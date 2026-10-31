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
 * ncm-server —— 车机音乐服务的网易云 API 层
 *
 * 职责：把网易云的加密/风控/失效 URL 全部关在服务端，
 *       对车机只暴露一组稳定的 JSON 接口 + 一个音频流。
 *
 * 车机不可物理到达 → 任何协议变化都必须在这里吸收，不能推给 APK。
 */
const fs = require('fs')
const path = require('path')
const os = require('os')

// 官方 request.js 启动时读 tmpdir/anonymous_token
const tokPath = path.resolve(os.tmpdir(), 'anonymous_token')
if (!fs.existsSync(tokPath)) fs.writeFileSync(tokPath, '', 'utf-8')

/**
 * 找到网易云 API 实现层的根目录。
 *
 * 三种布局都得支持（否则别人克隆下来能启动、但每个接口都 500）：
 *  1) 本项目 npm install 后：<repo>/node_modules
 *  2) 独立 checkout 的老实现：NCM_ROOT 指向含 util/ 与 module/ 的目录
 *  3) 以前手工部署：~/.hermes/ncm-api
 */
const NCM_CANDIDATES = [
  process.env.NCM_ROOT,
  path.join(__dirname, '..', 'node_modules'),
  path.join(__dirname, '..', '..', 'node_modules'),
  path.join(os.homedir(), '.hermes', 'ncm-api'),
].filter(Boolean)

function pickRoot(subs) {
  for (const root of NCM_CANDIDATES) {
    for (const sub of subs) {
      const p = sub ? path.join(root, sub) : root
      try { if (fs.existsSync(p)) return p } catch { }
    }
  }
  return null
}

// legacy 实现（util/request.js、module/*.js）的根
const NCM = pickRoot(['NeteaseCloudMusicApi', '']) || NCM_CANDIDATES[1]
// api-enhanced 的根（自己能解析 require 的目录）
const ENHANCED = pickRoot([
  path.join('@neteasecloudmusicapienhanced', 'api'),
  path.join('node_modules', '@neteasecloudmusicapienhanced', 'api'),
]) || path.join(NCM, '@neteasecloudmusicapienhanced', 'api')
// 旧实现（request/createOption）改成懒加载，见下方 loadLegacy()

const COOKIE_FILE = process.env.NC_COOKIE_FILE
  || require('path').join(require('os').homedir(), '.ncm', 'secrets', 'ncm.cookie')
// 匿名设备身份（MUSIC_A + deviceId），由 server/anonimous.js 注册后落盘。
// 没有它，登录类请求会被网易云判成「异常设备」直接拦掉。
const DEVICE_FILE = process.env.NCM_DEVICE_FILE
  || path.join(process.env.NCM_META_DIR || path.join(os.homedir(), '.ncm-cache', 'meta'),
               'music_a.json')

let _device = null
function loadDevice() {
  if (_device) return _device
  try {
    _device = JSON.parse(fs.readFileSync(DEVICE_FILE, 'utf8'))
  } catch {
    _device = {}
  }
  return _device
}

function loadCookie() {
  let base = ''
  try {
    base = fs.readFileSync(COOKIE_FILE, 'utf8').trim()
  } catch {
    base = ''
  }
  // 已登录（有 MUSIC_U）时不要再塞自己注册的 MUSIC_A：
  // 设备身份和登录态对不上，网易云会当成「未登录」，接口全回 account:null。
  // 只有匿名态才补设备身份，避免裸请求被风控。
  const d = loadDevice()
  const parts = []
  if (base) parts.push(base)
  if (!/MUSIC_U=/.test(base)) {
    if (d.musicA) parts.push('MUSIC_A=' + d.musicA)
    if (d.deviceId) parts.push('deviceId=' + d.deviceId)
  }
  return parts.join('; ')
}

// 出口真实公网 IP：网易云用 realIP 参数做来源判断/风控指纹。
// 本机出口本身就走家宽（到 163 是直连），把同一个 IP 报上去，
// 避免「参数里的 IP 与实际来源不一致」这种最容易被风控抓的破绽。
// 缓存 1 小时；拿不到就退回 env，再拿不到就不传（保持原行为）。
let _ip = ''
let _ipAt = 0

