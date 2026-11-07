'use strict';
// Ядро NowPlaying: принимает состояние плеера с телефона, отдаёт его интерфейсу
// и пересылает команды интерфейса (пауза, next/prev, seek, like) обратно на телефон.
// Без внешних зависимостей, можно запускать отдельно: node server.js
const http = require('http');
const fs = require('fs');
const os = require('os');
const dgram = require('dgram');
const path = require('path');
const { EventEmitter } = require('events');

const PORT = Number(process.env.NP_PORT) || 8765;
const DISCOVERY_PORT = Number(process.env.NP_DISCOVERY_PORT) || 8766;
const bus = new EventEmitter();

const DEFAULT_CONFIG = {
  corner: 'tr',        // tl | tr | bl | br | custom
  pos: null,           // {x, y} для corner === 'custom'
  scale: 100,          // % размера карточки
  opacity: 96,         // % прозрачности
  spin: true,          // вращать пластинку
  hideWhenIdle: true,  // скрывать карточку, когда ничего не играет
  autostart: false,    // запускать вместе с Windows
};
let config = { ...DEFAULT_CONFIG };
let configFile = null;

const emptyState = () => ({
  title: '', artist: '', album: '', app: '', playing: false,
  position: 0, duration: 0, speed: 1,
  liked: false, canLike: false, canSeek: false,
  recvAt: 0, artVer: 0,
});
let state = emptyState();
let art = null;        // Buffer с PNG обложки
let artCounter = 0;

const sseClients = new Set();
const cmdQueue = [];
let pending = null;    // открытый long-poll запрос телефона
let lastSeen = 0;
let connected = false;

const COMMANDS = new Set(['play', 'pause', 'toggle', 'next', 'prev', 'seek', 'like']);
const clamp = (v, a, b) => Math.min(b, Math.max(a, v));

// ---------- конфиг ----------
function setConfigDir(dir) {
  configFile = path.join(dir, 'config.json');
  try {
    config = { ...DEFAULT_CONFIG, ...sanitize(JSON.parse(fs.readFileSync(configFile, 'utf8'))) };
  } catch (_) { /* нет файла - значения по умолчанию */ }
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
  return out;
}

function updateConfig(patch, source = 'ui') {
  const clean = sanitize(patch);
  const keys = Object.keys(clean);
  if (!keys.length) return config;
  config = { ...config, ...clean };
  if (configFile) {
    try { fs.writeFileSync(configFile, JSON.stringify(config, null, 2)); } catch (_) { /* не критично */ }
  }
  sse('config', config);
  bus.emit('config', config, keys, source);
  return config;
}

// ---------- SSE ----------
function sse(event, data) {
  const msg = `event: ${event}\ndata: ${JSON.stringify(data)}\n\n`;
  for (const res of sseClients) res.write(msg);
}
const publicState = () => ({ ...state, age: state.recvAt ? Date.now() - state.recvAt : 0 });
const lanIps = () => Object.values(os.networkInterfaces()).flat()
  .filter((i) => i && i.family === 'IPv4' && !i.internal).map((i) => i.address);
const infoPayload = () => ({ connected, ips: lanIps(), port: PORT });

function broadcastState() {
  sse('state', publicState());
  bus.emit('state', state);
}

// ---------- состояние от телефона ----------
const str = (v, n = 300) => String(v == null ? '' : v).slice(0, n);
const num = (v) => (Number.isFinite(Number(v)) ? Number(v) : 0);

function applyNow(d) {
  const title = str(d.title);
  if (!title.trim()) {
    state = { ...emptyState(), recvAt: Date.now() };
    art = null;
  } else {
    if (typeof d.art === 'string' && d.art) {
      art = Buffer.from(d.art, 'base64');
      artCounter++;
    } else if (!d.artSame) {
      art = null;
    }
    state = {
      title, artist: str(d.artist), album: str(d.album), app: str(d.app, 60),
      playing: !!d.playing,
      position: Math.max(0, num(d.position)),
      duration: Math.max(0, num(d.duration)),
      speed: num(d.speed) || 1,
      liked: !!d.liked, canLike: !!d.canLike, canSeek: !!d.canSeek,
      recvAt: Date.now(),
      artVer: art ? artCounter : 0,
    };
  }
  broadcastState();
}

