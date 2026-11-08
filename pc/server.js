'use strict';
// Ядро NowPlaying.
// Источников воспроизведения может быть несколько: телефон, плееры самого ПК (медиасессии Windows)
// и отдельные окна. Ядро хранит их все, показывает выбранный (или тот, что играет сейчас) и отправляет
// команды интерфейса (пауза, next/prev, seek, like) именно на тот источник, который показан.
// Без внешних зависимостей, можно запускать отдельно: node server.js
const http = require('http');
const fs = require('fs');
const os = require('os');
const dgram = require('dgram');
const path = require('path');
const { EventEmitter } = require('events');
const winps = require('./winps');

const PORT = Number(process.env.NP_PORT) || 8765;
const DISCOVERY_PORT = Number(process.env.NP_DISCOVERY_PORT) || 8766;
const DEV = process.env.NP_DEV === '1';   // тестовые адреса /dev/*, только для проверок
const bus = new EventEmitter();

const DEFAULT_CONFIG = {
  corner: 'tr',        // tl | tr | bl | br | custom
  pos: null,           // {x, y} для corner === 'custom'
  scale: 100,          // % размера карточки
  opacity: 96,         // % прозрачности
  spin: true,          // вращать пластинку
  hideWhenIdle: true,  // скрывать карточку, когда ничего не играет
  autostart: false,    // запускать вместе с Windows
  source: 'auto',      // auto | phone | pc:<id приложения> | win:<pid окна>
};
const SOURCE_RE = /^(auto|phone|pc:.{1,300}|win:\d{1,10})$/;
let config = { ...DEFAULT_CONFIG };
let configFile = null;
let userDir = null;

const emptyState = () => ({
  title: '', artist: '', album: '', app: '', playing: false,
  position: 0, duration: 0, speed: 1,
  liked: false, canLike: false, canSeek: false, controls: true,
  recvAt: 0,
});

// ---------- источники ----------
// id -> { id, kind: 'phone'|'pc'|'win', name, rawId, state, art, artVer, activity, lastTouched }
const sources = new Map();
let windows = [];                                  // [{ id: 'win:123', pid, name, title }]
let pcStatus = { available: null, error: '' };     // null = ещё не знаем
let displayedId = null;
let displayedArt = null;
let displayedKey = '';
let artVersion = 0;
let artSeq = 0;

const sseClients = new Set();
const cmdQueue = [];
let pending = null;    // открытый long-poll запрос телефона
let lastSeen = 0;
let connected = false;

const COMMANDS = new Set(['play', 'pause', 'toggle', 'next', 'prev', 'seek', 'like']);
const OPEN_TARGETS = { sound: 'ms-settings:sound', mixer: 'ms-settings:apps-volume' };
const clamp = (v, a, b) => Math.min(b, Math.max(a, v));
const str = (v, n = 300) => String(v == null ? '' : v).slice(0, n);
const num = (v) => (Number.isFinite(Number(v)) ? Number(v) : 0);

// ---------- конфиг ----------
function setConfigDir(dir) {
  userDir = dir;
  configFile = path.join(dir, 'config.json');
  try {
    config = { ...DEFAULT_CONFIG, ...sanitize(JSON.parse(fs.readFileSync(configFile, 'utf8'))) };
  } catch (_) { /* нет файла - значения по умолчанию */ }
  winps.init(dir);
}

function sanitize(p) {
  const out = {};
  if (!p || typeof p !== 'object') return out;
  if (['tl', 'tr', 'bl', 'br', 'custom'].includes(p.corner)) out.corner = p.corner;
  if (p.pos === null) out.pos = null;
  else if (p.pos && Number.isFinite(p.pos.x) && Number.isFinite(p.pos.y)) {
    out.pos = { x: Math.round(p.pos.x), y: Math.round(p.pos.y) };
  }
  if (Number.isFinite(p.scale)) out.scale = clamp(Math.round(p.scale), 60, 200);
  if (Number.isFinite(p.opacity)) out.opacity = clamp(Math.round(p.opacity), 30, 100);
  for (const k of ['spin', 'hideWhenIdle', 'autostart']) {
    if (typeof p[k] === 'boolean') out[k] = p[k];
  }
  if (typeof p.source === 'string' && SOURCE_RE.test(p.source)) out.source = p.source;
  return out;
}