function fetchRealIP() {
  return new Promise((resolve) => {
    const req = require('http').get('http://ip.3322.net', { timeout: 3000 }, (r) => {
      let b = ''
      r.on('data', (d) => { b += d })
      r.on('end', () => resolve(b.trim().slice(0, 20)))
    })
    req.on('error', () => resolve(''))
    req.on('timeout', () => { req.destroy(); resolve('') })
  })
}

async function realIP() {
  if (_ip && Date.now() - _ipAt < 3600 * 1000) return _ip
  const got = await fetchRealIP()
  const val = /^\d{1,3}(\.\d{1,3}){3}$/.test(got) ? got : (process.env.NCM_REAL_IP || '')
  if (val) { _ip = val; _ipAt = Date.now() }
  return _ip
}

// 接口实现层：优先用 api-enhanced（NeteaseCloudMusicApiEnhanced）。
//
// 为什么换：老版 v4.32 只有 weapi/eapi，且 weapi 的 cookie 处理会让
// 带 MUSIC_U 的请求被网易云认成「另一台设备」（account 恒为 null）；
// api-enhanced 这边维护着 neapi/xeapi 与新 UA，同一个 cookie 直接就能登录态。
// 老实现保留为兜底：enhanced 加载失败时自动退回去，服务不至于起不来。
const ENHANCED_ROOT = ENHANCED

let enhanced = null
let legacy = null

function loadEnhanced() {
  if (enhanced !== null) return enhanced
  try {
    enhanced = require(ENHANCED_ROOT)
    console.log('[ncm-api] 使用 api-enhanced 实现层')
  } catch (e) {
    console.error('[ncm-api] api-enhanced 不可用，回退旧实现:', e.message)
    enhanced = false
  }
  return enhanced
}

// xeapi 接口（song_url_v1 等）需要 tmpdir/xeapi_public_key 里的公钥。
// 容器 /tmp 重启就没了，所以在首次调用前按需拉一次并落盘。
let _xeapiReady = null
function ensureXeapiKey(api) {
  if (_xeapiReady) return _xeapiReady
  _xeapiReady = (async () => {
    const pkPath = path.resolve(os.tmpdir(), 'xeapi_public_key')
    if (fs.existsSync(pkPath)) return
    try {
      const d = loadDevice()
      const res = await api.register_xeapikey({ deviceId: d.deviceId || '' })
      if (res && res.body && res.body.sk) {
        fs.writeFileSync(pkPath, JSON.stringify(res.body), 'utf-8')
        console.log('[ncm-api] xeapi 公钥已写入，version=', res.body.version)
      } else {
        console.error('[ncm-api] xeapi 公钥响应异常:', JSON.stringify(res && res.body).slice(0, 120))
      }
    } catch (e) {
      console.error('[ncm-api] xeapi 公钥获取失败:', e.message)
    }
  })()
  return _xeapiReady
}

function loadLegacy() {
  if (legacy) return legacy
  const base = pickRoot(['NeteaseCloudMusicApi', '']) || NCM
  legacy = {
    request: require(path.join(base, 'util/request.js')),
    createOption: require(path.join(base, 'util/option.js')),
  }
  return legacy
}

async function call(moduleName, query) {
  const cookie = loadCookie()
  const ip = await realIP()
  const api = loadEnhanced()
  if (api && typeof api[moduleName] === 'function') {
    await ensureXeapiKey(api)
    const res = await api[moduleName]({ ...query, cookie, realIP: ip })
    return res && res.body !== undefined ? res.body : res
  }
  const legacyApi = loadLegacy()
  const mod = require(path.join(pickRoot(['NeteaseCloudMusicApi', '']) || NCM, 'module', moduleName + '.js'))
  const res = await mod(query, legacyApi.request, legacyApi.createOption({ cookie, crypto: 'weapi', realIP: ip }))
  return res.body
}

/** 曲目规范化：车机端只认这个结构 */
function normSong(s) {
  if (!s) return null
  return {
    id: s.id,
    name: s.name,
    artists: (s.ar || s.artists || []).map((a) => ({ id: a.id, name: a.name })),
    album: s.al ? { id: s.al.id, name: s.al.name, pic: s.al.picUrl || null }
                : (s.album ? { id: s.album.id, name: s.album.name, pic: s.album.picUrl || null } : null),
    duration: s.dt || s.duration || 0,
    fee: s.fee,
    mv: s.mv || 0,
  }
}

module.exports = { call, normSong, loadCookie, COOKIE_FILE }
