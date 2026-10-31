/**
 * ncm-server HTTP 主体
 *
 * 只用 Node 内置模块 + 官方 NeteaseCloudMusicApi 的 crypto/module 层。
 * 不引 express —— 减少依赖和攻击面。
 */
const http = require('http')
const fs = require('fs')
const path = require('path')
const { URL } = require('url')
const { call, normSong, COOKIE_FILE } = require('./ncm-api.js')
const cache = require('./cache.js')
const metaCache = require('./meta-cache.js')

const PORT = parseInt(process.env.NCM_PORT || '28475', 10)
const HOST = process.env.NCM_HOST || '0.0.0.0'
const TOKEN = process.env.NCM_TOKEN || ''

// 音质档位 → 网易云 level
// 实测（8 首抽样）：jymaster / jyeffect / sky / dolby 上游返回的是同一份字节
// （同 br 同 size），所以它们归一化成同一个 canonical 名字，
// 否则同一首歌会在盘上存 4 份 92MB 的 FLAC。
const QUALITY = {
  standard: 'standard',   // 128k
  high: 'standard',
  exhigh: 'exhigh',       // 320k
  lossless: 'lossless',   // 需 VIP
  hires: 'hires',         // 需 VIP
  jymaster: 'jymaster',   // 超清母带，需 VIP
  jyeffect: 'jymaster',
  sky: 'jymaster',
  dolby: 'jymaster',
}

/** 档位校验 + 归一化：不认识的档位一律 400，别静默当 exhigh 存一份山寨缓存 */
function normQuality(q) {
  const k = String(q || 'exhigh').toLowerCase()
  if (!Object.prototype.hasOwnProperty.call(QUALITY, k)) return null
  return { key: k, level: QUALITY[k], canonical: QUALITY[k] === 'standard' ? 'standard'
           : QUALITY[k] === 'exhigh' ? 'exhigh'
           : QUALITY[k] === 'lossless' ? 'lossless'
           : QUALITY[k] === 'hires' ? 'hires' : 'jymaster' }
}

function send(res, code, obj, extraHeaders = {}) {
  const body = Buffer.from(JSON.stringify(obj), 'utf8')
  res.writeHead(code, {
    'Content-Type': 'application/json; charset=utf-8',
    'Content-Length': body.length,
    'Access-Control-Allow-Origin': '*',
    ...extraHeaders,
  })
  res.end(body)
}

function notFound(res, msg = 'not found') {
  send(res, 404, { error: msg })
}

/** 取播放地址（服务端拿网易云 URL，车机不接触网易云） */
async function resolveUrl(songId, quality) {
  const level = QUALITY[quality] || 'exhigh'
  const body = await call('song_url_v1', { id: String(songId), level })
  const d = body?.data?.[0]
  if (!d || !d.url) {
    return { ok: false, reason: 'no_url', fee: d?.fee, code: body?.code }
  }
  return {
    ok: true,
    url: d.url,
    br: d.br,
    size: d.size,
    type: d.type || 'mp3',
    level: d.level || level,
    fee: d.fee,
    time: d.time,
  }
}

/* ---------------- 登录态 / 红心缓存 ---------------- */

// uid → {ids, at}；红心列表变动频繁但读得更多，5 分钟内存缓存足够
const likedCache = new Map()
const LIKED_TTL = 5 * 60 * 1000

/** 当前登录用户 id；未登录返回 0。login_status 失败不抛，按未登录处理。
 *  60 秒内存缓存：红心/推荐/歌单每个请求都要 uid，不缓存等于每次多打一次上游。 */
let _uid = { id: 0, at: 0 }
const UID_TTL = 60 * 1000

async function currentUid(force = false) {
  const r = await uidState(force)
  return r.id
}

/**
 * 当前登录用户 id，并区分「上游查询失败」和「确实未登录」。
 *
 * 以前 catch 里一律 id=0，路由就回 403 login_required，
 * 车机会引导用户重新扫码 —— 但真实登录态还在，白折腾一趟。
 * 现在查询失败回 502 upstream_error，只有上游明确说没登录才回 403。
 */
async function uidState(force = false) {
  if (!force && _uid.at && Date.now() - _uid.at < UID_TTL) {
    return { id: _uid.id, err: _uid.err || null }
  }
  let id = 0
  let err = null
  try {
    const body = await call('login_status', {})
    id = body?.data?.account?.id || 0
    if (!id && body?.code !== 200 && body?.code !== undefined) {
      err = 'upstream returned code ' + body.code
    }
  } catch (e) {
    err = e && e.message ? e.message : String(e)
  }
  _uid = { id, at: Date.now(), err }
  return { id, err }
}

/** 我的红心歌曲 id（最近红心在前） */
async function likedIds(uid) {
  const k = String(uid)
  const hit = likedCache.get(k)
  if (hit && Date.now() - hit.at < LIKED_TTL) return hit.ids
  const body = await call('likelist', { uid: k })
  const ids = body?.ids || []
  likedCache.set(k, { ids, at: Date.now() })
  return ids
}

