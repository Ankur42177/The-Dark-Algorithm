"use client";
/**
 * Play mode ⚽ — footballs drip from the top of the screen, bounce, collide and pile up at the bottom.
 * Move the mouse (or a finger) through them to kick; click empty space for a burst.
 *
 * Loaded ONLY when Play is switched on (dynamic import in HeaderControls), so readers who never play pay 0 KB.
 * Performance & manners:
 *  - one <canvas> fixed over the page with pointer-events: none → the page stays fully clickable;
 *    we listen to pointer events on window instead of on the canvas
 *  - requestAnimationFrame loop, paused while the tab is hidden; delta time clamped (no explosions after a pause)
 *  - devicePixelRatio-aware (crisp on retina), ball count capped, each ball drawn from a pre-rendered sprite
 *  - switches itself off if the reader turns on "reduce motion"; Esc stops it
 */
import { useEffect, useRef, useState } from "react";

type Ball = { x: number; y: number; vx: number; vy: number; r: number; a: number };

const MAX_BALLS = 70;
const DRIP_TOTAL = 30;          // how many drip in on their own
const DRIP_EVERY = 260;         // ms between drips
const GRAVITY = 2000;           // px/s²
const BOUNCE = 0.5;
const KICK_REACH = 14;          // px of "boot" around the pointer

/** Draws one classic black-and-white football (centre pentagon, five around it, seams) on an offscreen canvas. */
function makeSprites(size: number) {
  const ball = document.createElement("canvas");
  const shade = document.createElement("canvas");
  ball.width = ball.height = shade.width = shade.height = size;
  const R = size / 2;
  const g = ball.getContext("2d")!;

  const poly = (cx: number, cy: number, rad: number, rot: number) => {
    const pts: [number, number][] = [];
    for (let k = 0; k < 5; k++) {
      const t = rot + (k * 2 * Math.PI) / 5;
      pts.push([cx + rad * Math.cos(t), cy + rad * Math.sin(t)]);
    }
    return pts;
  };
  const fillPoly = (pts: [number, number][]) => {
    g.beginPath();
    pts.forEach(([x, y], i) => (i ? g.lineTo(x, y) : g.moveTo(x, y)));
    g.closePath();
    g.fill();
  };

  g.save();
  g.beginPath();
  g.arc(R, R, R - 1, 0, Math.PI * 2);
  g.clip();
  g.fillStyle = "#fbfbfd";
  g.fillRect(0, 0, size, size);

  const pr = R * 0.34;                         // pentagon "radius"
  const centre = poly(R, R, pr, -Math.PI / 2);
  g.fillStyle = "#111827";
  fillPoly(centre);

  g.strokeStyle = "#2b3242";
  g.lineWidth = Math.max(1, size / 90);
  const outer: [number, number][][] = [];
  for (let k = 0; k < 5; k++) {
    const t = -Math.PI / 2 + (k * 2 * Math.PI) / 5;
    const cx = R + Math.cos(t) * R * 0.98;
    const cy = R + Math.sin(t) * R * 0.98;
    const pts = poly(cx, cy, pr, t + Math.PI);  // one vertex points back at the centre
    outer.push(pts);
    fillPoly(pts);
    // seam: centre-pentagon corner → outer pentagon's inner corner
    g.beginPath();
    g.moveTo(centre[k][0], centre[k][1]);
    g.lineTo(pts[0][0], pts[0][1]);
    g.stroke();
  }
  // seams between neighbouring outer pentagons (the hexagon edges)
  for (let k = 0; k < 5; k++) {
    const a = outer[k];
    const b = outer[(k + 1) % 5];
    g.beginPath();
    g.moveTo(a[4][0], a[4][1]);
    g.lineTo(b[1][0], b[1][1]);
    g.stroke();
  }
  g.restore();

  // Shading lives on a second sprite that does NOT rotate, so the light always comes from the top-left.
  const s = shade.getContext("2d")!;
  const grad = s.createRadialGradient(R * 0.65, R * 0.55, R * 0.1, R, R, R);
  grad.addColorStop(0, "rgba(255,255,255,0.35)");
  grad.addColorStop(0.55, "rgba(255,255,255,0)");
  grad.addColorStop(1, "rgba(10,18,40,0.38)");
  s.fillStyle = grad;
  s.beginPath();
  s.arc(R, R, R - 1, 0, Math.PI * 2);
  s.fill();
  s.strokeStyle = "rgba(10,18,40,0.35)";
  s.lineWidth = Math.max(1, size / 64);
  s.stroke();
  return { ball, shade };
}