function updateConfig(patch, from = 'ui') {
  const clean = sanitize(patch);
  const keys = Object.keys(clean);
  if (!keys.length) return config;
  config = { ...config, ...clean };
  if (configFile) {
    try { fs.writeFileSync(configFile, JSON.stringify(config, null, 2)); } catch (_) { /* не критично */ }
  }
  sse('config', config);
  bus.emit('config', config, keys, from);
  if (keys.includes('source')) {
    syncWindowSource();
    refresh();
  }
  return config;
}

// ---------- SSE ----------
function sse(event, data) {
  const msg = `event: ${event}\ndata: ${JSON.stringify(data)}\n\n`;
  for (const res of sseClients) res.write(msg);
}
const lanIps = () => Object.values(os.networkInterfaces()).flat()
  .filter((i) => i && i.family === 'IPv4' && !i.internal).map((i) => i.address);
const infoPayload = () => ({ connected, ips: lanIps(), port: PORT });

// ---------- выбор показываемого источника ----------
function friendlyApp(id) {
  let n = String(id || '');
  if (n.includes('!')) n = n.split('!').pop();
  n = n.replace(/\.exe$/i, '');
  if (n.includes('.')) n = n.split('.').pop();
  n = n.replace(/_.*$/, '');
  const known = { msedge: 'Edge', chrome: 'Chrome', firefox: 'Firefox', yandex: 'Яндекс Браузер', brave: 'Brave', opera: 'Opera' };
  const k = n.toLowerCase();
  if (known[k]) return known[k];
  return n ? n.charAt(0).toUpperCase() + n.slice(1) : 'Приложение';
}

function putSource(id, kind, name, rawId, st, art) {
  let s = sources.get(id);
  const wasPlaying = s ? s.state.playing : false;
  const prevTitle = s ? s.state.title : '';
  if (!s) {
    s = { id, kind, name, rawId, state: st, art: null, artVer: 0, activity: 0, lastTouched: Date.now() };
    sources.set(id, s);
  }
  s.name = name;
  s.rawId = rawId;
  s.state = st;
  if (art !== undefined && art !== s.art) {   // undefined = оставить прежнюю обложку
    s.art = art;
    s.artVer = ++artSeq;
  }
  if (st.playing) {
    s.lastTouched = Date.now();
    if (!wasPlaying || st.title !== prevTitle) s.activity = Date.now();
  }
  return s;
}

function resolveCurrent() {
  const sel = config.source;
  if (sel !== 'auto') {
    const s = sources.get(sel);
    return s && s.state.title ? s : null;
  }
  let best = null;
  for (const s of sources.values()) {
    if (!s.state.title || s.kind === 'win') continue;
    if (!best) { best = s; continue; }
    if (s.state.playing !== best.state.playing) {
      if (s.state.playing) best = s;
      continue;
    }
    const a = s.state.playing ? s.activity : s.lastTouched;
    const b = best.state.playing ? best.activity : best.lastTouched;
    if (a > b) best = s;
  }
  return best;
}

const labelFor = (s) => {
  if (s.kind === 'phone') return s.state.app ? `Телефон · ${s.state.app}` : 'Телефон';
  if (s.kind === 'pc') return `ПК · ${s.name}`;
  return `Окно · ${s.name}`;
};