/* ---------------- 路由 ---------------- */

const routes = {
  async '/api/health'(req, res) {
    send(res, 200, { ok: true, time: Date.now(), cacheBytes: cache.cacheBytes() })
  },

  async '/api/search'(req, res, u) {
    const q = u.searchParams.get('q') || ''
    const type = parseInt(u.searchParams.get('type') || '1', 10)
    const limit = Math.min(parseInt(u.searchParams.get('limit') || '30', 10), 100)
    if (!q) return send(res, 400, { error: 'q required' })
    const body = await call('search', { keywords: q, type, limit })
    const r = body?.result || {}
    if (type === 1) {
      send(res, 200, { code: body?.code, total: r.songCount, songs: (r.songs || []).map(normSong) })
    } else if (type === 1000) {
      send(res, 200, { code: body?.code, total: r.playlistCount, playlists: (r.playlists || []).map((p) => ({
        id: p.id, name: p.name, cover: p.coverImgUrl, playCount: p.playCount, trackCount: p.trackCount,
        creator: p.creator?.name,
      })) })
    } else if (type === 10) {
      send(res, 200, { code: body?.code, total: r.albumCount, albums: (r.albums || []).map((a) => ({
        id: a.id, name: a.name, cover: a.picUrl, artist: a.artist?.name, size: a.size,
      })) })
    } else if (type === 100) {
      send(res, 200, { code: body?.code, total: r.artistCount, artists: (r.artists || []).map((a) => ({
        id: a.id, name: a.name, cover: a.picUrl, albumSize: a.albumSize,
      })) })
    } else {
      send(res, 200, { code: body?.code, result: r })
    }
  },

  async '/api/playlist/recommend'(req, res, u) {
    const limit = Math.min(parseInt(u.searchParams.get('limit') || '30', 10), 100)
    const body = await call('personalized', { limit })
    send(res, 200, {
      code: body?.code,
      playlists: (body?.result || []).map((p) => ({
        id: p.id, name: p.name, cover: p.coverImgUrl, playCount: p.playCount,
        trackCount: p.trackCount, creator: p.creator?.name,
      })),
    })
  },

  async '/api/playlist/detail'(req, res, u) {
    const id = u.searchParams.get('id')
    if (!id) return send(res, 400, { error: 'id required' })
    const limit = Math.min(parseInt(u.searchParams.get('limit') || '200', 10), 1000)
    const [d, t] = await Promise.all([
      call('playlist_detail', { id, s: 0 }),
      call('playlist_track_all', { id, limit }),
    ])
    const pl = d?.body?.playlist || d?.playlist || {}
    send(res, 200, {
      code: d?.code,
      playlist: {
        id: pl.id, name: pl.name, cover: pl.coverImgUrl,
        description: pl.description, creator: pl.creator?.name,
        trackCount: pl.trackCount,
      },
      songs: (t?.songs || []).map(normSong).filter(Boolean),
    })
  },

  async '/api/toplist'(req, res) {
    const body = await call('toplist', {})
    send(res, 200, {
      code: body?.code,
      groups: (body?.list || []).map((g) => ({
        id: g.id, name: g.name, cover: g.coverImgUrl, updateFrequency: g.updateFrequency,
        tracks: (g.tracks || []).slice(0, 50).map(normSong).filter(Boolean),
      })),
    })
  },

  async '/api/song/url'(req, res, u) {
    const id = u.searchParams.get('id')
    if (!id) return send(res, 400, { error: 'id required' })
    const quality = u.searchParams.get('quality') || 'exhigh'
    const r = await resolveUrl(id, quality)
    if (!r.ok) return send(res, 404, { error: 'no_url', reason: r.reason, fee: r.fee })
    send(res, 200, { id: Number(id), quality, br: r.br, size: r.size, type: r.type, level: r.level, fee: r.fee })
  },

  async '/api/lyric'(req, res, u) {
    const id = u.searchParams.get('id')
    if (!id) return send(res, 400, { error: 'id required' })

    // 先查缓存（含负缓存：空歌词也缓存 12h，避免每次播放重试必然失败的请求）
    const hit = metaCache.get('lyric', id)
    if (hit) {
      let c = hit.value
      if (typeof c === 'string') { try { c = JSON.parse(c) } catch { c = { lrc: c, tlyric: '' } } }
      return send(res, 200, { code: 200, lrc: (c && c.lrc) || '',
                              tlyric: (c && c.tlyric) || '',
                              translation: (c && c.tlyric) || '',
                              cached: true, empty: hit.empty })
    }

    const body = await call('lyric', { id })
    const lrc = body?.lrc?.lyric || ''
    const tlyric = body?.tlyric?.lyric || ''
    // 连翻译一起缓存：只存 lrc 会让「第一次有翻译、之后 7 天都没有」
    metaCache.put('lyric', id, JSON.stringify({ lrc, tlyric }))
    metaCache.trim()
    send(res, 200, { code: body?.code, lrc, tlyric, translation: tlyric, cached: false })
  },

  async '/api/song/detail'(req, res, u) {
    const id = u.searchParams.get('id')
    if (!id) return send(res, 400, { error: 'id required' })
    const body = await call('song_detail', { ids: String(id) })
    const s = body?.songs?.[0]
    send(res, 200, { code: body?.code, song: normSong(s) })
  },

  /* ---------- 我的音乐：每日推荐 / 红心 / 歌单（都需要已登录 cookie） ---------- */

  // 每日推荐：网易云按天的个性化推荐，没登录只会返回登录错误，所以先自查登录态
  async '/api/recommend/songs'(req, res, u) {
    const limit = Math.min(parseInt(u.searchParams.get('limit') || '30', 10), 300)
    const st = await uidState()
    if (st.err) return send(res, 502, { error: 'upstream_error', msg: st.err, songs: [] })
    if (!st.id) return send(res, 403, { code: 301, error: 'login_required', songs: [] })
    const body = await call('recommend_songs', {})
    const songs = (body?.data?.dailySongs || []).slice(0, limit).map(normSong).filter(Boolean)
    if (songs.length === 0) {
      // 上游可能以 301/其它 code 表示“登录态无效”，要如实告诉车机去重登
      return send(res, 403, { code: 301, error: 'login_required', upstreamCode: body?.code, songs: [] })
    }
    send(res, 200, { code: body?.code, songs, total: songs.length })
  },

  // 红心 id 列表（车机用来渲染每行的红心状态）
  async '/api/likelist'(req, res) {
    const st = await uidState()
    if (st.err) return send(res, 502, { error: 'upstream_error', msg: st.err, ids: [] })
    if (!st.id) return send(res, 403, { code: 301, error: 'login_required', ids: [] })
    const ids = await likedIds(st.id)
    send(res, 200, { code: 200, ids, count: ids.length })
  },

  // 红心歌曲（带曲目详情，车机一次请求就能渲染列表）
  async '/api/likelist/songs'(req, res, u) {
    const st = await uidState()
    if (st.err) return send(res, 502, { error: 'upstream_error', msg: st.err, ids: [], songs: [] })
    if (!st.id) return send(res, 403, { code: 301, error: 'login_required', ids: [], songs: [] })
    const lim0 = parseInt(u.searchParams.get('limit') || '200', 10)
    const limit = Number.isFinite(lim0) && lim0 > 0 ? Math.min(lim0, 500) : 200
    const ids = (await likedIds(st.id)).slice(0, limit)
    const songs = []
    // song_detail 一次最多几十个 id，分批拉，保持与 likedIds 相同顺序（最近红心在前）
    for (let i = 0; i < ids.length; i += 50) {
      const chunk = ids.slice(i, i + 50)
      try {
        const body = await call('song_detail', { ids: chunk.join(',') })
        const byId = new Map((body?.songs || []).map((s) => [String(s.id), s]))
        for (const id of chunk) {
          const n = normSong(byId.get(String(id)))
          if (n) songs.push(n)
        }
      } catch (e) {
        console.error('[ERR] likelist/songs chunk', i, e.message)
      }
    }
    send(res, 200, { code: 200, ids, songs, total: songs.length })
  },

  // 私人 FM：网易云 /api/v1/radio/get，每次返回一小批
  async '/api/fm'(req, res, u) {
    const st = await uidState()
    if (st.err) return send(res, 502, { error: 'upstream_error', msg: st.err, songs: [] })
    if (!st.id) return send(res, 403, { code: 301, error: 'login_required', songs: [] })
    try {
      // 上游一次只回 3 首，还会重复；连要 4 批并去重，凑够一屏
      const seen = new Set()
      const songs = []
      for (let i = 0; i < 4 && songs.length < 12; i++) {
        const body = await call('personal_fm', {})
        const raw = body?.data || body?.result || []
        if (!raw.length) break
        for (const s of raw) {
          const n = normSong(s)
          if (!n || seen.has(String(n.id))) continue
          seen.add(String(n.id))
          songs.push(n)
        }
      }
      return send(res, 200, { code: 200, songs, total: songs.length })
    } catch (e) {
      return send(res, 502, { error: 'upstream_error', msg: e.message, songs: [] })
    }
  },

  // 心动模式：以一首歌为种子取相似歌曲，串成不会重复的连续播放流
  async '/api/similar'(req, res, u) {
    const seed = u.searchParams.get('id')
    if (!seed) return send(res, 400, { error: 'id required', songs: [] })
    const lim0 = parseInt(u.searchParams.get('limit') || '30', 10)
    const limit = Number.isFinite(lim0) && lim0 > 0 ? Math.min(lim0, 100) : 30
    const ex = (u.searchParams.get('exclude') || '').split(',').filter(Boolean)
    try {
      // 上游「相似歌曲」一次只给 5 首左右，不够一屏。
      // 用滚雪球：拿上一批的最后一首当新种子继续要，最多 4 轮。
      const seen = new Set(ex)
      seen.add(String(seed))
      const songs = []
      let cur = String(seed)
      for (let round = 0; round < 4 && songs.length < limit; round++) {
        const body = await call('simi_song', { id: cur, limit: 20 })
        const raw = body?.songs || []
        if (!raw.length) break
        for (const s of raw) {
          const n = normSong(s)
          if (!n || seen.has(String(n.id))) continue
          seen.add(String(n.id))
          songs.push(n)
          if (songs.length >= limit) break
        }
        cur = String(raw[raw.length - 1].id)
      }
      return send(res, 200, { code: 200, seed: Number(seed), songs, total: songs.length })
    } catch (e) {
      return send(res, 502, { error: 'upstream_error', msg: e.message, songs: [] })
    }
  },

  // 红心 / 取消红心
  async '/api/like'(req, res, u) {
    const id = u.searchParams.get('id')
    if (!id) return send(res, 400, { error: 'id required' })
    const raw = String(u.searchParams.get('like') || '1').toLowerCase()
    const like = !(raw === '0' || raw === 'false')
    const st = await uidState()
    if (st.err) return send(res, 502, { error: 'upstream_error', msg: st.err })
    if (!st.id) return send(res, 403, { code: 301, error: 'login_required' })
    // 必须是字符串 'true'/'false'：上游模块内部用 query.like == 'false' 判断，
    // 传布尔 false 会被当成「点赞」（false == 'false' 为 false → like=true）。
    const body = await call('like', { id: String(id), like: like ? 'true' : 'false' })
    if (body?.code === 200) {
      // 红心状态变了，本地 id 缓存立即失效，避免车机看到旧状态
      likedCache.delete(String(st.id))   // 原来写成未定义的 uid，一调红心就 500
      console.log('[like]', id, like ? 'liked' : 'unliked')
    }
    send(res, 200, { code: body?.code, id: Number(id), like })
  },

  // 我的歌单（含「我喜欢的音乐」那个默认歌单）
  async '/api/user/playlists'(req, res, u) {
    const st = await uidState()
    if (st.err) return send(res, 502, { error: 'upstream_error', msg: st.err, playlists: [] })
    if (!st.id) return send(res, 403, { code: 301, error: 'login_required', playlists: [] })
    const limit = Math.min(parseInt(u.searchParams.get('limit') || '100', 10), 500)
    const body = await call('user_playlist', { uid: String(st.id), limit, offset: 0 })
    const list = body?.playlist || []
    send(res, 200, {
      code: body?.code,
      userId: st.id,
      playlists: list.map((p) => ({
        id: p.id, name: p.name, cover: p.coverImgUrl, trackCount: p.trackCount,
        creator: p.creator?.nickname, subscribed: !!p.subscribed,
      })),
    })
  },

  async '/api/cache/status'(req, res) {
    const files = cache.listCache()
    send(res, 200, {
      audio: {
        dir: cache.CACHE_DIR,
        maxBytes: cache.MAX_BYTES,
        usedBytes: files.reduce((s, f) => s + f.size, 0),
        fileCount: files.length,
        inflight: activeDownloads,
      },
      meta: metaCache.stats(),
    })
  },

  async '/api/cache/clear'(req, res) {
    const files = cache.listCache()
    let freed = 0
    for (const f of files) {
      try { fs.unlinkSync(f.path); freed += f.size } catch {}
    }
    cache.invalidateTotal()
    send(res, 200, { cleared: files.length, freed })
  },

  /* ---------- 登录（扫码，凭据不经过聊天） ---------- */

  async '/api/login/qr'(req, res) {
    try {
      const keyBody = await call('login_qr_key', {})
      const unikey = keyBody?.data?.unikey
      if (!unikey) return send(res, 500, { error: 'no qr key', body: keyBody })
      const createBody = await call('login_qr_create', { qkey: unikey })
      // 官方返回的 qrurl 有时是 undefined，本地自拼一个（网易云 App 认 codekey 参数）
      const qrurl = 'https://music.163.com/login?codekey=' + unikey
      // qrcode 是可选依赖：容器里没装就不渲染图，只回 qrurl 由调用方自己出图。
      // 别在这里 require 失败整个接口 500 —— 登录是低频操作，不该被一个绘图库拖死。
      let dataUrl = null
      try {
        const QRCode = require('qrcode')
        dataUrl = await QRCode.toDataURL(qrurl, { margin: 1, width: 320 })
      } catch (e) {
        console.warn('[qr] qrcode 模块不可用，仅返回 qrurl:', e.message.split('\n')[0])
      }
      send(res, 200, { unikey, qrurl, qr: dataUrl })
    } catch (e) {
      send(res, 500, { error: e.message })
    }
  },

  async '/api/login/check'(req, res, u) {
    const key = u.searchParams.get('key')
    if (!key) return send(res, 400, { error: 'key required' })
    try {
      const body = await call('login_qr_check', { key })
      // 800 过期 / 801 等待扫码 / 802 已扫待确认 / 803 成功
      const code = body?.code
      let saved = false
      if (code === 803) {
        // 把 cookie 落盘（只有服务端保存，车机 APK 永远拿不到账号凭据）
        const cookie = body.cookie || ''
        if (cookie) {
          // 新克隆环境还没有 secrets 目录（README 让用户直接跑登录），先建出来
          try { fs.mkdirSync(require('path').dirname(COOKIE_FILE), { recursive: true }) } catch { }
          fs.writeFileSync(COOKIE_FILE, cookie, { mode: 0o600 })
          _uid = { id: 0, at: 0 }
          saved = true
        }
      }
      send(res, 200, {
        code,
        message: body?.message || '',
        nickname: body?.nickname || '',
        saved,
      })
    } catch (e) {
      // 必须打日志：803 时写 cookie 失败会让整次扫码作废，
      // 而接口只回 500 —— 不记日志就只能看到「登录莫名失败」。
      console.error('[ERR] login/check', e.message)
      send(res, 500, { error: e.message })
    }
  },

  // 上游故障（超时/网络错误）≠ 未登录。
  // 以前一律回 403 login_required，车机会引导用户重新扫码，
  // 而真实登录态其实还在 —— 白白折腾一趟。
  async '/api/login/status'(req, res) {
    try {
      const body = await call('login_status', {})
      const p = body?.data?.profile
      send(res, 200, {
        loggedIn: !!(body?.data?.account),
        nickname: p?.nickname || '',
        vipType: p?.vipType || 0,
        userId: body?.data?.account?.id || 0,
      })
    } catch (e) {
      send(res, 200, { loggedIn: false, error: e.message })
    }
  },
}

