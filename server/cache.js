/**
 * 音频缓存层（服务端 L2）
 *
 * 设计要点（每个都有实测依据）：
 *
 * 1. 缓存的是「音频字节」而不是「网易云 URL」。
 *    实测 CDN URL 路径含时间戳（/20261029004242/...），几十分钟后失效。
 *    缓存 URL 会周期性 404；缓存字节永久有效。
 *
 * 2. 键含音质：<songId>.<quality>.<ext>
 *    音质从 128k 升到 320k 时缓存自然失效重建，不会串味。
 *
 * 3. 并发去重：同一首歌的并发请求共用一个下载 Promise，
 *    不重复打上游（上游请求是风控的主要来源）。
 *
 * 4. 边下边发：未命中时先建立上游连接，音频数据同时写盘和发给客户端，
 *    客户端不必等整首下完。
 *
 * 5. LRU 淘汰按 atime，不是 mtime —— 读一次就把 atime 推后，
 *    「最近听过但很久没下载」的歌会被优先淘汰。
 */
const fs = require('fs')
const path = require('path')
const http = require('http')
const https = require('https')
const crypto = require('crypto')

const os = require('os')
const path0 = require('path')
const CACHE_DIR = process.env.NCM_CACHE_DIR || path0.join(os.homedir(), '.ncm-cache', 'audio')
const MAX_BYTES = parseInt(process.env.NCM_CACHE_MAX || String(20 * 1024 * 1024 * 1024), 10)

fs.mkdirSync(CACHE_DIR, { recursive: true })

// 启动时清掉上次进程崩溃/被杀留下的半截下载。
// 命名规则见 server.js：`.part.<文件名>.<pid>.<序号>`，隐藏文件不参与缓存记账。
;function cleanupStaleParts() {
  let removed = 0
  try {
    for (const name of fs.readdirSync(CACHE_DIR)) {
      if (!name.startsWith('.part.')) continue
      const p = path.join(CACHE_DIR, name)
      try {
        if (Date.now() - fs.statSync(p).mtimeMs > 6 * 3600 * 1000) {
          fs.unlinkSync(p)
          removed++
        }
      } catch {}
    }
  } catch {}
  if (removed) console.log('[cache] 清理残留分片', removed, '个')
}
cleanupStaleParts()

// 运行期泄漏兜底：上游中途报错/进程重启前留下的 .part 不在 listCache 里，
// 不主动扫就会一直在磁盘上躺着（单个能到上百 MB）。
setInterval(cleanupStaleParts, 3600 * 1000).unref?.()

/** songId.quality.ext → 绝对路径 */
function keyToFile(songId, quality, ext) {
  const safe = String(songId).replace(/[^0-9A-Za-z_-]/g, '')
  const q = String(quality || 'exhigh').replace(/[^0-9A-Za-z_-]/g, '')
  const e = (ext || 'mp3').replace(/[^0-9A-Za-z]/g, '')
  return path.join(CACHE_DIR, `${safe}.${q}.${e}`)
}

/** 正在进行的下载：key → Promise（并发去重） */
const inflight = new Map()

/* 总量缓存：避免每次下载都 readdir+stat 全目录（几千首时会很贵） */
let _totalBytes = null

function touch(file) {
  try { fs.utimesSync(file, new Date(), new Date()) } catch {}
}

function fileSize(file) {
  try { return fs.statSync(file).size } catch { return 0 }
}

/** 列出所有缓存文件及其大小/atime，按 atime 升序（最旧在前）；顺带刷新总量 */
function listCache() {
  const out = []
  let entries = []
  try { entries = fs.readdirSync(CACHE_DIR) } catch { _totalBytes = 0; return out }
  let total = 0
  for (const name of entries) {
    if (name.startsWith('.')) continue
    const p = path.join(CACHE_DIR, name)
    let st
    try { st = fs.statSync(p) } catch { continue }
    if (!st.isFile()) continue
    out.push({ name, path: p, size: st.size, atime: st.mtimeMs })
    total += st.size
  }
  out.sort((a, b) => a.atime - b.atime)
  _totalBytes = total
  return out
}

function cacheBytes() {
  if (_totalBytes === null) listCache()
  return _totalBytes || 0
}

/**
 * LRU 淘汰到上限以下。
 * @param {string} [keepPath] 刚写入的文件路径：只有它自己就超上限时才淘汰它
 * @returns {{evicted:number, freed:number}}
 */
