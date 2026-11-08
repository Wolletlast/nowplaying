'use strict';
// Подключает к ядру (server.js) плееры и окна самого ПК через PowerShell-мост (ps/smtc.ps1).
const winps = require('./winps');

function attach(server, userDir) {
  const noop = { stop() {} };
  if (!winps.IS_WIN) {
    server.setPcStatus({ available: false, error: 'Приложения ПК поддерживаются только в Windows' });
    return noop;
  }
  winps.init(userDir);

  let child = null;
  let buf = '';
  let lastErr = '';
  let fails = 0;
  let stopped = false;
  let gotData = false;

  function onLine(line) {
    let m;
    try { m = JSON.parse(line); } catch (_) { return; }
    gotData = true;
    if (m.t === 'sessions') {
      fails = 0;
      server.setPcSessions(m.list || []);
    } else if (m.t === 'windows') {
      server.setWindows(m.list || []);
    } else if (m.t === 'error') {
      lastErr = String(m.msg || '');
      server.setPcStatus({ available: false, error: lastErr });
    }
  }

  function start() {
    if (stopped) return;
    gotData = false;
    try {
      child = winps.spawnScript('smtc.ps1');
    } catch (e) {
      server.setPcStatus({ available: false, error: e.message });
      return;
    }
    child.stdout.setEncoding('utf8');
    child.stderr.setEncoding('utf8');
    child.stdout.on('data', (d) => {
      buf += d;
      let i;
      while ((i = buf.indexOf('\n')) >= 0) {
        const line = buf.slice(0, i).trim();
        buf = buf.slice(i + 1);
        if (line) onLine(line);
      }
    });
    child.stderr.on('data', (d) => { lastErr = (lastErr + d).slice(-300); });
    child.on('error', (e) => server.setPcStatus({ available: false, error: e.message }));
    child.on('exit', () => {
      child = null;
      if (stopped) return;
      fails += 1;
      if (fails >= 5) {
        server.setPcStatus({
          available: false,
          error: (lastErr || 'PowerShell завершился. Нужна Windows 10 (1809) или новее.').trim().slice(0, 300),
        });
        return;
      }
      setTimeout(start, 2000);
    });
  }

  function send(c) {
    if (!child || !child.stdin || !child.stdin.writable) return;
    // не-ASCII символы экранируем: кодировка stdin у PowerShell зависит от системы
    const text = JSON.stringify(c).replace(/[\u0080-￿]/g, (ch) => '\\u' + ch.charCodeAt(0).toString(16).padStart(4, '0'));
    try { child.stdin.write(text + '\n'); } catch (_) { /* процесс завершается */ }
  }

  server.bus.on('pc-cmd', send);
  start();

  return {
    stop() {
      stopped = true;
      if (child) { try { child.kill(); } catch (_) { /* уже завершён */ } }
    },
    gotData: () => gotData,
  };
}

module.exports = { attach };