/* ---------------- 音频流（核心路径） ---------------- */

async function handleStream(req, res, u) {
  const songId = u.searchParams.get('id')
  if (!songId) return send(res, 400, { error: 'id required' })
  const nq = normQuality(u.searchParams.get('quality'))
  if (!nq) return send(res, 400, { error: 'bad quality', quality: u.searchParams.get('quality') })
  const quality = nq.canonical
  // 客户端传的 ext 只用来先猜缓存键；真正落盘扩展名以 resolve 出来的 type 为准
  const guessExt = (u.searchParams.get('ext') || '').replace(/[^0-9A-Za-z]/g, '') || 'mp3'

  // 1) 缓存命中 → 直接发盘（零上游流量）
  const hit = cache.lookup(songId, quality, guessExt)
  if (hit) {
    serveFile(req, res, hit.path, hit.size)
    return
  }

  // 1.5) 同一首歌正在下载中 → 等它下完再发。
  //      否则两个并发请求各拉一遍上游（双倍流量），
  //      而且两次 noteWritten 会让缓存字节数虚高一份，evict() 拿这个值判断会提前淘汰别人的歌。
  //
  //      登记必须发生在 resolveUrl（打上游，最慢的一步）之前：
  //      否则两个并发请求都在对方登记之前就通过了检查，等于没去重。
  const inflightKey = `${songId}.${quality}`
  let inflightEntry = streamInflight.get(inflightKey)
  if (inflightEntry) {
    let p = null
    try {
      p = await Promise.race([
        inflightEntry.promise,
        new Promise((r) => setTimeout(() => r(null), 180000)),   // 上限 3 分钟
      ])
    } catch { p = null }
    if (p && p.path) {
      serveFile(req, res, p.path, p.size)
      return
    }
    // 等待的那个失败了 → 自己再走一遍下载
    inflightEntry = null
  }

  // 我是第一个：占位登记，后来的请求会 await 这个 promise
  let resolveDone
  const done = new Promise((r) => { resolveDone = r })
  inflightEntry = { promise: done }
  streamInflight.set(inflightKey, inflightEntry)
  const releaseInflight = (info) => {
    const cur = streamInflight.get(inflightKey)
    if (cur && cur.promise === done) streamInflight.delete(inflightKey)
    resolveDone(info)
  }

  // 2) 未命中 → 取上游 URL，边下边发 + 落盘
  let resolved
  try {
    resolved = await resolveUrl(songId, quality)
  } catch (e) {
    releaseInflight(null)          // 失败也要释放，否则后来的请求白等
    return send(res, 502, { error: 'resolve failed', msg: e.message })
  }
  if (!resolved.ok) {
    releaseInflight(null)
    return send(res, 404, { error: 'no_url', reason: resolved.reason, fee: resolved.fee })
  }

  // 以上游返回的 type 为准：客户端 ext 只能决定「先查哪个缓存键」，
  // 不能决定落盘扩展名，否则同一 URL 的 Content-Type 会随缓存命中与否漂移
  const ext = /flac/i.test(resolved.type || '') ? 'flac'
    : /mp4|m4a|aac/i.test(resolved.type || '') ? 'm4a'
    : 'mp3'
  const finalPath = cache.keyToFile(songId, quality, ext)
  // 客户端猜的 ext 可能和上游真实 type 不一致（例如非 VIP 请求 lossless 时上游回 MP3）：
  // 那样 lookup 用的键和落盘的键永远对不上，每次播放都重新全量下载。
  // 拿到真实 ext 后再查一次。
  if (ext !== guessExt) {
    const hit2 = cache.lookup(songId, quality, ext)
    if (hit2) {
      releaseInflight(null)
      serveFile(req, res, hit2.path, hit2.size)
      return
    }
  }
  // .part 必须「隐藏 + 每请求唯一」：
  //  - cache.listCache() 只跳过 '.' 开头的文件，旧命名 xxx.mp3.part 会被算进缓存占用；
  //  - 同一首歌并发两次请求（拖动进度条/播放器重试）共用一个 .part 会互相踩，
  //    落盘后就是损坏的音频，往后每次都命中坏缓存。
  const partPath = path.join(cache.CACHE_DIR,
    `.part.${path.basename(finalPath)}.${process.pid}.${++partSeq}`)

  const agent = resolved.url.startsWith('https:') ? require('https') : require('http')
  // Range 透传：上游 CDN 支持 Range，但这里以前一律回 200 全量，
  // 车机首播拖动进度条就会把整首歌拉下来（十几 MB）并抢占带宽。
  // 只有「上游回 206（部分内容）」时才不写缓存 —— 半截文件不能当完整缓存。
  const clientRange = req.headers.range || ''
  let upstream
  try {
  upstream = await new Promise((resolve, reject) => {
    const rq = agent.get(resolved.url, {
      headers: {
        'User-Agent': 'Mozilla/5.0',
        'Referer': 'https://music.163.com/',
        ...(clientRange ? { Range: clientRange } : {}),
      },
    }, (r) => {
      if (r.statusCode >= 300 && r.statusCode < 400 && r.headers.location) {
        r.resume()
        resolve({ redirect: r.headers.location })
        return
      }
      resolve(r)
    })
    rq.on('error', reject)
    rq.setTimeout(60000, () => rq.destroy(new Error('upstream timeout')))
  })
  } catch (e) {
    releaseInflight(null)
    return send(res, 502, { error: 'upstream failed', msg: e.message })
  }

  // 跟随重定向。
  // 这里必须自己 try/catch + timeout：reject 会冒到最外层，
  // 那样 releaseInflight 永远不执行，这首歌后续每个请求都要白等 3 分钟。
  if (upstream.redirect) {
    let r2
    try {
      r2 = await new Promise((resolve, reject) => {
        const rq = agent.get(upstream.redirect, {
          headers: {
            'User-Agent': 'Mozilla/5.0',
            'Referer': 'https://music.163.com/',
            ...(clientRange ? { Range: clientRange } : {}),
          },
        }, (r) => resolve(r))
        rq.on('error', reject)
        rq.setTimeout(60000, () => rq.destroy(new Error('upstream redirect timeout')))
      })
    } catch (e) {
      releaseInflight(null)
      return send(res, 502, { error: 'redirect failed', msg: e.message })
    }
    return feedUpstream(req, res, r2, partPath, finalPath, resolved, releaseInflight)
  }
  return feedUpstream(req, res, upstream, partPath, finalPath, resolved, releaseInflight)
  activeDownloads++
  try {
    pipeToClient(req, res, upstream, partPath, finalPath, resolved, 200, releaseInflight)
  } finally {
    // pipeToClient 是同步挂载事件监听的，真正的下载结束在 finish() 里；
    // 这里只负责在连接释放时递减，避免异常路径漏减。
    res.on('close', () => { activeDownloads = Math.max(0, activeDownloads - 1) })
  }
}