export function PlayField({ onStop }: { onStop: () => void }) {
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const api = useRef<{ clear: () => void; rain: () => void } | null>(null);
  const [count, setCount] = useState(0);

  useEffect(() => {
    const canvas = canvasRef.current!;
    const ctx = canvas.getContext("2d")!;
    const reduce = window.matchMedia("(prefers-reduced-motion: reduce)");
    if (reduce.matches) { onStop(); return; }

    let W = 0, H = 0, dpr = 1;
    const resize = () => {
      dpr = Math.min(window.devicePixelRatio || 1, 2);
      W = window.innerWidth;
      H = window.innerHeight;
      canvas.width = Math.round(W * dpr);
      canvas.height = Math.round(H * dpr);
      canvas.style.width = `${W}px`;
      canvas.style.height = `${H}px`;
    };
    resize();

    const { ball: sprite, shade } = makeSprites(128);
    const balls: Ball[] = [];
    let dripped = 0;
    let dripping = true;
    let lastDrip = 0;
    const radius = () => Math.max(13, Math.min(26, W / 64)) * (0.85 + Math.random() * 0.3);

    const add = (b: Ball) => {
      balls.push(b);
      if (balls.length > MAX_BALLS) balls.shift();   // oldest ball retires
    };
    const drip = () => {
      const r = radius();
      add({ x: r + Math.random() * (W - 2 * r), y: -r - 10, vx: (Math.random() - 0.5) * 140, vy: 60 + Math.random() * 120, r, a: Math.random() * 6.28 });
      dripped++;
    };
    const burst = (x: number, y: number) => {
      for (let i = 0; i < 7; i++) {
        const t = (i / 7) * Math.PI * 2 + Math.random() * 0.4;
        const sp = 420 + Math.random() * 380;
        add({ x, y, vx: Math.cos(t) * sp, vy: Math.sin(t) * sp - 300, r: radius(), a: Math.random() * 6.28 });
      }
    };
    api.current = {
      clear: () => { balls.length = 0; dripping = false; },
      rain: () => { dripped = 0; dripping = true; },
    };

    // ---- the pointer is the boot: position + smoothed velocity (px/s)
    const p = { x: -9999, y: -9999, vx: 0, vy: 0, t: 0, active: false };
    const onMove = (e: PointerEvent) => {
      const now = performance.now();
      const dt = Math.max(1, now - p.t) / 1000;
      if (p.active) {
        p.vx = p.vx * 0.5 + ((e.clientX - p.x) / dt) * 0.5;
        p.vy = p.vy * 0.5 + ((e.clientY - p.y) / dt) * 0.5;
      }
      p.x = e.clientX; p.y = e.clientY; p.t = now; p.active = true;
    };
    const onLeave = () => { p.active = false; p.x = p.y = -9999; p.vx = p.vy = 0; };
    const onClick = (e: MouseEvent) => {
      const t = e.target as Element | null;
      if (t?.closest("a, button, input, textarea, select, label, summary, [role='button'], [role='radio'], [data-deck-card]")) return;
      burst(e.clientX, e.clientY);
    };
    const onKey = (e: KeyboardEvent) => { if (e.key === "Escape") onStop(); };

    // ---- physics
    const step = (dt: number) => {
      for (const b of balls) {
        b.vy += GRAVITY * dt;
        b.x += b.vx * dt;
        b.y += b.vy * dt;
        // floor (the bottom of the viewport) and walls
        if (b.y + b.r > H) {
          b.y = H - b.r;
          b.vy = Math.abs(b.vy) < 70 ? 0 : -b.vy * BOUNCE;
          b.vx *= 0.985;
        }
        if (b.x - b.r < 0) { b.x = b.r; b.vx = Math.abs(b.vx) * 0.6; }
        if (b.x + b.r > W) { b.x = W - b.r; b.vx = -Math.abs(b.vx) * 0.6; }
        b.vx *= 0.999;
        b.a += (b.vx / b.r) * dt;                      // roll
      }
      // ball ↔ ball: separate the overlap, then exchange momentum along the contact normal (heavier = bigger)
      for (let i = 0; i < balls.length; i++) {
        const a = balls[i];
        for (let j = i + 1; j < balls.length; j++) {
          const b = balls[j];
          const dx = b.x - a.x, dy = b.y - a.y;
          const min = a.r + b.r;
          const d2 = dx * dx + dy * dy;
          if (d2 >= min * min || d2 === 0) continue;
          const d = Math.sqrt(d2);
          const nx = dx / d, ny = dy / d;
          const ma = a.r * a.r, mb = b.r * b.r;
          const overlap = min - d;
          a.x -= nx * overlap * (mb / (ma + mb));
          a.y -= ny * overlap * (mb / (ma + mb));
          b.x += nx * overlap * (ma / (ma + mb));
          b.y += ny * overlap * (ma / (ma + mb));
          const rel = (b.vx - a.vx) * nx + (b.vy - a.vy) * ny;
          if (rel < 0) {
            const imp = (-(1 + 0.45) * rel) / (1 / ma + 1 / mb);
            a.vx -= (imp / ma) * nx; a.vy -= (imp / ma) * ny;
            b.vx += (imp / mb) * nx; b.vy += (imp / mb) * ny;
          }
        }
      }
      // the kick: pointer velocity transfers into any ball it touches
      if (p.active) {
        for (const b of balls) {
          const dx = b.x - p.x, dy = b.y - p.y;
          const reach = b.r + KICK_REACH;
          const d2 = dx * dx + dy * dy;
          if (d2 >= reach * reach) continue;
          const d = Math.sqrt(d2) || 1;
          const nx = dx / d, ny = dy / d;
          b.x = p.x + nx * reach;
          b.y = p.y + ny * reach;
          const push = Math.max(220, p.vx * nx + p.vy * ny);
          b.vx += nx * push * 0.9 + p.vx * 0.35;
          b.vy += ny * push * 0.9 + p.vy * 0.35 - 120;
          const sp = Math.hypot(b.vx, b.vy);
          if (sp > 2600) { b.vx *= 2600 / sp; b.vy *= 2600 / sp; }
        }
        p.vx *= 0.8; p.vy *= 0.8;                        // a resting cursor stops kicking
      }
    };

    const draw = () => {
      ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
      ctx.clearRect(0, 0, W, H);
      for (const b of balls) {
        ctx.save();
        ctx.translate(b.x, b.y);
        ctx.rotate(b.a);
        ctx.drawImage(sprite, -b.r, -b.r, b.r * 2, b.r * 2);
        ctx.restore();
        ctx.drawImage(shade, b.x - b.r, b.y - b.r, b.r * 2, b.r * 2);
      }
    };

    let raf = 0;
    let last = performance.now();
    let shown = -1;
    const loop = (now: number) => {
      const dt = Math.min(0.033, (now - last) / 1000);
      last = now;
      if (dripping && dripped < DRIP_TOTAL && now - lastDrip > DRIP_EVERY) { drip(); lastDrip = now; }
      step(dt / 2);
      step(dt / 2);                                      // two sub-steps = steadier piles
      draw();
      if (balls.length !== shown) { shown = balls.length; setCount(shown); }
      raf = requestAnimationFrame(loop);
    };
    const start = () => { if (!raf) { last = performance.now(); raf = requestAnimationFrame(loop); } };
    const pause = () => { cancelAnimationFrame(raf); raf = 0; };
    const onVisibility = () => (document.hidden ? pause() : start());
    const onReduce = () => { if (reduce.matches) onStop(); };

    window.addEventListener("resize", resize);
    window.addEventListener("pointermove", onMove, { passive: true });
    window.addEventListener("pointerdown", onMove, { passive: true });
    document.documentElement.addEventListener("pointerleave", onLeave);
    window.addEventListener("click", onClick);
    window.addEventListener("keydown", onKey);
    document.addEventListener("visibilitychange", onVisibility);
    reduce.addEventListener("change", onReduce);
    start();

    return () => {
      pause();
      window.removeEventListener("resize", resize);
      window.removeEventListener("pointermove", onMove);
      window.removeEventListener("pointerdown", onMove);
      document.documentElement.removeEventListener("pointerleave", onLeave);
      window.removeEventListener("click", onClick);
      window.removeEventListener("keydown", onKey);
      document.removeEventListener("visibilitychange", onVisibility);
      reduce.removeEventListener("change", onReduce);
      api.current = null;
    };
  }, [onStop]);

  return (
    <>
      <canvas ref={canvasRef} aria-hidden="true" className="pointer-events-none fixed inset-0 z-40" />
      <div role="group" aria-label="Play mode controls"
           className="fixed bottom-5 left-1/2 z-50 flex -translate-x-1/2 items-center gap-1.5 whitespace-nowrap rounded-full border border-line bg-[var(--glass-solid)] py-1.5 pl-4 pr-1.5 text-[var(--glass-ink)] shadow-[0_18px_40px_-16px_rgb(0_0_0/.6)] backdrop-blur-xl">
        <span className="mr-1 text-[.66rem] font-bold uppercase tracking-[.16em]">
          ⚽ {count} {count === 1 ? "ball" : "balls"}
          <span className="hidden font-medium normal-case tracking-normal text-[var(--glass-muted)] sm:inline"> · move to kick, click to burst</span>
        </span>
        <button type="button" onClick={() => api.current?.rain()} className="chip !py-1.5">Rain</button>
        <button type="button" onClick={() => api.current?.clear()} className="chip !py-1.5">Clear</button>
        <button type="button" onClick={onStop} className="chip !border-transparent !bg-[var(--color-coral)] !py-1.5 !text-white">Stop</button>
      </div>
    </>
  );
}
