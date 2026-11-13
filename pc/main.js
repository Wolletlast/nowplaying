'use strict';
// Окно-оверлей NowPlaying (Electron). Вся логика приёма данных - в server.js, интерфейс - в ui/index.html.
const { app, BrowserWindow, Tray, Menu, nativeImage, screen, dialog, shell, ipcMain } = require('electron');
const path = require('path');
const server = require('./server');
const pcmedia = require('./pcmedia');
const snap = require('./snap');

const BASE_W = 420;
const BASE_H = 150;
const MARGIN = 16;

let win = null;
let tray = null;
let settingsWin = null;
let audioWin = null;
let applying = false;
let pinned = false;                       // показывать карточку независимо от того, играет ли музыка
let lastActive = 0;
const startedAt = Date.now();

const clamp = (v, a, b) => Math.min(b, Math.max(a, v));

if (!app.requestSingleInstanceLock()) {
  app.quit();
} else {
  app.on('second-instance', () => { pinned = true; updateVisibility(); });
  app.on('window-all-closed', (e) => e.preventDefault());   // окно только прячем, программа живёт в трее
  app.whenReady().then(init);
}

async function init() {
  server.setConfigDir(app.getPath('userData'));
  try {
    await server.start();
  } catch (e) {
    dialog.showErrorBox('NowPlaying', `Не удалось запустить сервер на порту ${server.PORT}: ${e.message}`);
    app.quit();
    return;
  }
  createWindow();
  createAudioWindow();
  createTray();
  server.bus.on('config', onConfig);
  server.bus.on('state', onState);
  server.bus.on('quit', () => app.quit());
  server.bus.on('open-settings', openSettings);
  server.bus.on('open-external', (url) => { if (/^ms-settings:[a-z-]+$/.test(url)) shell.openExternal(url).catch(() => {}); });
  pcmedia.attach(server, app.getPath('userData'));
  setInterval(updateVisibility, 1500);
  applyAutostart();
}

function createWindow() {
  win = new BrowserWindow({
    width: BASE_W, height: BASE_H, useContentSize: true,
    frame: false, transparent: true, backgroundColor: '#00000000', hasShadow: false,
    resizable: false, maximizable: false, minimizable: false, fullscreenable: false,
    skipTaskbar: true, alwaysOnTop: true, show: false,
    webPreferences: { backgroundThrottling: false, preload: path.join(__dirname, 'preload.js') },
  });
  win.setAlwaysOnTop(true, 'screen-saver');
  win.setMenuBarVisibility(false);
  win.loadURL(`http://127.0.0.1:${server.PORT}/`);
  win.webContents.on('did-finish-load', () => { applyLayout(); updateVisibility(); });
  ipcMain.on('np-drag', (e, phase) => {
    if (!win || win.isDestroyed() || e.sender !== win.webContents) return;
    if (phase === 'start') dragStart();
    else if (phase === 'end') dragEnd();
  });
}

// Невидимое окно-«динамик»: играет звук, который телефон присылает на ПК (ui/player.html).
function createAudioWindow() {
  audioWin = new BrowserWindow({
    show: false, width: 200, height: 100, skipTaskbar: true,
    webPreferences: { backgroundThrottling: false, autoplayPolicy: 'no-user-gesture-required' },
  });
  audioWin.loadURL(`http://127.0.0.1:${server.PORT}/player`);
  audioWin.on('closed', () => { audioWin = null; });
}

function createTray() {
  const icon = nativeImage.createFromPath(path.join(__dirname, 'tray.png'));
  tray = new Tray(icon);
  tray.setToolTip('NowPlaying');
  const rebuild = () => tray.setContextMenu(Menu.buildFromTemplate([
    { label: pinned ? 'Скрывать, когда ничего не играет' : 'Всегда показывать карточку',
      click: () => { pinned = !pinned; updateVisibility(); rebuild(); } },
    { label: 'Настройки…', click: () => { openSettings(); } },
    { type: 'separator' },
    { label: 'Выход', click: () => app.quit() },
  ]));
  rebuild();
  tray.on('click', () => { pinned = !pinned; updateVisibility(); rebuild(); });
}

function openSettings() {
  if (settingsWin && !settingsWin.isDestroyed()) {
    if (settingsWin.isMinimized()) settingsWin.restore();
    settingsWin.show();
    settingsWin.focus();
    return;
  }
  settingsWin = new BrowserWindow({
    width: 560, height: 740, minWidth: 420, minHeight: 480,
    title: 'NowPlaying — настройки', backgroundColor: '#0d0b1c', autoHideMenuBar: true,
    webPreferences: { backgroundThrottling: true },
  });
  settingsWin.setMenuBarVisibility(false);
  settingsWin.loadURL(`http://127.0.0.1:${server.PORT}/settings`);
  settingsWin.on('closed', () => { settingsWin = null; });
}

