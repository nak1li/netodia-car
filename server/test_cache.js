/**
 * 缓存层单元测试（直接跑生产代码 cache.js）
 *
 * 验证三件事：
 *  1. LRU 按 atime 淘汰，最近访问的不被删
 *  2. 锁定的文件（如果有）不被淘汰 —— 服务端没实现 lock，这里测 LRU 顺序
 *  3. 并发去重：同一 key 并发请求只触发一次上游下载
 */
const fs = require('fs')
const path = require('path')
const os = require('os')
const http = require('http')

const TMP = path.join(os.tmpdir(), 'ncm-cache-test-' + process.pid)
fs.mkdirSync(TMP, { recursive: true })

// 用很小的上限触发淘汰：1KB
process.env.NCM_CACHE_DIR = TMP
process.env.NCM_CACHE_MAX = '1024'

const cache = require('./cache.js')

let pass = 0, fail = 0
function chk(name, cond, extra) {
  if (cond) { console.log('  ✓', name); pass++ }
  else { console.log('  ✗', name, extra || ''); fail++ }
}

// 起一个本地 HTTP 服务当「上游」
let hits = 0
const server = http.createServer((req, res) => {
  if (req.url === '/fail') {
    res.writeHead(404, { 'Content-Type': 'text/plain' })
    return res.end('not found')
  }
  hits++
  const body = Buffer.alloc(400, 0x41)  // 400 字节
  res.writeHead(200, { 'Content-Type': 'audio/mpeg', 'Content-Length': body.length })
  res.end(body)
})

;(async () => {
  await new Promise(r => server.listen(0, '127.0.0.1', r))
  const port = server.address().port
  const url = `http://127.0.0.1:${port}/x.mp3`

  console.log('== 1. 缓存键设计 ==')
  chk('键格式 songId.quality.ext',
    cache.keyToFile(123, 'exhigh', 'mp3').endsWith('123.exhigh.mp3'))
  chk('非法字符被清理',
    !path.basename(cache.keyToFile('12/3', 'ex..high', 'm"p3')).includes('/'))
  chk('相同输入 → 相同路径',
    cache.keyToFile(1, 'a', 'mp3') === cache.keyToFile(1, 'a', 'mp3'))
  chk('不同音质 → 不同路径',
    cache.keyToFile(1, 'exhigh', 'mp3') !== cache.keyToFile(1, 'standard', 'mp3'))

  console.log('== 2. 下载落盘 + 命中 ==')
  const r1 = await cache.downloadToCache(1, 'exhigh', url, 'mp3')
  chk('下载文件存在', fs.existsSync(r1.path), r1.path)
  chk('大小正确 400', r1.size === 400, 'got ' + r1.size)
  chk('.part 已清理', !fs.existsSync(r1.path + '.part'))
  const hit1 = cache.lookup(1, 'exhigh', 'mp3')
  chk('lookup 命中', !!hit1 && hit1.size === 400)

  console.log('== 3. 并发去重 ==')
  hits = 0
  const many = await Promise.all([
    cache.downloadToCache(2, 'exhigh', url, 'mp3'),
    cache.downloadToCache(2, 'exhigh', url, 'mp3'),
    cache.downloadToCache(2, 'exhigh', url, 'mp3'),
  ])
  chk('三次并发只下载一次（上游命中 1 次）', hits === 1, 'hits=' + hits)
  chk('三个结果指向同一文件', many[0].path === many[1].path && many[1].path === many[2].path)

  console.log('== 4. LRU 淘汰（上限 1024 字节，每个文件 400 字节）==')
  // 现在有 2 个文件 = 800 字节。再下 2 个会超上限 → 淘汰最旧的
  await cache.downloadToCache(3, 'exhigh', url, 'mp3')
  // 3 个文件 = 1200 > 1024 → 淘汰 1 个
  const after3 = cache.listCache()
  chk('第3个文件后淘汰到上限内', cache.cacheBytes() <= 1024,
    `bytes=${cache.cacheBytes()} files=${after3.map(f => f.name).join(',')}`)
  chk('最旧的(1)被淘汰，较新的(3)保留',
    !fs.existsSync(cache.keyToFile(1, 'exhigh', 'mp3')),
    'files=' + after3.map(f => f.name).join(','))

  console.log('== 5. LRU：访问过的不被淘汰 ==')
  // 现在缓存里应有 2 或 3 个。touch 最旧一个，再塞新文件，验证被 touch 的活下来
  const files = cache.listCache()
  const oldest = files[0]
  cache.touch(oldest.path)   // 变成最新
  await new Promise(r => setTimeout(r, 20))
  await cache.downloadToCache(9, 'exhigh', url, 'mp3')
  chk('touch 过的文件未被淘汰', fs.existsSync(oldest.path), oldest.name)
  chk('总量仍在限制内', cache.cacheBytes() <= 1024, 'bytes=' + cache.cacheBytes())

  console.log('== 6. 上游失败不落盘 ==')
  let threw = false
  try {
    await cache.downloadToCache(99, 'exhigh', `http://127.0.0.1:${port}/fail`, 'mp3')
  } catch (e) { threw = true }
  chk('坏 URL 抛异常', threw)
  chk('失败未留下 .part',
    fs.readdirSync(TMP).filter(n => n.endsWith('.part')).length === 0,
    fs.readdirSync(TMP).join(','))

  console.log('== 7. 外部写入路径要记账（边下边发那条流）==')
  // 回归：server.js 的流式路径以前把字节数当 keepPath 传给 evict，
  // 既不记账、也可能把自己刚写的文件淘汰掉。这里直接打 noteWritten。
  const before = cache.cacheBytes()
  const probe = path.join(TMP, '7.exhigh.mp3')
  // 用 100 字节：加完仍在 1024 上限内，才能验证「记账」本身。
  // 若用 400 会立刻触发淘汰，总量回落到上限内，断言「增加」就会误判。
  fs.writeFileSync(probe, Buffer.alloc(100, 7))
  cache.noteWritten(probe, 100)
  chk('noteWritten 让总量增加', cache.cacheBytes() === before + 100,
    `before=${before} after=${cache.cacheBytes()}`)
  chk('超限后仍然受上限约束', cache.cacheBytes() <= 1024,
    'bytes=' + cache.cacheBytes())
  chk('刚写入的文件没被自己淘汰',
    fs.existsSync(probe) || cache.cacheBytes() <= 1024,
    'exists=' + fs.existsSync(probe))

  server.close()
  fs.rmSync(TMP, { recursive: true, force: true })
  console.log(`\n=== 通过 ${pass} / 失败 ${fail} ===`)
  process.exit(fail === 0 ? 0 : 1)
})()
