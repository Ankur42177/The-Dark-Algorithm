"use client";
/**
 * The only JavaScript in the "Hot right now" hand (~1 KB):
 *  - ‹ › buttons (and ←/→ while the hand has focus) put one card in the SPOTLIGHT: it lifts like on hover,
 *    so keyboard and touch users get the same show as mouse users;
 *  - "Shuffle" re-deals the hand (a CSS animation re-triggered by an attribute).
 * The cards themselves are server-rendered children; CSS does all the moving.
 */
import { useEffect, useRef, useState } from "react";

export function HotHandFx({ children, labels }: { children: React.ReactNode; labels: string[] }) {
  const root = useRef<HTMLDivElement>(null);
  const [spot, setSpot] = useState<number | null>(null);
  const [dealing, setDealing] = useState(false);
  const n = labels.length;

  useEffect(() => {
    root.current?.querySelectorAll<HTMLElement>(".hot-card").forEach((el) => {
      el.toggleAttribute("data-spot", spot !== null && Number(el.dataset.rank) === spot);
    });
  }, [spot]);

  useEffect(() => {
    if (!dealing) return;
    const t = window.setTimeout(() => setDealing(false), 1100);
    return () => window.clearTimeout(t);
  }, [dealing]);

  const step = (dir: 1 | -1) => setSpot((s) => (s === null ? (dir === 1 ? 0 : n - 1) : (s + dir + n) % n));
  const shuffle = () => {
    setSpot(null);
    setDealing(false);
    requestAnimationFrame(() => setDealing(true));   // off → on restarts the animation
  };

  return (
    <div ref={root} className="hot-fx" data-dealing={dealing ? "" : undefined}
         onKeyDown={(e) => {
           if (e.key === "ArrowRight") { e.preventDefault(); step(1); }
           if (e.key === "ArrowLeft") { e.preventDefault(); step(-1); }
         }}>
      {children}
      <div className="mt-2 flex items-center justify-center gap-3">
        <button type="button" className="icon-btn !h-10 !w-10" onClick={() => step(-1)} aria-label="Spotlight previous card">
          <svg width="14" height="14" viewBox="0 0 16 16" aria-hidden="true"><path d="M10 3 5 8l5 5" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" /></svg>
        </button>
        <button type="button" className="chip hot-shuffle !px-4 !py-2" onClick={shuffle}>
          <svg width="14" height="14" viewBox="0 0 16 16" aria-hidden="true"><path d="M2 4h2.5c2.5 0 3.5 8 6 8H14m0 0-2-2m2 2-2 2M2 12h2.5c1 0 1.7-1.2 2.3-2.6M14 4h-3.5c-1 0-1.7 1.2-2.3 2.6M14 4l-2-2m2 2-2 2" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" /></svg>
          Shuffle
        </button>
        <button type="button" className="icon-btn !h-10 !w-10" onClick={() => step(1)} aria-label="Spotlight next card">
          <svg width="14" height="14" viewBox="0 0 16 16" aria-hidden="true"><path d="M6 3l5 5-5 5" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" /></svg>
        </button>
      </div>
      <p className="sr-only" aria-live="polite">{spot !== null ? `Number ${spot + 1}: ${labels[spot]}` : ""}</p>
    </div>
  );
}
