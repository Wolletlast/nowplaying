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
  streamAudio: true,   // принимать звук телефона и играть его на ПК, когда источник - телефон
  volume: 70,          // громкость звука телефона на ПК, %
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

// ---------- звук телефона ----------
const audioOut = new Set();   // страницы-плееры (ui/player.html), которым пересылаем звук
let audioIn = null;           // текущий запрос от телефона
let audioLast = 0;
let lastWant = false;
const wantAudio = () => config.streamAudio && connected
  && (config.source === 'phone' || (config.source === 'auto' && displayedId === 'phone'));
const audioStatus = () => ({
  enabled: config.streamAudio, want: wantAudio(),
  active: Date.now() - audioLast < 2500, listening: audioOut.size > 0,
});

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
  if (Number.isFinite(p.volume)) out.volume = clamp(Math.round(p.volume), 0, 100);
  for (const k of ['spin', 'hideWhenIdle', 'autostart', 'streamAudio']) {
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
  if (keys.includes('source')) syncWindowSource();
  if (keys.includes('source') || keys.includes('streamAudio')) refresh();
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
  const want = wantAudio();
  if (want !== lastWant) {
    lastWant = want;
    if (connected) pushCommand({ cmd: 'audio', pos: want ? 1 : 0 });
    sse('audio', audioStatus());
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
  syncWindowSource();
  refresh();
}

const winArt = new Map();   // pid -> иконка программы (PNG), приходит один раз

const normId = (id) => String(id).split('!').pop().replace(/\.exe$/i, '').split('.').pop().replace(/_.*$/, '').toLowerCase();

/** Есть ли у программы окна своя медиасессия Windows (браузер, Spotify...): тогда берём из неё трек и управляем ей. */
function matchSession(w) {
  const n = String(w.name).toLowerCase();
  for (const s of sources.values()) {
    if (s.kind !== 'pc') continue;
    const k = normId(s.rawId);
    if (k === n || (k.length >= 4 && n.length >= 4 && (k.includes(n) || n.includes(k)))) return s;
  }
  return null;
}

function syncWindowSource() {
  const sel = config.source;
  for (const [id, s] of sources) {
    if (s.kind === 'win' && id !== sel) sources.delete(id);
  }
  if (!sel.startsWith('win:')) return;
  const w = windows.find((x) => x.id === sel);
  if (!w) { sources.delete(sel); return; }
  const m = matchSession(w);
  const prev = sources.get(sel);
  let st;
  let art;
  if (m) {
    st = { ...m.state, app: w.name, controls: true };
    art = m.art;
  } else {
    st = {
      ...emptyState(), title: w.title, artist: w.name, app: 'Окно', controls: true, recvAt: Date.now(),
      playing: prev && !prev.via ? prev.state.playing : true,   // состояние окна неизвестно: считаем, что играет
    };
    art = winArt.get(w.pid) || null;
  }
  const s = putSource(sel, 'win', w.name, w.pid, st, art);
  s.via = m ? m.rawId : null;
  s.hwnd = w.hwnd;
}

function setWindows(list) {
  windows = [];
  for (const w of Array.isArray(list) ? list : []) {
    if (!w || !Number.isFinite(Number(w.pid)) || !w.title) continue;
    const pid = Number(w.pid);
    if (typeof w.art === 'string' && w.art) winArt.set(pid, Buffer.from(w.art, 'base64'));
    windows.push({ id: `win:${pid}`, pid, name: str(w.name, 60), title: str(w.title, 200), hwnd: Number(w.hwnd) || 0 });
  }
  windows = windows.filter((w) => !/^(NowPlaying|electron)$/i.test(w.name));
  const pids = new Set(windows.map((w) => w.pid));
  for (const pid of winArt.keys()) if (!pids.has(pid)) winArt.delete(pid);
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
  const cmd = body.cmd;
  const pos = Math.max(0, Math.round(num(body.pos)));
  if (cur.kind === 'phone') {
    pushCommand({ cmd, pos });
  } else if (cur.kind === 'pc') {
    if (cmd !== 'like') bus.emit('pc-cmd', { cmd, id: cur.rawId, pos });
  } else if (cur.kind === 'win') {
    if (cmd === 'like') return;
    if (cur.via) {
      bus.emit('pc-cmd', { cmd, id: cur.via, pos });
    } else if (cur.hwnd && cmd !== 'seek') {
      // обычное окно: стандартные медиа-команды окну; ответа о состоянии нет, поэтому play/pause отмечаем сами
      bus.emit('pc-cmd', { cmd, hwnd: cur.hwnd });
      if (cmd === 'toggle') cur.state.playing = !cur.state.playing;
      else if (cmd === 'play') cur.state.playing = true;
      else if (cmd === 'pause') cur.state.playing = false;
      refresh();
    }
  }
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
      return sendJson(res, 200, { ok: true, needArt: r.needArt, audio: wantAudio() });
    }
    if (req.method === 'GET' && p === '/poll') return handlePoll(req, res);
    if (req.method === 'POST' && p === '/audio-in') {
      // поток звука от телефона: сырой PCM 48000 Гц, 16 бит, стерео
      if (!wantAudio()) { res.writeHead(409, { Connection: 'close' }); return res.end(); }
      if (audioIn && audioIn !== req) { try { audioIn.destroy(); } catch (_) { /* уже закрыт */ } }
      audioIn = req;
      touch();
      req.on('data', (c) => {
        audioLast = Date.now();
        for (const o of audioOut) if (o.writableLength < 262144) o.write(c);   // отстающего слушателя не копим
      });
      req.on('close', () => { if (audioIn === req) audioIn = null; });
      req.on('end', () => { if (!res.writableEnded) sendJson(res, 200, { ok: true }); });
      return undefined;
    }

    // дальше только локально
    if (!isLocal(req)) return sendText(res, 403, 'forbidden');

    if (req.method === 'GET' && p === '/') return sendPage(res, 'index.html');
    if (req.method === 'GET' && p === '/settings') return sendPage(res, 'settings.html');
    if (req.method === 'GET' && p === '/player') return sendPage(res, 'player.html');
    if (req.method === 'GET' && p === '/audio-out') {
      res.writeHead(200, { 'Content-Type': 'application/octet-stream', 'Cache-Control': 'no-store' });
      res.flushHeaders();
      audioOut.add(res);
      sse('audio', audioStatus());
      req.on('close', () => { audioOut.delete(res); });
      return undefined;
    }
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
      res.write(`event: audio\ndata: ${JSON.stringify(audioStatus())}\n\n`);
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

server.requestTimeout = 0;   // поток звука от телефона идёт одним долгим запросом
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
        setInterval(() => sse('audio', audioStatus()), 2000),
      ];
      startDiscovery();
      resolve();
    });
  });
}
function stop() {
  timers.forEach(clearInterval);
  for (const r of sseClients) r.end();
  for (const r of audioOut) r.end();
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