function evict(keepPath) {
  const files = listCache()   // 已按 atime 升序（最旧在前），并把 _totalBytes 校正为真值
  let total = files.reduce((s, f) => s + f.size, 0)
  if (total <= MAX_BYTES) return { evicted: 0, freed: 0 }
  let evicted = 0, freed = 0
  for (const f of files) {
    if (total - freed <= MAX_BYTES) break
    // 保留刚写入的文件（LRU 下它本就最新，这里再兜一层）
    if (keepPath && f.path === keepPath && total - freed - f.size <= MAX_BYTES) continue
    try {
      fs.unlinkSync(f.path)
      evicted++
      freed += f.size
    } catch {}
  }
  // 关键：删完必须把缓存的总量计数同步掉，否则增量记账会虚高、后续永不淘汰
  if (_totalBytes !== null) _totalBytes = Math.max(0, total - freed)
  return { evicted, freed }
}

/** 缓存命中检查 */
function lookup(songId, quality, ext) {
  const p = keyToFile(songId, quality, ext)
  const size = fileSize(p)
  if (size > 0) {
    touch(p)
    return { path: p, size }
  }
  return null
}

/**
 * 从上游 URL 下载到缓存（原子提交：.part → rename）。
 * 返回 {path, size, ext}。
 */
function downloadToCache(songId, quality, url, ext = 'mp3') {
  const key = `${songId}.${quality}.${ext}`
  if (inflight.has(key)) return inflight.get(key)

  const p = downloadToCache._impl(songId, quality, url, ext)
  inflight.set(key, p)
  p.catch(() => {}).finally(() => inflight.delete(key))
  return p
}

downloadToCache._impl = async function (songId, quality, url, ext) {
  const finalPath = keyToFile(songId, quality, ext)
  const partPath = finalPath + '.part'
  const agent = url.startsWith('https:') ? https : http

  const res = await new Promise((resolve, reject) => {
    const req = agent.get(url, {
      headers: { 'User-Agent': 'Mozilla/5.0', 'Referer': 'https://music.163.com/' },
    }, (r) => {
      if (r.statusCode >= 300 && r.statusCode < 400 && r.headers.location) {
        r.resume()
        resolve(downloadToCache._impl(songId, quality, r.headers.location, ext))
        return
      }
      if (r.statusCode !== 200) {
        r.resume()
        reject(new Error('upstream HTTP ' + r.statusCode))
        return
      }
      resolve(r)
    })
    req.on('error', reject)
    req.setTimeout(60000, () => { req.destroy(new Error('upstream timeout')) })
  })

  const out = fs.createWriteStream(partPath)
  let written = 0
  const expect = parseInt(res.headers['content-length'] || '0', 10)

  await new Promise((resolve, reject) => {
    res.on('data', (c) => { written += c.length })
    res.pipe(out)
    out.on('finish', resolve)
    out.on('error', reject)
    res.on('error', reject)
  })

  // 校验：Content-Length 与实际字节数一致才算完整
  if (expect > 0 && written !== expect) {
    try { fs.unlinkSync(partPath) } catch {}
    throw new Error(`size mismatch: got ${written}, expect ${expect}`)
  }
  if (written === 0) {
    try { fs.unlinkSync(partPath) } catch {}
    throw new Error('empty download')
  }

  // 原子提交
  try { fs.renameSync(partPath, finalPath) } catch (e) {
    try { fs.unlinkSync(partPath) } catch {}
    throw e
  }
  touch(finalPath)

  // 增量记总量（不必扫目录），超上限才真正扫盘淘汰
  _totalBytes = (_totalBytes === null ? null : _totalBytes + written)
  if (_totalBytes === null) cacheBytes()
  if (_totalBytes > MAX_BYTES) evict(finalPath)

  const name = path.basename(finalPath)
  const dot = name.lastIndexOf('.')
  return {
    path: finalPath,
    size: written,
    ext: dot > 0 ? name.slice(dot + 1) : 'mp3',
  }
}

/**
 * 外部写入路径（server.js 里「边下边发」那条流）落盘后调用。
 * 那条路不经过 downloadToCache，所以必须由它自己记账；否则总量永远不涨，
 * 淘汰永远不触发 —— 这条路径以前就漏了。
 */
function noteWritten(filePath, bytes, prevSize = null) {
  // 流式路径是 rename 覆盖同名文件：旧文件的空间必须先减掉，
  // 否则同一首歌重复下载会让总量虚高（虚高会让 evict 误删别人的歌）。
  let prev = prevSize
  if (prev === null) {
    // 调用方没给就自己估：此刻文件已是新大小，无从得知旧值；
    // 保守做法是不累加（listCache 会重新算出真值）。
    _totalBytes = null
    cacheBytes()
    if (_totalBytes > MAX_BYTES) evict(filePath)
    return
  }
  if (_totalBytes !== null) _totalBytes = Math.max(0, _totalBytes - prev + bytes)
  else cacheBytes()
  if (_totalBytes > MAX_BYTES) evict(filePath)
}

module.exports = {
  CACHE_DIR, MAX_BYTES,
  keyToFile, lookup, downloadToCache, evict,
  listCache, cacheBytes, touch, fileSize, inflight,
  noteWritten, invalidateTotal: () => { _totalBytes = null },
}
