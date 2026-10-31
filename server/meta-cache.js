/*
 * Netodia Car — 网易云音乐车机播放器
 * Copyright (c) 2026 nak1li
 *
 * Required Notice: Copyright (c) 2026 nak1li (Netodia Car). This project is licensed for
 * non-commercial use only. See LICENSE for details.
 *
 * 授权：PolyForm Noncommercial License 1.0.0 —— 仅限非商业用途。
 * 商业使用（含预装售卖、SaaS 收费、集成进商业产品）须先取得著作权人书面授权。
 */
/**
 * 元数据缓存（歌词/歌曲详情），带 TTL 与「负缓存」。
 *
 * 设计来源：Siren 的 disk_lru 实践 —— 两条最容易踩的坑：
 *
 * 1. **负缓存**：歌词查询「查不到」是常态（纯音乐、冷门曲）。如果不缓存这个
 *    「空结果」，每次播放都要重打一次必然失败的请求，既慢又给上游风控喂数据。
 *    所以空结果也落盘，但用更短的 TTL（12h vs 7d）。
 *
 * 2. **TTL 与 LRU 分离**：mtime 只负责 LRU 排序，真正的新鲜度靠条目内的
 *    fetchedAt 判断。否则 trim 一次就把所有条目的「年龄」重置了。
 *
 * 另外：在线成功即写、仅加载失败才读 —— 它是兜底层，不是加速层，
 * 永不展示过期数据。
 */
const fs = require('fs')
const path = require('path')

const META_DIR = process.env.NCM_META_DIR || require('path').join(require('os').homedir(), '.ncm-cache', 'meta')
const MAX_BYTES = parseInt(process.env.NCM_META_MAX || String(64 * 1024 * 1024), 10)
const TTL_OK = parseInt(process.env.NCM_META_TTL || String(7 * 24 * 3600), 10) * 1000   // 7 天
const TTL_EMPTY = 12 * 3600 * 1000                                                     // 空结果 12 小时

fs.mkdirSync(META_DIR, { recursive: true })

function keyFile(ns, key) {
  const safeNs = String(ns).replace(/[^0-9A-Za-z_-]/g, '')
  const safeKey = String(key).replace(/[^0-9A-Za-z_-]/g, '')
  return path.join(META_DIR, `${safeNs}.${safeKey}.json`)
}

/** 读缓存：命中且未过期返回 {value, empty}；否则 null */
function get(ns, key) {
  const p = keyFile(ns, key)
  let raw
  try { raw = fs.readFileSync(p, 'utf8') } catch { return null }
  let obj
  try { obj = JSON.parse(raw) } catch { return null }
  if (!obj || typeof obj.fetchedAt !== 'number') return null

  const age = Date.now() - obj.fetchedAt
  const empty = !obj.value || (typeof obj.value === 'string' && obj.value.length === 0)
  const ttl = empty ? TTL_EMPTY : TTL_OK
  if (age > ttl) {
    try { fs.unlinkSync(p) } catch {}
    return null
  }
  // 刷新 mtime 供 LRU 使用（真正的 TTL 靠 fetchedAt）
  try { fs.utimesSync(p, new Date(), new Date()) } catch {}
  return { value: obj.value, empty }
}

/** 写缓存（空结果也写，负缓存） */
function put(ns, key, value) {
  const p = keyFile(ns, key)
  const tmp = p + '.tmp'
  const obj = { fetchedAt: Date.now(), value }
  try {
    fs.writeFileSync(tmp, JSON.stringify(obj), 'utf8')
    fs.renameSync(tmp, p)   // 原子提交，进程中途死掉不留半个文件
  } catch {
    try { fs.unlinkSync(tmp) } catch {}
  }
}

/** 容量淘汰：按 mtime 最旧优先 */
function trim() {
  let entries = []
  try { entries = fs.readdirSync(META_DIR) } catch { return 0 }
  const files = []
  let total = 0
  for (const name of entries) {
    if (name.endsWith('.tmp')) { try { fs.unlinkSync(path.join(META_DIR, name)) } catch {}; continue }
    const p = path.join(META_DIR, name)
    let st
    try { st = fs.statSync(p) } catch { continue }
    files.push({ p, size: st.size, mtime: st.mtimeMs })
    total += st.size
  }
  if (total <= MAX_BYTES) return 0
  files.sort((a, b) => a.mtime - b.mtime)
  let freed = 0
  for (const f of files) {
    if (total - freed <= MAX_BYTES) break
    try { fs.unlinkSync(f.p); freed += f.size } catch {}
  }
  return freed
}

function stats() {
  let n = 0, bytes = 0
  try {
    for (const name of fs.readdirSync(META_DIR)) {
      try {
        const st = fs.statSync(path.join(META_DIR, name))
        n++; bytes += st.size
      } catch {}
    }
  } catch {}
  return { dir: META_DIR, fileCount: n, usedBytes: bytes, maxBytes: MAX_BYTES }
}

module.exports = { get, put, trim, stats }