function idleTexts() {
  const sel = config.source;
  const ips = lanIps().join(', ') || '—';
  if (sel === 'auto') {
    return { idleTitle: 'Ничего не играет', idleSub: connected ? 'Телефон подключён' : `IP для телефона: ${ips}` };
  }
  if (sel === 'phone') {
    return connected
      ? { idleTitle: 'Ничего не играет', idleSub: 'Источник: телефон' }
      : { idleTitle: 'Телефон не подключён', idleSub: `IP для телефона: ${ips}` };
  }
  const s = sources.get(sel);
  if (s) return { idleTitle: 'Ничего не играет', idleSub: `Источник: ${s.name}` };
  return { idleTitle: 'Источник недоступен', idleSub: 'Выберите другой в настройках' };
}

function publicState() {
  const cur = displayedId ? sources.get(displayedId) : null;
  if (!cur) {
    return {
      ...emptyState(), ...idleTexts(),
      sourceId: config.source, sourceKind: '', label: '', age: 0, artVer: 0,
    };
  }
  const st = cur.state;
  return {
    ...st,
    sourceId: cur.id, sourceKind: cur.kind, label: labelFor(cur),
    age: st.recvAt ? Date.now() - st.recvAt : 0,
    artVer: displayedArt ? artVersion : 0,
  };
}

function sourcesPayload() {
  const phone = sources.get('phone');
  const list = [{
    id: 'phone', kind: 'phone', name: 'Телефон', available: connected,
    playing: !!(phone && phone.state.playing),
    detail: !connected ? 'не подключён' : (phone && phone.state.title
      ? `${phone.state.title}${phone.state.artist ? ' • ' + phone.state.artist : ''}`
      : 'подключён, ничего не играет'),
  }];
  for (const s of sources.values()) {
    if (s.kind !== 'pc') continue;
    list.push({
      id: s.id, kind: 'pc', name: s.name, available: true, playing: s.state.playing,
      detail: s.state.title ? `${s.state.title}${s.state.artist ? ' • ' + s.state.artist : ''}` : 'ничего не играет',
    });
  }
  return {
    selected: config.source,
    current: displayedId,
    list,
    windows: windows.map((w) => ({ id: w.id, name: w.name, title: w.title })),
    pc: pcStatus,
    ips: lanIps(),
  };
}

/** Пересчитывает, какой источник показывать, и рассылает состояние всем интерфейсам. */
function refresh() {
  const cur = resolveCurrent();
  displayedId = cur ? cur.id : null;
  const key = cur ? `${cur.id}:${cur.artVer}` : '';
  if (key !== displayedKey) {
    displayedKey = key;
    displayedArt = cur ? cur.art : null;
    artVersion += 1;
  }
  const st = publicState();
  sse('state', st);
  sse('sources', sourcesPayload());
  bus.emit('state', st);
}

// ---------- телефон ----------
function applyNow(d) {
  const title = str(d.title);
  const phone = sources.get('phone');
  if (!title.trim()) {
    putSource('phone', 'phone', 'Телефон', 'phone', { ...emptyState(), recvAt: Date.now() }, null);
  } else {
    let art;
    if (typeof d.art === 'string' && d.art) art = Buffer.from(d.art, 'base64');
    else if (!d.artSame) art = null;
    putSource('phone', 'phone', 'Телефон', 'phone', {
      title, artist: str(d.artist), album: str(d.album), app: str(d.app, 60),
      playing: !!d.playing,
      position: Math.max(0, num(d.position)),
      duration: Math.max(0, num(d.duration)),
      speed: num(d.speed) || 1,
      liked: !!d.liked, canLike: !!d.canLike, canSeek: !!d.canSeek, controls: true,
      recvAt: Date.now(),
    }, art);
  }
  refresh();
  const cur = sources.get('phone');
  return { needArt: !!d.artSame && !(cur && cur.art) && !!(phone || cur) };
}

function touch() {
  lastSeen = Date.now();
  checkConnection();
}

function checkConnection() {
  const c = !!pending || Date.now() - lastSeen < 40000;
  if (c !== connected) {
    connected = c;
    sse('info', infoPayload());
    if (!c) sources.delete('phone');
    refresh();
  }
}

