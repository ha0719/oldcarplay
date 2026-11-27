/*
 * 洛雪自定义源运行环境（车机版自实现，接口按洛雪官方文档对齐）
 * https://lyswhut.github.io/lx-music-doc/mobile/custom-source
 *
 * 宿主（Java）注入的原生函数：
 *   __n_log(level, msg)                 写日志
 *   __n_timer(id, ms)                   定时器
 *   __n_event(name, json)               inited / updateAlert / response 事件
 *   __n_request(id, url, method, headersJson, body, hasBody, timeout, binary)
 *   __n_cancel(id)
 *   __n_md5(b64) -> hex
 *   __n_aes(b64, mode, keyB64, ivB64) -> b64
 *   __n_rsa(b64, pem) -> b64
 *   __n_random(size) -> b64
 *   __n_inflate(b64) -> b64 | null
 *   __n_deflate(b64) -> b64 | null
 * 宿主调用的 JS 函数：
 *   __lx_setup(infoJson) / __lx_fire_timer(id) / __lx_on_response(...) / __lx_handle_request(...)
 */
'use strict';
(function () {
  const g = globalThis

  // ------------------------------------------------------------ console
  const fmt = args => args.map(a => {
    if (typeof a === 'string') return a
    if (a instanceof Error) return a.message + (a.stack ? '\n' + a.stack : '')
    try { return JSON.stringify(a) } catch (e) { return String(a) }
  }).join(' ')
  const log = (lv, args) => { try { __n_log(lv, fmt(args)) } catch (e) {} }
  g.console = {
    log: (...a) => log('log', a),
    info: (...a) => log('info', a),
    warn: (...a) => log('warn', a),
    error: (...a) => log('error', a),
    debug: (...a) => log('debug', a),
    group: () => {}, groupEnd: () => {}, time: () => {}, timeEnd: () => {},
  }

  // ------------------------------------------------------------ timers
  let timerSeq = 0
  const timers = new Map()
  const intervals = new Set()
  g.setTimeout = (fn, ms, ...args) => {
    const id = ++timerSeq
    timers.set(id, () => { if (typeof fn === 'function') fn(...args) })
    __n_timer(id, Math.max(0, (ms | 0)))
    return id
  }
  g.setInterval = (fn, ms, ...args) => {
    const id = ++timerSeq
    const delay = Math.max(1, (ms | 0))
    intervals.add(id)
    timers.set(id, () => {
      if (typeof fn === 'function') fn(...args)
      if (timers.has(id)) __n_timer(id, delay)
    })
    __n_timer(id, delay)
    return id
  }
  g.clearTimeout = g.clearInterval = id => { timers.delete(id); intervals.delete(id) }
  g.__lx_fire_timer = id => {
    const f = timers.get(id)
    if (!f) return
    if (!intervals.has(id)) timers.delete(id)
    try { f() } catch (e) { console.error('timer error', e) }
  }

  // ------------------------------------------------------------ bytes helpers
  const B64 = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/'
  const B64MAP = (() => { const m = {}; for (let i = 0; i < 64; i++) m[B64[i]] = i; m['-'] = 62; m['_'] = 63; return m })()
  const bytesToB64 = u8 => {
    let out = ''
    let i = 0
    for (; i + 2 < u8.length; i += 3) {
      const n = (u8[i] << 16) | (u8[i + 1] << 8) | u8[i + 2]
      out += B64[(n >> 18) & 63] + B64[(n >> 12) & 63] + B64[(n >> 6) & 63] + B64[n & 63]
    }
    const rest = u8.length - i
    if (rest === 1) {
      const n = u8[i] << 16
      out += B64[(n >> 18) & 63] + B64[(n >> 12) & 63] + '=='
    } else if (rest === 2) {
      const n = (u8[i] << 16) | (u8[i + 1] << 8)
      out += B64[(n >> 18) & 63] + B64[(n >> 12) & 63] + B64[(n >> 6) & 63] + '='
    }
    return out
  }
  const b64ToBytes = s => {
    s = String(s || '').replace(/[^A-Za-z0-9+/\-_]/g, '')
    const len = Math.floor(s.length * 3 / 4)
    const out = new Uint8Array(len)
    let bits = 0; let val = 0; let p = 0
    for (let i = 0; i < s.length; i++) {
      val = (val << 6) | B64MAP[s[i]]
      bits += 6
      if (bits >= 8) { bits -= 8; if (p < len) out[p++] = (val >> bits) & 0xff }
    }
    return p === len ? out : out.subarray(0, p)
  }
  const utf8Encode = str => {
    str = String(str)
    const out = []
    for (let i = 0; i < str.length; i++) {
      let c = str.charCodeAt(i)
      if (c >= 0xd800 && c <= 0xdbff && i + 1 < str.length) {
        const c2 = str.charCodeAt(i + 1)
        if (c2 >= 0xdc00 && c2 <= 0xdfff) { c = 0x10000 + ((c - 0xd800) << 10) + (c2 - 0xdc00); i++ }
      }
      if (c < 0x80) out.push(c)
      else if (c < 0x800) out.push(0xc0 | (c >> 6), 0x80 | (c & 63))
      else if (c < 0x10000) out.push(0xe0 | (c >> 12), 0x80 | ((c >> 6) & 63), 0x80 | (c & 63))
      else out.push(0xf0 | (c >> 18), 0x80 | ((c >> 12) & 63), 0x80 | ((c >> 6) & 63), 0x80 | (c & 63))
    }
    return Uint8Array.from(out)
  }
  const utf8Decode = u8 => {
    let out = ''
    let i = 0
    while (i < u8.length) {
      const c = u8[i++]
      let cp
      if (c < 0x80) cp = c
      else if (c >= 0xf0 && i + 2 < u8.length + 0) { cp = ((c & 7) << 18) | ((u8[i++] & 63) << 12) | ((u8[i++] & 63) << 6) | (u8[i++] & 63) }
      else if (c >= 0xe0) { cp = ((c & 15) << 12) | ((u8[i++] & 63) << 6) | (u8[i++] & 63) }
      else if (c >= 0xc0) { cp = ((c & 31) << 6) | (u8[i++] & 63) }
      else cp = 0xfffd
      if (cp > 0xffff) { cp -= 0x10000; out += String.fromCharCode(0xd800 + (cp >> 10), 0xdc00 + (cp & 1023)) }
      else out += String.fromCharCode(cp)
    }
    return out
  }
  const hexToBytes = h => {
    h = String(h).replace(/[^0-9a-fA-F]/g, '')
    const out = new Uint8Array(h.length >> 1)
    for (let i = 0; i < out.length; i++) out[i] = parseInt(h.substr(i * 2, 2), 16)
    return out
  }
  const bytesToHex = u8 => { let s = ''; for (let i = 0; i < u8.length; i++) s += (u8[i] < 16 ? '0' : '') + u8[i].toString(16); return s }
  const binToBytes = s => { s = String(s); const o = new Uint8Array(s.length); for (let i = 0; i < s.length; i++) o[i] = s.charCodeAt(i) & 0xff; return o }
  const bytesToBin = u8 => { let s = ''; for (let i = 0; i < u8.length; i++) s += String.fromCharCode(u8[i]); return s }

  // ------------------------------------------------------------ Buffer（够源脚本用的最小实现）
  const bufToString = (buf, format) => {
    const u8 = buf instanceof Uint8Array ? buf : bufferFrom(buf)
    switch (String(format || 'utf8').toLowerCase()) {
      case 'hex': return bytesToHex(u8)
      case 'base64': return bytesToB64(u8)
      case 'binary': case 'latin1': return bytesToBin(u8)
      default: return utf8Decode(u8)
    }
  }
  class LxBuffer extends Uint8Array {
    toString(format) { return bufToString(this, format) }
    toJSON() { return { type: 'Buffer', data: Array.from(this) } }
  }
  const toBuf = u8 => { const b = new LxBuffer(u8.length); b.set(u8); return b }
  const bufferFrom = (data, enc) => {
    if (data instanceof LxBuffer) return data
    if (typeof data === 'string') {
      switch (String(enc || 'utf8').toLowerCase()) {
        case 'hex': return toBuf(hexToBytes(data))
        case 'base64': return toBuf(b64ToBytes(data))
        case 'binary': case 'latin1': return toBuf(binToBytes(data))
        default: return toBuf(utf8Encode(data))
      }
    }
    if (data instanceof ArrayBuffer) return toBuf(new Uint8Array(data))
    if (ArrayBuffer.isView(data)) return toBuf(new Uint8Array(data.buffer, data.byteOffset, data.byteLength))
    if (Array.isArray(data)) return toBuf(Uint8Array.from(data))
    if (data && data.type === 'Buffer' && Array.isArray(data.data)) return toBuf(Uint8Array.from(data.data))
    throw new Error('Unsupported buffer source')
  }
  const toB64 = d => bytesToB64(bufferFrom(d))

  // ------------------------------------------------------------ crypto / zlib
  const crypto = {
    aesEncrypt(buffer, mode, key, iv) {
      const r = __n_aes(toB64(buffer), String(mode), toB64(key), iv == null ? '' : toB64(iv))
      return toBuf(b64ToBytes(r))
    },
    rsaEncrypt(buffer, key) {
      const r = __n_rsa(toB64(buffer), String(key))
      return toBuf(b64ToBytes(r))
    },
    randomBytes(size) { return toBuf(b64ToBytes(__n_random(size | 0))) },
    md5(str) { return __n_md5(toB64(typeof str === 'string' ? utf8Encode(str) : str)) },
  }
  const zlib = {
    inflate(buf) {
      return new Promise((resolve, reject) => {
        const r = __n_inflate(toB64(buf))
        if (r == null) reject(new Error('inflate failed')); else resolve(toBuf(b64ToBytes(r)))
      })
    },
    deflate(buf) {
      return new Promise((resolve, reject) => {
        const r = __n_deflate(toB64(buf))
        if (r == null) reject(new Error('deflate failed')); else resolve(toBuf(b64ToBytes(r)))
      })
    },
  }

  // ------------------------------------------------------------ request
  let reqSeq = 0
  const reqs = new Map()
  const encodeForm = obj => Object.keys(obj).map(k => encodeURIComponent(k) + '=' + encodeURIComponent(obj[k] == null ? '' : obj[k])).join('&')
  const request = (url, options, callback) => {
    if (typeof options === 'function') { callback = options; options = {} }
    const { method = 'get', timeout, headers, body, form, formData, binary } = options || {}
    const id = ++reqSeq
    const hdrs = Object.assign({}, headers || {})
    const hasCT = Object.keys(hdrs).some(k => k.toLowerCase() === 'content-type')
    let data = null
    if (body != null) {
      if (typeof body === 'string') data = body
      else if (body instanceof Uint8Array) data = bytesToBin(body)
      else { data = JSON.stringify(body); if (!hasCT) hdrs['Content-Type'] = 'application/json' }
    } else if (form != null || formData != null) {
      data = encodeForm(form != null ? form : formData)
      if (!hasCT) hdrs['Content-Type'] = 'application/x-www-form-urlencoded'
    }
    reqs.set(id, typeof callback === 'function' ? callback : () => {})
    __n_request(id, String(url), String(method).toUpperCase(), JSON.stringify(hdrs), data == null ? '' : data, data != null, timeout ? (timeout | 0) : 60000, !!binary)
    return () => { if (reqs.delete(id)) __n_cancel(id) }
  }
  g.__lx_on_response = (id, err, statusCode, statusMessage, headersJson, text, b64) => {
    const cb = reqs.get(id)
    if (!cb) return
    reqs.delete(id)
    if (err) {
      try { cb(new Error(err), null, null) } catch (e) { console.error('request callback error', e) }
      return
    }
    let headers = {}
    try { headers = JSON.parse(headersJson) } catch (e) {}
    let body
    let raw
    if (b64) {
      raw = toBuf(b64ToBytes(b64))
      body = raw
    } else {
      body = text
      try { body = JSON.parse(text) } catch (e) {}
    }
    const resp = {
      statusCode,
      statusMessage,
      headers,
      get raw() { if (!raw) raw = toBuf(utf8Encode(text)); return raw },
      get bytes() { return this.raw.length },
      body,
    }
    try { cb(null, resp, body) } catch (e) { console.error('request callback error', e) }
  }

  // ------------------------------------------------------------ events
  const EVENT_NAMES = { request: 'request', inited: 'inited', updateAlert: 'updateAlert' }
  const handlers = {}
  let inited = false
  const on = (name, handler) => {
    if (name !== EVENT_NAMES.request) return Promise.reject(new Error('The event is not supported: ' + name))
    handlers[name] = handler
    return Promise.resolve()
  }
  const send = (name, data) => new Promise((resolve, reject) => {
    switch (name) {
      case EVENT_NAMES.inited:
        if (inited) return reject(new Error('Script is inited'))
        inited = true
        __n_event('inited', JSON.stringify(data || {}))
        return resolve()
      case EVENT_NAMES.updateAlert:
        __n_event('updateAlert', JSON.stringify(data || {}))
        return resolve()
      default:
        return reject(new Error('Unknown event name: ' + name))
    }
  })
  g.__lx_handle_request = (id, source, action, infoJson) => {
    const done = (ok, data, msg) => __n_event('response', JSON.stringify({ id, ok, data: data == null ? null : data, msg: msg || '' }))
    const handler = handlers[EVENT_NAMES.request]
    if (!handler) return done(false, null, 'Request event is not defined')
    let info
    try { info = JSON.parse(infoJson) } catch (e) { return done(false, null, 'Bad request info') }
    let p
    try { p = handler({ source, action, info }) } catch (e) { return done(false, null, (e && e.message) || String(e)) }
    Promise.resolve(p).then(res => {
      if (action === 'musicUrl') {
        if (typeof res !== 'string' || res.length > 4096 || !/^https?:/i.test(res)) throw new Error('音源没有返回有效链接')
        return done(true, { url: res })
      }
      done(true, res)
    }).catch(e => done(false, null, (e && e.message) || String(e)))
  }

  // ------------------------------------------------------------ globalThis.lx
  g.__lx_setup = infoJson => {
    let info = {}
    try { info = JSON.parse(infoJson) } catch (e) {}
    g.lx = {
      EVENT_NAMES,
      request,
      send,
      on,
      utils: {
        crypto,
        buffer: { from: bufferFrom, bufToString },
        zlib,
      },
      currentScriptInfo: info,
      version: '2.0.0',
      env: 'mobile',
    }
  }
})()
