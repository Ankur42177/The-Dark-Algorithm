"use client";
/**
 * The hero's deck of cards: Pulse posts shuffled with articles from official engineering blogs.
 *
 * The card FACES are Server Components passed in as children (zero JS each); this island only decides where
 * each card sits. Every card gets two CSS variables and CSS does the animating (transform-only, GPU friendly):
 *   --d  depth in the pile (0 = top card)     → stacked look
 *   --f  slot in the fan (0, +1, -1, +2, -2) → fanned look on hover / keyboard focus
 *
 * Ways to traverse: ‹ › buttons, ← → keys (deck focused), clicking a card behind the top one,
 * and swipe/drag (touch or mouse). A short drag is still a click, so links keep working.
 */
import { Children, useEffect, useRef, useState } from "react";

const VISIBLE = 5;           // cards drawn at once; the rest wait invisibly at the bottom of the pile
const SWIPE = 60;            // px of drag that counts as a swipe

function fanSlot(d: number) {
  if (d === 0) return 0;
  return d % 2 === 1 ? (d + 1) / 2 : -d / 2;
}

export function HeroDeck({ children, labels }: { children: React.ReactNode; labels: string[] }) {
  const cards = Children.toArray(children);
  const n = cards.length;
  const [active, setActive] = useState(0);
  const [leaving, setLeaving] = useState<{ i: number; dir: -1 | 1 } | null>(null);
  const deck = useRef<HTMLDivElement>(null);
  const drag = useRef<{ x: number; y: number; dx: number; moved: boolean; id: number } | null>(null);
  const suppressClick = useRef(false);
  const leaveTimer = useRef<number | undefined>(undefined);

  function show(next: number, fly?: -1 | 1) {
    const target = ((next % n) + n) % n;
    if (target === active) return;
    if (fly) {
      setLeaving({ i: active, dir: fly });
      window.clearTimeout(leaveTimer.current);
      leaveTimer.current = window.setTimeout(() => setLeaving(null), 520);
    }
    setActive(target);
  }
  const next = (fly: -1 | 1 = -1) => show(active + 1, fly);
  const prev = () => show(active - 1);

  useEffect(() => () => window.clearTimeout(leaveTimer.current), []);

  // Only the top card's links are reachable with Tab; the rest are decoration until they come to the top.
  useEffect(() => {
    deck.current?.querySelectorAll<HTMLElement>("[data-deck-card]").forEach((el) => {
      const top = el.dataset.pos === "0";
      el.querySelectorAll("a").forEach((a) => (top ? a.removeAttribute("tabindex") : a.setAttribute("tabindex", "-1")));
    });
  }, [active]);

  function onKeyDown(e: React.KeyboardEvent) {
    if (e.key === "ArrowRight") { e.preventDefault(); next(); }
    if (e.key === "ArrowLeft") { e.preventDefault(); prev(); }
  }

  function onPointerDown(e: React.PointerEvent) {
    if (e.button !== 0) return;
    drag.current = { x: e.clientX, y: e.clientY, dx: 0, moved: false, id: e.pointerId };
  }
  function onPointerMove(e: React.PointerEvent) {
    const d = drag.current;
    if (!d || d.id !== e.pointerId) return;
    d.dx = e.clientX - d.x;
    if (!d.moved && Math.abs(d.dx) > 8 && Math.abs(d.dx) > Math.abs(e.clientY - d.y)) {
      d.moved = true;
      deck.current?.setPointerCapture(e.pointerId);
      deck.current?.setAttribute("data-dragging", "");
    }
    if (d.moved) deck.current?.style.setProperty("--drag", `${d.dx}px`);
  }
  function endDrag(e: React.PointerEvent) {
    const d = drag.current;
    drag.current = null;
    if (!d || d.id !== e.pointerId) return;
    deck.current?.removeAttribute("data-dragging");
    deck.current?.style.removeProperty("--drag");
    if (!d.moved) return;
    suppressClick.current = true;          // the click that follows a drag must not open the card
    if (d.dx <= -SWIPE) next(-1);
    else if (d.dx >= SWIPE) prev();
  }

  function onClickCapture(e: React.MouseEvent) {
    if (suppressClick.current) {
      suppressClick.current = false;
      e.preventDefault();
      e.stopPropagation();
      return;
    }
    // Clicking a card that is NOT on top brings it to the top instead of following its link.
    const wrapper = (e.target as Element).closest<HTMLElement>("[data-deck-card]");
    if (wrapper && wrapper.dataset.pos !== "0") {
      e.preventDefault();
      e.stopPropagation();
      show(Number(wrapper.dataset.index), -1);
    }
  }

  return (
    <div className="flex flex-col items-center">
      <div ref={deck} className="deck" tabIndex={0} role="region" aria-roledescription="card deck"
           aria-label="Featured stories — use the left and right arrow keys to shuffle"
           onKeyDown={onKeyDown} onPointerDown={onPointerDown} onPointerMove={onPointerMove}
           onPointerUp={endDrag} onPointerCancel={endDrag} onClickCapture={onClickCapture}
           onDragStart={(e) => e.preventDefault() /* no native link/image dragging: we handle swipes */}>
        {cards.map((card, i) => {
          const d = (i - active + n) % n;
          const isLeaving = leaving?.i === i;
          return (
            <div key={i} data-deck-card="" data-index={i} data-pos={d}
                 data-hidden={d >= VISIBLE && !isLeaving ? "" : undefined}
                 data-leaving={isLeaving ? "" : undefined}
                 aria-hidden={d !== 0}
                 className="deck-card"
                 style={{
                   "--d": d, "--f": fanSlot(d),
                   ...(isLeaving ? { "--fly": leaving!.dir < 0 ? "-120%" : "120%", "--fly-rot": leaving!.dir < 0 ? "-18deg" : "18deg" } : {}),
                 } as React.CSSProperties}>
              {card}
            </div>
          );
        })}
      </div>

      <div className="relative z-50 mt-12 flex items-center gap-5">
        <button type="button" className="icon-btn" onClick={prev} aria-label="Previous card">
          <svg width="16" height="16" viewBox="0 0 16 16" aria-hidden="true"><path d="M10 3 5 8l5 5" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" /></svg>
        </button>
        <p className="min-w-[4.5rem] text-center font-display text-sm font-semibold tabular-nums tracking-[.2em] text-ink" aria-hidden="true">
          {String(active + 1).padStart(2, "0")}<span className="text-muted"> / {String(n).padStart(2, "0")}</span>
        </p>
        <button type="button" className="icon-btn" onClick={() => next(-1)} aria-label="Next card">
          <svg width="16" height="16" viewBox="0 0 16 16" aria-hidden="true"><path d="M6 3l5 5-5 5" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" /></svg>
        </button>
      </div>
      <p className="mt-3 text-[.66rem] font-bold uppercase tracking-[.2em] text-muted" aria-hidden="true">
        <span className="hidden sm:inline">Hover to fan · ← → to shuffle</span><span className="sm:hidden">Swipe to shuffle</span>
      </p>
      <p className="sr-only" aria-live="polite">Card {active + 1} of {n}: {labels[active]}</p>
    </div>
  );
}