/**
 * 按上游状态码分流：
 *  - 200 全量 → 边下发边落盘
 *  - 206 部分 → 只转发不落盘；但如果它其实覆盖了整个文件（Range: bytes=0-），
 *              按 200 处理并落盘，否则这类客户端永远建不起缓存
 *  - 416 → 原样透传（客户端要靠它删掉损坏的 .part 重下，映射成 502 会让这首歌永久失败）
 */
function feedUpstream(req, res, r, partPath, finalPath, resolved, onCommit) {
  if (r.statusCode === 206) {
    const cr = String(r.headers['content-range'] || '')
    const m = /bytes\s+(\d+)-(\d+)\/(\d+)/.exec(cr)
    const whole = m && Number(m[1]) === 0 && Number(m[3]) === Number(m[2]) + 1
    if (!whole) {
      onCommit(null)          // 不落盘 → 别让后来的请求等这个 promise
      return pipeToClient(req, res, r, null, null, resolved, 206)
    }
    // 整段都给了：按全量处理
    return pipeToClient(req, res, r, partPath, finalPath, resolved, 200, onCommit)
  }
  if (r.statusCode === 416) {
    onCommit(null)
    res.writeHead(416, {
      'Content-Range': r.headers['content-range'] || 'bytes */*',
      'Accept-Ranges': 'bytes',
    })
    return res.end()
  }
  if (r.statusCode !== 200) {
    onCommit(null)
    return send(res, 502, { error: 'upstream HTTP ' + r.statusCode })
  }
  return pipeToClient(req, res, r, partPath, finalPath, resolved, 200, onCommit)
}