// ---------- связь с телефоном ----------
function touch() {
  lastSeen = Date.now();
  checkConnection();
}

function checkConnection() {
  const c = !!pending || Date.now() - lastSeen < 40000;
  if (c !== connected) {
    connected = c;
    sse('info', infoPayload());
  }
  if (!c && state.title) {
    state = emptyState();
    art = null;
    broadcastState();
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
}

// ---------- HTTP ----------
function sendJson(res, code, obj) {
  const body = JSON.stringify(obj);
  res.writeHead(code, { 'Content-Type': 'application/json; charset=utf-8', 'Content-Length': Buffer.byteLength(body) });
  res.end(body);
}
const sendText = (res, code, text) => { res.writeHead(code, { 'Content-Type': 'text/plain; charset=utf-8' }); res.end(text); };

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

const server = http.createServer(async (req, res) => {
  try {
    const url = new URL(req.url, 'http://x');
    const p = url.pathname;

    // доступно телефону по локальной сети
    if (req.method === 'POST' && p === '/now') {
      const d = JSON.parse((await readBody(req)) || '{}');
      touch();
      applyNow(d);
      return sendJson(res, 200, { ok: true, needArt: !!d.artSame && !art });
    }
    if (req.method === 'GET' && p === '/poll') return handlePoll(req, res);

    // дальше только локально
    if (!isLocal(req)) return sendText(res, 403, 'forbidden');

    if (req.method === 'GET' && p === '/') {
      const html = fs.readFileSync(path.join(__dirname, 'ui', 'index.html'));
      res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8', 'Cache-Control': 'no-store' });
      return res.end(html);
    }
    if (p === '/favicon.ico') { res.writeHead(204); return res.end(); }
    if (req.method === 'GET' && p === '/art') {
      if (!art) return sendText(res, 404, 'no art');
      res.writeHead(200, { 'Content-Type': 'image/png', 'Cache-Control': 'max-age=3600' });
      return res.end(art);
    }
    if (req.method === 'GET' && p === '/events') {
      res.writeHead(200, { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache', Connection: 'keep-alive' });
      res.write('retry: 1000\n\n');
      sseClients.add(res);
      req.on('close', () => sseClients.delete(res));
      res.write(`event: config\ndata: ${JSON.stringify(config)}\n\n`);
      res.write(`event: info\ndata: ${JSON.stringify(infoPayload())}\n\n`);
      res.write(`event: state\ndata: ${JSON.stringify(publicState())}\n\n`);
      return undefined;
    }

    if (req.method === 'POST' && ['/cmd', '/config', '/quit'].includes(p)) {
      if (req.headers['x-np'] !== '1') return sendText(res, 403, 'forbidden');
      const body = JSON.parse((await readBody(req, 10000)) || '{}');
      if (p === '/cmd') {
        if (!COMMANDS.has(body.cmd)) return sendText(res, 400, 'bad command');
        pushCommand({ cmd: body.cmd, pos: Math.max(0, Math.round(num(body.pos))) });
      } else if (p === '/config') {
        updateConfig(body, 'ui');
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
  getConfig: () => config,
  getState: () => state,
  lanIps,
};

if (require.main === module) {
  if (process.env.NP_CONFIG_DIR) setConfigDir(process.env.NP_CONFIG_DIR);
  bus.on('quit', () => process.exit(0));
  start().then(() => console.log(`NowPlaying: http://127.0.0.1:${PORT}  (телефон: ${lanIps().join(', ')}:${PORT})`))
    .catch((e) => { console.error(e.message); process.exit(1); });
}
