/**
 * 注册匿名设备身份（MUSIC_A）并持久化。
 *
 * 为什么需要：网易云扫码登录会校验「设备身份」。裸请求（没有 MUSIC_A cookie）
 * 会被风控判成异常设备 —— 手机扫描时 App 弹出
 * 「检测到当前设备环境异常 本次操作已拦截」。
 *
 * 用法（容器内）：node /app/server/anonimous.js
 * 产物：/app/cache/meta/music_a.json  { musicA, deviceId, at }
 * 之后 ncm-api.js 的 loadCookie() 会把它拼进每次请求的 cookie。
 */
const fs = require('fs')
const os = require('os')
const path = require('path')

const NCM_CANDIDATES = [
  process.env.NCM_ROOT,
  path.join(__dirname, '..', 'node_modules', 'NeteaseCloudMusicApi'),
  path.join(__dirname, '..', 'node_modules'),
  path.join(os.homedir(), '.hermes', 'ncm-api'),
].filter(Boolean)
const NCM = NCM_CANDIDATES.find((p) => {
  try { return fs.existsSync(path.join(p, 'module', 'register_anonimous.js')) } catch { return false }
}) || NCM_CANDIDATES[0]
const request = require(path.join(NCM, 'util/request.js'))
const createOption = require(path.join(NCM, 'util/option.js'))
const mod = require(path.join(NCM, 'module/register_anonimous.js'))

const OUT = process.env.NCM_DEVICE_FILE
  || path.join(process.env.NCM_META_DIR || '/app/cache/meta', 'music_a.json')
// 全新环境这两个目录都不存在，写之前先建
try { fs.mkdirSync(path.dirname(OUT), { recursive: true }) } catch { }

;(async () => {
  const res = await mod({}, request, createOption({ crypto: 'weapi' }))
  const code = res?.body?.code
  const cookie = Array.isArray(res?.cookie) ? res.cookie.join(';') : String(res?.cookie || '')
  console.log('[anonimous] code =', code)
  console.log('[anonimous] cookie =', cookie.slice(0, 200))

  if (code !== 200) {
    console.error('[anonimous] 失败，未写入')
    process.exit(1)
  }

  const pick = (name) => {
    const m = new RegExp('(?:^|;\\s*)' + name + '=([^;]+)').exec(cookie)
    return m ? m[1] : ''
  }
  const musicA = pick('MUSIC_A')
  const deviceId = pick('deviceId') || global.deviceId || ''
  if (!musicA) {
    console.error('[anonimous] 响应里没有 MUSIC_A')
    process.exit(1)
  }
  fs.mkdirSync(path.dirname(OUT), { recursive: true })
  fs.writeFileSync(OUT, JSON.stringify({ musicA, deviceId, at: Date.now() }), 'utf8')
  console.log('[anonimous] 已写入', OUT, 'musicA len =', musicA.length, 'deviceId =', deviceId)
})()