// 正在进行的流式下载（用于并发去重）：
// key = `${songId}.${quality}`，value = { promise, resolve, reject }
const streamInflight = new Map()

let partSeq = 0
// 正在下载中的数量（/api/cache/status 里报出去；cache.inflight 是 cache.download() 用的，
// 本文件的流式路径不走它，直接读它永远是 0，会误导排查）
let activeDownloads = 0

/**
 * 边下边发 + 同时落盘。
 * partPath/finalPath 为 null 时只转发不落盘（上游回 206 的部分内容场景）。
 */
function pipeToClient(req, res, upstream, partPath, finalPath, resolved, statusCode = 200, onCommit = null) {
  const total = parseInt(upstream.headers['content-length'] || '0', 10)
  const head = {
    // 用落盘文件名的扩展名决定 Content-Type：上游对 flac 也回 audio/mpeg，
    // 照抄会让「同一个 URL 命中缓存前后的 Content-Type 不一样」
    // 按上游真实 type 算，不依赖 finalPath（206 分支 finalPath 是 null，
    // 以前会退化成 audio/mpeg，同一首歌命中前后类型不一致）
    'Content-Type': /flac/i.test(resolved.type || '') ? 'audio/flac'
      : /mp4|m4a|aac/i.test(resolved.type || '') ? 'audio/mp4' : 'audio/mpeg',
    'Accept-Ranges': 'bytes',
    'X-Cache': 'MISS',
    'X-Br': resolved.br || '',
    'Access-Control-Allow-Origin': '*',
  }
  if (statusCode === 206) {
    head['Content-Range'] = upstream.headers['content-range']
      || `bytes 0-${Math.max(0, total - 1)}/*`
  }
  // 注意：Node 20 对 undefined 的头值抛 ERR_HTTP_INVALID_HEADER_VALUE（200 会变 500）
  if (total > 0) head['Content-Length'] = total
  res.writeHead(statusCode, head)

  const out = partPath ? fs.createWriteStream(partPath) : null
  let written = 0
  let clientGone = false
  let outFailed = false

  // 写流必须自己接 error：磁盘满/无权限（ENOSPC/EACCES）时 Node 会
  // unhandled 'error' 事件 → 直接退出进程，整个音乐服务跟着挂。
  if (out) {
    out.on('error', (e) => {
      outFailed = true
      console.error('[cache] 写盘失败（磁盘满/权限？）', partPath, e && e.message)
      try { out.destroy() } catch { }
      try { fs.unlinkSync(partPath) } catch { }
      try { res.destroy() } catch { }
      if (onCommit) { try { onCommit(null) } catch { } }
    })
  }

  req.on('close', () => { clientGone = true })

  upstream.on('data', (c) => { written += c.length })
  if (out) upstream.pipe(out)
  upstream.pipe(res)

  const finish = () => {
    if (!out || outFailed) return     // 206 场景不落盘；写盘失败也别提交
    out.end(() => {
      // 上游中断/客户端提前断开时 written 会小于 Content-Length，
      // 这种半截文件绝不能当缓存提交，否则以后每次都播损坏的音频。
      const incomplete = total > 0 && written !== total
      let prevSize = 0
    try { prevSize = fs.statSync(finalPath).size } catch { }
    if (written === 0 || incomplete) {
        try { fs.unlinkSync(partPath) } catch {}
        if (incomplete) console.warn('[cache] 丢弃不完整下载', path.basename(finalPath), written + '/' + total)
        if (onCommit) { try { onCommit(null) } catch { } }
        return
      }
      try {
        fs.renameSync(partPath, finalPath)
        cache.touch(finalPath)
        // 走 cache 的记账入口，而不是拿字节数当路径去调用 evict ——
        // 旧写法 keepPath 收到的是个数字，永远不会匹配，刚写的文件可能被自己淘汰掉。
        cache.noteWritten(finalPath, written, prevSize)
        if (onCommit) { try { onCommit({ path: finalPath, size: written }) } catch { } }
      } catch (e) {
        if (onCommit) { try { onCommit(null) } catch { } }
      }
    })
  }
  upstream.on('end', finish)
  upstream.on('error', (e) => {
    console.warn('[cache] 上游中断', finalPath ? path.basename(finalPath) : '-', e && e.message)
    try { if (out) out.destroy() } catch { }
    try { if (partPath) fs.unlinkSync(partPath) } catch { }   // 运行期也要清，别等下次重启
    // 一定要释放并发去重的 promise，否则后来的同曲请求会一直等下去
    if (onCommit) { try { onCommit(null) } catch { } }
  })
  if (clientGone) {
    // 客户端断了：仍然把文件下完落盘（这样下次是缓存命中）
    try { res.destroy() } catch {}
  }
}