function finishPoll(p, cmds) {
  clearTimeout(p.timer);
  if (pending === p) pending = null;
  touch();
  if (!p.res.writableEnded) sendJson(p.res, 200, { cmds });
}

function pushCommand(cmd) {
  cmdQueue.push(cmd);
  if (cmdQueue.length > 20) cmdQueue.shift();
  if (pending) finishPoll(pending, cmdQueue.splice(0));
}

function handlePoll(req, res) {
  if (pending) finishPoll(pending, []);
  if (cmdQueue.length) {
    touch();
    return sendJson(res, 200, { cmds: cmdQueue.splice(0) });
  }
  const p = { res, timer: null };
  p.timer = setTimeout(() => finishPoll(p, []), 25000);
  req.on('close', () => {
    if (pending === p) {
      clearTimeout(p.timer);
      pending = null;
      lastSeen = Date.now();
      checkConnection();
    }
  });
  pending = p;
  touch();
  return undefined;
}

// ---------- плееры и окна ПК (данные приходят из pcmedia.js) ----------
function setPcSessions(list) {
  const seen = new Set();
  for (const p of Array.isArray(list) ? list : []) {
    if (!p || !p.id) continue;
    const rawId = str(p.id, 300);
    const id = `pc:${rawId}`;
    seen.add(id);
    const name = friendlyApp(rawId);
    const duration = Math.max(0, num(p.duration));
    let art;
    if (typeof p.art === 'string' && p.art) art = Buffer.from(p.art, 'base64');
    else if (!p.artSame) art = null;
    putSource(id, 'pc', name, rawId, {
      title: str(p.title), artist: str(p.artist), album: str(p.album), app: name,
      playing: !!p.playing,
      position: Math.max(0, num(p.position)), duration, speed: 1,
      liked: false, canLike: false, canSeek: !!p.canSeek && duration > 0, controls: true,
      recvAt: Date.now(),
    }, art);
  }
  for (const [id, s] of sources) {
    if (s.kind === 'pc' && !seen.has(id)) sources.delete(id);
  }
  pcStatus = { available: true, error: '' };
  refresh();
}

function syncWindowSource() {
  const sel = config.source;
  for (const [id, s] of sources) {
    if (s.kind === 'win' && id !== sel) sources.delete(id);
  }
  if (!sel.startsWith('win:')) return;
  const w = windows.find((x) => x.id === sel);
  if (!w) { sources.delete(sel); return; }
  putSource(sel, 'win', w.name, w.pid, {
    ...emptyState(), title: w.title, artist: w.name, app: 'Окно', playing: true,
    controls: false, recvAt: Date.now(),
  }, null);
}

function setWindows(list) {
  windows = (Array.isArray(list) ? list : [])
    .filter((w) => w && Number.isFinite(Number(w.pid)) && w.title)
    .map((w) => ({ id: `win:${Number(w.pid)}`, pid: Number(w.pid), name: str(w.name, 60), title: str(w.title, 200) }))
    .filter((w) => !/^(NowPlaying|electron)$/i.test(w.name));
  syncWindowSource();
  refresh();
}

function setPcStatus(st) {
  pcStatus = { available: !!st.available, error: str(st.error, 300) };
  sse('sources', sourcesPayload());
}

/** Команда с карточки уходит на тот источник, который сейчас показан. */
function routeCommand(body) {
  const cur = displayedId ? sources.get(displayedId) : null;
  if (!cur) return;
  const pos = Math.max(0, Math.round(num(body.pos)));
  if (cur.kind === 'phone') pushCommand({ cmd: body.cmd, pos });
  else if (cur.kind === 'pc' && body.cmd !== 'like') bus.emit('pc-cmd', { cmd: body.cmd, id: cur.rawId, pos });
}

