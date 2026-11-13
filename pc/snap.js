'use strict';
// Математика перетаскивания карточки: бросок к углу экрана и «магнит» с равным отступом.
// Чистые функции без Electron, чтобы их можно было проверить в Node.

const FLING = 400;     // px/s: быстрее этого движение считается броском в сторону
const OMEGA = 9;       // жёсткость пружины (1/с): выше - быстрее долетает, 9 даёт ~0,5 с
const WINDOW_MS = 90;  // по какому отрезку перед отпусканием считаем скорость
const STALE_MS = 60;   // если курсор стоял дольше, скорость считается нулевой

// samples: [{t (мс), x, y}] - положение окна во время перетаскивания.
function velocity(samples, now) {
  const recent = samples.filter((s) => now - s.t <= WINDOW_MS);
  if (recent.length < 2) return { x: 0, y: 0 };
  const a = recent[0];
  const b = recent[recent.length - 1];
  const dt = (b.t - a.t) / 1000;
  if (now - b.t > STALE_MS || dt < 0.008) return { x: 0, y: 0 };
  return { x: (b.x - a.x) / dt, y: (b.y - a.y) / dt };
}

// Выбор угла по каждой оси отдельно: быстрое движение - в сторону броска,
// медленное или остановка - к ближайшей стороне экрана.
// rect: {x,y,width,height} окна; v: скорость; wa: рабочая область монитора.
function pickCorner(rect, v, wa) {
  const cx = rect.x + rect.width / 2;
  const cy = rect.y + rect.height / 2;
  const right = Math.abs(v.x) >= FLING ? v.x > 0 : cx > wa.x + wa.width / 2;
  const bottom = Math.abs(v.y) >= FLING ? v.y > 0 : cy > wa.y + wa.height / 2;
  return (bottom ? 'b' : 't') + (right ? 'r' : 'l');
}

// Левый верхний угол окна в выбранном углу: одинаковый отступ margin от обоих краёв.
function cornerPos(corner, size, wa, margin) {
  return {
    x: corner.endsWith('l') ? wa.x + margin : wa.x + wa.width - size.width - margin,
    y: corner.startsWith('t') ? wa.y + margin : wa.y + wa.height - size.height - margin,
  };
}

// Полёт: критически затухающая пружина по каждой оси (без перелёта за отступ).
// from/to: {x,y}; v: скорость отпускания, px/s.
function makeFlight(from, to, v) {
  const axis = (p, target, vel) => {
    const r0 = p - target;
    let v0 = vel;
    if (r0 === 0 || v0 * r0 > 0) v0 = 0;                       // от цели не летим
    else if (Math.abs(v0) > 0.95 * OMEGA * Math.abs(r0)) v0 = -Math.sign(r0) * 0.95 * OMEGA * Math.abs(r0); // не перелетаем
    return { target, r0, c2: v0 + OMEGA * r0 };
  };
  return { x: axis(from.x, to.x, v.x), y: axis(from.y, to.y, v.y) };
}

function flightAt(f, t) {
  const at = (a) => {
    const k = Math.exp(-OMEGA * t);
    const r = (a.r0 + a.c2 * t) * k;
    const vel = (a.c2 - OMEGA * (a.r0 + a.c2 * t)) * k;
    return { p: a.target + r, vel };
  };
  const x = at(f.x);
  const y = at(f.y);
  const done = t > 1.2 || (Math.abs(x.p - f.x.target) < 1.5 && Math.abs(y.p - f.y.target) < 1.5 && Math.abs(x.vel) < 40 && Math.abs(y.vel) < 40);
  return done
    ? { x: f.x.target, y: f.y.target, done: true }
    : { x: Math.round(x.p), y: Math.round(y.p), done: false };
}

module.exports = { FLING, OMEGA, velocity, pickCorner, cornerPos, makeFlight, flightAt };