/** 从本地文件发盘（支持 Range） */
function serveFile(req, res, filePath, size, headers = {}) {
  const stat = fs.statSync(filePath)
  const range = req.headers.range
  const type = filePath.endsWith('.flac') ? 'audio/flac'
    : filePath.endsWith('.m4a') ? 'audio/mp4'
    : 'audio/mpeg'

  if (range) {
    const m = /bytes=(\d*)-(\d*)/.exec(range)
    let start, end
    if (m && !m[1] && m[2]) {
      // 后缀范围 bytes=-N：最后 N 字节（之前会被当成 0-N）
      const n = parseInt(m[2], 10)
      start = Math.max(0, stat.size - (Number.isFinite(n) ? n : 0))
      end = stat.size - 1
    } else {
      start = m && m[1] ? parseInt(m[1], 10) : 0
      end = m && m[2] ? parseInt(m[2], 10) : stat.size - 1
    }
    if (Number.isNaN(start)) start = 0
    if (Number.isNaN(end) || end >= stat.size) end = stat.size - 1
    if (start > end) {
      res.writeHead(416, { 'Content-Range': `bytes */${stat.size}` })
      return res.end()
    }
    res.writeHead(206, {
      'Content-Type': type,
      'Content-Length': end - start + 1,
      'Content-Range': `bytes ${start}-${end}/${stat.size}`,
      'Accept-Ranges': 'bytes',
      'X-Cache': 'HIT',
      'Access-Control-Allow-Origin': '*',
      ...headers,
    })
    return pipeFile(fs.createReadStream(filePath, { start, end }), res)
  }

  res.writeHead(200, {
    'Content-Type': type,
    'Content-Length': stat.size,
    'Accept-Ranges': 'bytes',
    'X-Cache': 'HIT',
    'Access-Control-Allow-Origin': '*',
    ...headers,
  })
  pipeFile(fs.createReadStream(filePath), res)
}