// ---------- HTTP ----------
function sendJson(res, code, obj) {
  const body = JSON.stringify(obj);
  res.writeHead(code, { 'Content-Type': 'application/json; charset=utf-8', 'Content-Length': Buffer.byteLength(body) });
  res.end(body);
}
const sendText = (res, code, text) => { res.writeHead(code, { 'Content-Type': 'text/plain; charset=utf-8' }); res.end(text); };

function sendPage(res, name) {
  const html = fs.readFileSync(path.join(__dirname, 'ui', name));
  res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8', 'Cache-Control': 'no-store' });
  res.end(html);
}

function readBody(req, limit = 2 * 1024 * 1024) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let size = 0;
    req.on('data', (c) => {
      size += c.length;
      if (size > limit) { reject(new Error('too large')); req.destroy(); return; }
      chunks.push(c);
    });
    req.on('end', () => resolve(Buffer.concat(chunks).toString('utf8')));
    req.on('error', reject);
  });
}

// Интерфейс доступен только с этого же компьютера. Изменяющие запросы дополнительно
// требуют заголовок X-NP: страница в браузере не сможет его отправить без CORS-разрешения.
function isLocal(req) {
  const a = req.socket.remoteAddress;
  const localAddr = a === '127.0.0.1' || a === '::1' || a === '::ffff:127.0.0.1';
  const host = String(req.headers.host || '');
  return localAddr && [`127.0.0.1:${PORT}`, `localhost:${PORT}`, `[::1]:${PORT}`].includes(host);
}

const devCmds = [];

