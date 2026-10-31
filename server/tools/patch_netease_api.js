#!/usr/bin/env node
/**
 * 给 NeteaseCloudMusicApi（legacy 兜底实现）打一个本地补丁。
 *
 * 补丁内容：非 login 接口不再替换 NMTID。
 *   NMTID 是与登录态绑定的设备 token，上游对非 login 请求一律换成随机值，
 *   结果带着有效 MUSIC_U 的请求会被网易云当成「另一台设备」，
 *   account 返回 null（看起来像没登录）。
 *
 * 幂等：已经打过补丁就跳过，可以反复执行。
 */
const fs = require('fs')
const path = require('path')

const MARK = '[ncm-car patch]'
const targets = [
  path.join(__dirname, '..', 'node_modules', 'NeteaseCloudMusicApi', 'util', 'request.js'),
  path.join(__dirname, '..', '..', 'node_modules', 'NeteaseCloudMusicApi', 'util', 'request.js'),
]

for (const p of targets) {
  if (!fs.existsSync(p)) continue
  let src = fs.readFileSync(p, 'utf8')
  if (src.includes(MARK)) {
    console.log('[patch] 已打过补丁，跳过:', p)
    continue
  }
  const needle = "if (uri.indexOf('login') === -1) {"
  if (!src.includes(needle)) {
    console.warn('[patch] 目标代码没找到（上游可能已改），跳过:', p)
    continue
  }
  const patched = src.replace(needle,
    `// ${MARK} NMTID 与登录态绑定，非 login 接口不再替换成随机值\n` +
    `  if (uri.indexOf('login') === -1 && !processedCookie['NMTID']) {`)
  fs.writeFileSync(p, patched, 'utf8')
  console.log('[patch] 已打补丁:', p)
}