/**
 * 发文件：源流的 error 必须自己接。
 * 只给 dest 挂监听时，源流 ENOENT/EMFILE 会变成 uncaughtException →
 * Node 20 直接退出（整个音乐服务跟着没了，含扫码登录）。
 */
function pipeFile(rs, res) {
  rs.on('error', (e) => {
    console.error('[serveFile] read error:', e && e.message)
    if (!res.headersSent) {
      try { send(res, 503, { error: 'read failed' }) } catch (_) { }
    } else {
      try { res.destroy() } catch (_) { }
    }
  })
  res.on('close', () => { try { rs.destroy() } catch (_) { } })
  rs.pipe(res)
}

/* ---------------- 主循环 ---------------- */

// 兜底：任何漏网的 Promise rejection 都不该让整个进程退出（车机和网页端都靠它活着）
process.on('unhandledRejection', (e) => {
  console.error('[unhandledRejection]', e && e.stack ? e.stack : e)
})

// 未捕获异常（典型：写盘 ENOSPC）默认会让进程退出，车机和登录全断。
// 记日志继续跑 —— 单个请求坏掉好过整个服务消失。
process.on('uncaughtException', (e) => {
  console.error('[uncaughtException]', e && e.stack ? e.stack : e)
})

const server = http.createServer(async (req, res) => {
  // 不信任 Host 头：`Host: a b` 会让 new URL 抛 ERR_INVALID_URL，
  // 而这个回调是 async，异常没人接 → Node 直接退出（一个请求就能打死服务）。
  let u
  try {
    u = new URL(req.url, 'http://localhost')
  } catch (e) {
    res.writeHead(400, { 'Content-Type': 'application/json' })
    return res.end(JSON.stringify({ error: 'bad request uri' }))
  }

  if (req.method === 'OPTIONS') {
    res.writeHead(204, {
      'Access-Control-Allow-Origin': '*',
      'Access-Control-Allow-Methods': 'GET,OPTIONS',
      'Access-Control-Allow-Headers': 'Content-Type',
    })
    return res.end()
  }

  // 鉴权：token 走 header 或 query（车机是内嵌 token 的）
  if (TOKEN) {
    const supplied = req.headers['x-ncm-token'] || u.searchParams.get('token')
    if (supplied !== TOKEN) {
      return send(res, 401, { error: 'unauthorized' })
    }
  }

  try {
    if (u.pathname === '/api/stream') return await handleStream(req, res, u)
    const fn = routes[u.pathname]
    if (!fn) return notFound(res, 'unknown endpoint: ' + u.pathname)
    await fn(req, res, u)
  } catch (e) {
    console.error('[ERR]', u.pathname, e.message)
    if (!res.headersSent) send(res, 500, { error: e.message })
    else try { res.end() } catch {}
  }
})

server.listen(PORT, HOST, () => {
  console.log(`ncm-server listening on ${HOST}:${PORT}  cache=${cache.CACHE_DIR} max=${cache.MAX_BYTES}`)
})
