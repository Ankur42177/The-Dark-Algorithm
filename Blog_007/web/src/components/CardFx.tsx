"use client";
/**
 * ONE delegated pointer listener for the whole site (≈1 KB): no per-card JavaScript.
 *
 * When the mouse moves over any element with a `data-fx="<degrees>"` attribute (cards, arena doors),
 * we write a few CSS custom properties onto it, at most once per animation frame:
 *   --mx / --my  pointer position in % (drives the specular highlight)
 *   --px / --py  pointer offset from the centre, -0.5 … 0.5 (drives parallax)
 *   --rx / --ry  tilt in degrees (drives the 3D rotation)
 * plus a `data-fx-on` attribute so CSS can make the tilt follow quickly while hovered.
 * CSS does all the actual drawing and animating (globals.css), on the GPU.
 *
 * Skipped entirely on touch-only devices and when the reader prefers reduced motion.
 */
import { useEffect } from "react";

export function CardFx() {
  useEffect(() => {
    const finePointer = window.matchMedia("(hover: hover) and (pointer: fine)");
    const reduce = window.matchMedia("(prefers-reduced-motion: reduce)");
    if (!finePointer.matches || reduce.matches) return;

    let el: HTMLElement | null = null;
    let frame = 0;
    let x = 0;
    let y = 0;

    const reset = (t: HTMLElement) => {
      for (const p of ["--mx", "--my", "--px", "--py", "--rx", "--ry"]) t.style.removeProperty(p);
      t.removeAttribute("data-fx-on");
    };

    const apply = () => {
      frame = 0;
      if (!el) return;
      const r = el.getBoundingClientRect();
      const px = Math.min(1, Math.max(0, (x - r.left) / r.width));
      const py = Math.min(1, Math.max(0, (y - r.top) / r.height));
      const max = Number(el.dataset.fx) || 8;
      const s = el.style;
      s.setProperty("--mx", `${(px * 100).toFixed(1)}%`);
      s.setProperty("--my", `${(py * 100).toFixed(1)}%`);
      s.setProperty("--px", (px - 0.5).toFixed(3));
      s.setProperty("--py", (py - 0.5).toFixed(3));
      s.setProperty("--rx", `${((0.5 - py) * max).toFixed(2)}deg`);
      s.setProperty("--ry", `${((px - 0.5) * max).toFixed(2)}deg`);
      el.setAttribute("data-fx-on", "");
    };

    const onMove = (e: PointerEvent) => {
      if (e.pointerType !== "mouse" && e.pointerType !== "pen") return;
      const target = (e.target as Element | null)?.closest?.<HTMLElement>("[data-fx]") ?? null;
      if (target !== el) {
        if (el) reset(el);
        el = target;
      }
      if (!el) return;
      x = e.clientX;
      y = e.clientY;
      if (!frame) frame = requestAnimationFrame(apply);
    };
    const onLeave = () => {
      if (el) reset(el);
      el = null;
    };

    document.addEventListener("pointermove", onMove, { passive: true });
    document.documentElement.addEventListener("pointerleave", onLeave);
    window.addEventListener("blur", onLeave);
    return () => {
      document.removeEventListener("pointermove", onMove);
      document.documentElement.removeEventListener("pointerleave", onLeave);
      window.removeEventListener("blur", onLeave);
      if (frame) cancelAnimationFrame(frame);
      onLeave();
    };
  }, []);
  return null;
}
