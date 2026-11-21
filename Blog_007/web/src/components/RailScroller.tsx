"use client";
/**
 * Horizontal rail: CSS scroll-snap does the real work (swipe / trackpad); this tiny island only adds the round
 * ‹ › buttons and greys them out at either end. The cards are server-rendered children (zero JS each).
 */
import { useCallback, useEffect, useRef, useState } from "react";

function Arrow({ dir, disabled, onClick }: { dir: -1 | 1; disabled: boolean; onClick: () => void }) {
  return (
    <button type="button" onClick={onClick} disabled={disabled} aria-label={dir < 0 ? "Scroll back" : "Scroll forward"}
      className="grid h-11 w-11 place-items-center rounded-full bg-[var(--color-navy)] text-white shadow-[0_10px_24px_-10px_rgb(20_33_61/.7)] ring-1 ring-white/10 transition hover:-translate-y-0.5 hover:scale-105 active:scale-95 disabled:pointer-events-none disabled:opacity-30 dark:bg-white dark:text-[#0a1430]">
      <svg width="16" height="16" viewBox="0 0 16 16" aria-hidden="true">
        <path d={dir < 0 ? "M10 3 5 8l5 5" : "M6 3l5 5-5 5"} fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" />
      </svg>
    </button>
  );
}

export function RailScroller({ children, label }: { children: React.ReactNode; label: string }) {
  const track = useRef<HTMLDivElement>(null);
  const [atStart, setAtStart] = useState(true);
  const [atEnd, setAtEnd] = useState(false);

  const measure = useCallback(() => {
    const t = track.current;
    if (!t) return;
    setAtStart(t.scrollLeft < 8);
    setAtEnd(t.scrollLeft + t.clientWidth >= t.scrollWidth - 8);
  }, []);

  useEffect(() => {
    measure();
    window.addEventListener("resize", measure);
    return () => window.removeEventListener("resize", measure);
  }, [measure]);

  const go = (dir: -1 | 1) => {
    const t = track.current;
    if (!t) return;
    const reduce = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
    t.scrollBy({ left: dir * t.clientWidth * 0.8, behavior: reduce ? "auto" : "smooth" });
  };

  return (
    <div className="relative">
      <div className="absolute -top-[4.6rem] right-0 hidden gap-2.5 md:flex">
        <Arrow dir={-1} disabled={atStart} onClick={() => go(-1)} />
        <Arrow dir={1} disabled={atEnd} onClick={() => go(1)} />
      </div>
      <div ref={track} onScroll={measure} className="rail no-scrollbar" role="region" aria-roledescription="carousel" aria-label={label} tabIndex={0}>
        {children}
      </div>
    </div>
  );
}