const server = http.createServer(async (req, res) => {
  try {
    const url = new URL(req.url, 'http://x');
    const p = url.pathname;

    // доступно телефону по локальной сети
    if (req.method === 'POST' && p === '/now') {
      const d = JSON.parse((await readBody(req)) || '{}');
      touch();
      const r = applyNow(d);
      return sendJson(res, 200, { ok: true, needArt: r.needArt });
    }
    if (req.method === 'GET' && p === '/poll') return handlePoll(req, res);

    // дальше только локально
    if (!isLocal(req)) return sendText(res, 403, 'forbidden');

    if (req.method === 'GET' && p === '/') return sendPage(res, 'index.html');
    if (req.method === 'GET' && p === '/settings') return sendPage(res, 'settings.html');
    if (p === '/favicon.ico') { res.writeHead(204); return res.end(); }
    if (req.method === 'GET' && p === '/art') {
      const art = displayedArt;
      if (!art) return sendText(res, 404, 'no art');
      res.writeHead(200, { 'Content-Type': art[0] === 0xff ? 'image/jpeg' : 'image/png', 'Cache-Control': 'max-age=3600' });
      return res.end(art);
    }
    if (req.method === 'GET' && p === '/events') {
      res.writeHead(200, { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache', Connection: 'keep-alive' });
      res.write('retry: 1000\n\n');
      sseClients.add(res);
      req.on('close', () => sseClients.delete(res));
      res.write(`event: config\ndata: ${JSON.stringify(config)}\n\n`);
      res.write(`event: info\ndata: ${JSON.stringify(infoPayload())}\n\n`);
      res.write(`event: sources\ndata: ${JSON.stringify(sourcesPayload())}\n\n`);
      res.write(`event: state\ndata: ${JSON.stringify(publicState())}\n\n`);
      return undefined;
    }
    if (req.method === 'GET' && p === '/audio') {
      return sendJson(res, 200, await winps.runJson('audio.ps1', ['-Action', 'list']));
    }

    if (DEV && p.startsWith('/dev/')) {
      if (req.method === 'GET' && p === '/dev/cmds') return sendJson(res, 200, devCmds);
      const body = JSON.parse((await readBody(req)) || '{}');
      if (p === '/dev/pc') setPcSessions(body.sessions);
      else if (p === '/dev/win') setWindows(body.windows);
      else return sendText(res, 404, 'not found');
      return sendJson(res, 200, { ok: true });
    }

    if (req.method === 'POST' && ['/cmd', '/config', '/quit', '/open-settings', '/open', '/audio/set'].includes(p)) {
      if (req.headers['x-np'] !== '1') return sendText(res, 403, 'forbidden');
      const body = JSON.parse((await readBody(req, 10000)) || '{}');
      if (p === '/cmd') {
        if (!COMMANDS.has(body.cmd)) return sendText(res, 400, 'bad command');
        routeCommand(body);
      } else if (p === '/config') {
        updateConfig(body, 'ui');
      } else if (p === '/open-settings') {
        const handled = bus.listenerCount('open-settings') > 0;
        if (handled) bus.emit('open-settings');
        return sendJson(res, 200, { ok: true, handled });
      } else if (p === '/open') {
        const url2 = OPEN_TARGETS[body.target];
        if (!url2) return sendText(res, 400, 'bad target');
        const handled = bus.listenerCount('open-external') > 0;
        if (handled) bus.emit('open-external', url2);
        return sendJson(res, 200, { ok: true, handled });
      } else if (p === '/audio/set') {
        const m = /^\{0\.0\.0\.00000000\}\.\{([0-9a-fA-F-]{36})\}$/.exec(String(body.id || ''));
        if (!m) return sendText(res, 400, 'bad device');
        return sendJson(res, 200, await winps.runJson('audio.ps1', ['-Action', 'set', '-Id', m[1]]));
      } else {
        sendJson(res, 200, { ok: true });
        bus.emit('quit');
        return undefined;
      }
      return sendJson(res, 200, { ok: true });
    }
    return sendText(res, 404, 'not found');
  } catch (e) {
    if (!res.headersSent) sendText(res, 400, 'bad request');
  }
  return undefined;
});

// Телефон рассылает по Wi-Fi «NOWPLAYING_DISCOVER», мы отвечаем: так он находит ПК без ввода IP.
let udp = null;
function startDiscovery() {
  try {
    udp = dgram.createSocket({ type: 'udp4', reuseAddr: true });
    udp.on('message', (msg, rinfo) => {
      if (String(msg).trim() === 'NOWPLAYING_DISCOVER') {
        udp.send(Buffer.from(`NOWPLAYING_HERE:${PORT}`), rinfo.port, rinfo.address);
      }
    });
    udp.on('error', () => { try { udp.close(); } catch (_) { /* уже закрыт */ } udp = null; });
    udp.bind(DISCOVERY_PORT, '0.0.0.0');
  } catch (_) { udp = null; }
}

let timers = [];
function start() {
  return new Promise((resolve, reject) => {
    if (DEV) bus.on('pc-cmd', (c) => devCmds.push(c));
    server.once('error', reject);
    server.listen(PORT, '0.0.0.0', () => {
      server.off('error', reject);
      timers = [
        setInterval(checkConnection, 3000),
        setInterval(() => sse('ping', {}), 15000),
      ];
      startDiscovery();
      resolve();
    });
  });
}
function stop() {
  timers.forEach(clearInterval);
  for (const r of sseClients) r.end();
  if (udp) { try { udp.close(); } catch (_) { /* уже закрыт */ } udp = null; }
  server.close();
}

module.exports = {
  PORT, bus, start, stop, setConfigDir, updateConfig,
  setPcSessions, setWindows, setPcStatus,
  getConfig: () => config,
  getState: () => publicState(),
  lanIps,
};

if (require.main === module) {
  const dir = process.env.NP_CONFIG_DIR || path.join(os.tmpdir(), 'nowplaying');
  try { fs.mkdirSync(dir, { recursive: true }); } catch (_) { /* уже есть */ }
  setConfigDir(dir);
  bus.on('quit', () => process.exit(0));
  start()
    .then(() => {
      console.log(`NowPlaying: http://127.0.0.1:${PORT}  (телефон: ${lanIps().join(', ')}:${PORT})`);
      if (!DEV) require('./pcmedia').attach(module.exports, dir);
    })
    .catch((e) => { console.error(e.message); process.exit(1); });
}
