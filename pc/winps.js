'use strict';
// Запуск PowerShell-скриптов (только Windows).
// Скрипты копируются во внешнюю папку, потому что из упакованного приложения (asar) их запустить нельзя.
const { spawn } = require('child_process');
const fs = require('fs');
const os = require('os');
const path = require('path');

const IS_WIN = process.platform === 'win32';
const SCRIPTS = ['smtc.ps1', 'audio.ps1'];
const PS_ARGS = ['-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-File'];
let dir = null;

function init(userDir) {
  dir = userDir || path.join(os.tmpdir(), 'nowplaying');
  try { fs.mkdirSync(dir, { recursive: true }); } catch (_) { /* папка уже есть */ }
  for (const name of SCRIPTS) {
    try {
      const src = fs.readFileSync(path.join(__dirname, 'ps', name), 'utf8').replace(/^﻿/, '');
      // BOM нужен, чтобы Windows PowerShell 5.1 читал файл как UTF-8
      fs.writeFileSync(path.join(dir, name), '﻿' + src);
    } catch (_) { /* не критично: ошибка проявится при запуске */ }
  }
}

function scriptPath(name) {
  if (!dir) init();
  return path.join(dir, name);
}

/** Запускает скрипт и оставляет процесс работать (stdin и stdout - каналы). */
function spawnScript(name, args = []) {
  return spawn('powershell.exe', [...PS_ARGS, scriptPath(name), ...args], {
    windowsHide: true,
    stdio: ['pipe', 'pipe', 'pipe'],
  });
}

/** Разовый запуск: ждём последнюю JSON-строку из stdout. Всегда возвращает объект, ошибки в поле error. */
function runJson(name, args = [], timeoutMs = 25000) {
  return new Promise((resolve) => {
    if (!IS_WIN) { resolve({ error: 'Доступно только в Windows' }); return; }
    let out = '';
    let err = '';
    let done = false;
    let timer = null;
    let child = null;
    const finish = (v) => {
      if (done) return;
      done = true;
      clearTimeout(timer);
      resolve(v);
    };
    try {
      child = spawnScript(name, args);
    } catch (e) {
      finish({ error: e.message });
      return;
    }
    timer = setTimeout(() => {
      try { child.kill(); } catch (_) { /* уже завершён */ }
      finish({ error: 'Время ожидания истекло' });
    }, timeoutMs);
    child.stdout.setEncoding('utf8');
    child.stderr.setEncoding('utf8');
    child.stdout.on('data', (d) => { out += d; });
    child.stderr.on('data', (d) => { err += d; });
    child.on('error', (e) => finish({ error: e.message }));
    child.on('close', () => {
      const line = out.trim().split(/\r?\n/).filter(Boolean).pop();
      try {
        finish(JSON.parse(line));
      } catch (_) {
        finish({ error: (err || out || 'Нет ответа от PowerShell').trim().slice(0, 300) });
      }
    });
  });
}

module.exports = { IS_WIN, init, spawnScript, runJson };