// ---------- расположение ----------
function applyLayout() {
  if (!win) return;
  stopFlight();
  const cfg = server.getConfig();
  const s = cfg.scale / 100;
  const w = Math.round(BASE_W * s);
  const h = Math.round(BASE_H * s);

  applying = true;
  win.webContents.setZoomFactor(s);
  win.setContentSize(w, h);

  let x;
  let y;
  if (cfg.corner === 'custom' && cfg.pos) {
    const wa = screen.getDisplayMatching({ x: cfg.pos.x, y: cfg.pos.y, width: w, height: h }).workArea;
    x = clamp(cfg.pos.x, wa.x, wa.x + wa.width - w);
    y = clamp(cfg.pos.y, wa.y, wa.y + wa.height - h);
  } else {
    const wa = screen.getPrimaryDisplay().workArea;
    x = cfg.corner.endsWith('l') ? wa.x + MARGIN : wa.x + wa.width - w - MARGIN;
    y = cfg.corner.startsWith('t') ? wa.y + MARGIN : wa.y + wa.height - h - MARGIN;
  }
  win.setPosition(Math.round(x), Math.round(y));
  setTimeout(() => { applying = false; }, 400);
}

// ---------- перетаскивание: бросок к углу ----------
// Пока карточка «взята», окно следует за курсором (опрос ~120 Гц). При отпускании по скорости
// и положению выбирается угол, окно летит к нему на пружине и встаёт с отступом MARGIN от краёв.
let drag = null;
let flight = null;

function stopFlight() {
  if (flight) { clearInterval(flight.timer); flight = null; }
}

function dragStart() {
  if (!win || win.isDestroyed()) return;
  stopFlight();
  if (drag) clearInterval(drag.timer);
  const b = win.getBounds();
  const c = screen.getCursorScreenPoint();
  drag = { offX: c.x - b.x, offY: c.y - b.y, w: b.width, h: b.height, x0: b.x, y0: b.y, samples: [], moved: false, timer: null };
  const tick = () => {
    if (!drag || win.isDestroyed()) return;
    const p = screen.getCursorScreenPoint();
    const x = p.x - drag.offX;
    const y = p.y - drag.offY;
    drag.samples.push({ t: performance.now(), x, y });
    if (drag.samples.length > 16) drag.samples.shift();
    if (!drag.moved && Math.hypot(x - drag.x0, y - drag.y0) > 4) drag.moved = true;   // меньше 4 px - это просто клик
    if (drag.moved) win.setBounds({ x, y, width: drag.w, height: drag.h });
  };
  drag.timer = setInterval(tick, 8);
  tick();
}

function dragEnd() {
  if (!drag) return;
  const d = drag;
  drag = null;
  clearInterval(d.timer);
  if (!win || win.isDestroyed() || !d.moved) return;

  const b = win.getBounds();
  const v = snap.velocity(d.samples, performance.now());
  const display = screen.getDisplayNearestPoint({ x: b.x + b.width / 2, y: b.y + b.height / 2 });
  const corner = snap.pickCorner(b, v, display.workArea);
  const to = snap.cornerPos(corner, b, display.workArea, MARGIN);
  const f = snap.makeFlight({ x: b.x, y: b.y }, to, v);
  const t0 = performance.now();
  flight = { timer: setInterval(() => {
    if (!win || win.isDestroyed()) { stopFlight(); return; }
    const s = snap.flightAt(f, (performance.now() - t0) / 1000);
    win.setBounds({ x: s.x, y: s.y, width: b.width, height: b.height });
    if (s.done) stopFlight();
  }, 8) };

  // на основном мониторе запоминаем угол, на другом - точную позицию
  const onPrimary = display.id === screen.getPrimaryDisplay().id;
  server.updateConfig(onPrimary ? { corner, pos: null } : { corner: 'custom', pos: { x: to.x, y: to.y } }, 'drag');
}

function onConfig(cfg, keys, source) {
  if (!win) return;
  if (!(source === 'drag')) applyLayout();
  if (keys.includes('autostart')) applyAutostart();
  updateVisibility();
}

function applyAutostart() {
  try {
    app.setLoginItemSettings({
      openAtLogin: !!server.getConfig().autostart,
      path: process.env.PORTABLE_EXECUTABLE_FILE || process.execPath,
      args: app.isPackaged ? [] : [app.getAppPath()],
    });
  } catch (_) { /* не критично */ }
}

// ---------- видимость ----------
function onState() { updateVisibility(); }

function updateVisibility() {
  if (!win || win.isDestroyed()) return;
  const cfg = server.getConfig();
  const s = server.getState();
  if (s.playing) lastActive = Date.now();

  const recentlyActive = s.title && (s.playing || Date.now() - lastActive < 12000);
  const show = pinned || !cfg.hideWhenIdle || recentlyActive || Date.now() - startedAt < 15000;

  if (show && !win.isVisible()) win.showInactive();
  else if (!show && win.isVisible()) win.hide();
}
