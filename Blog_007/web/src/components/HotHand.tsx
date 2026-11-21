import Link from "next/link";
import type { SectionKey } from "@/lib/types";
import { sectionByKey } from "@/lib/sections";
import { Suit } from "./Suit";
import { Cover } from "./Cover";
import { HotHandFx } from "./HotHandFx";

export interface HotCard {
  slug: string;
  title: string;
  section: SectionKey;
  views: number;
  coverImageUrl: string | null;
  readingMinutes: number | null;
}

/** Centre-out seating: #1 in the middle (front), #2 right, #3 left, #4 far right, #5 far left. */
function seat(rank: number): number {
  if (rank === 0) return 0;
  return rank % 2 === 1 ? (rank + 1) / 2 : -rank / 2;
}

/** 1234 → "1.2k" */
function compact(n: number): string {
  return n >= 1000 ? `${(n / 1000).toFixed(n >= 10_000 ? 0 : 1)}k` : String(n);
}

/** A tiny flame "suit" for the rank index. */
function Flame() {
  return (
    <svg viewBox="0 0 16 16" aria-hidden="true" className="hot-flame">
      <path d="M8.4 1.2c.4 2.3-.9 3.4-2 4.6C5.3 7 4.4 8.2 4.4 10a3.6 3.6 0 0 0 7.2 0c0-1.3-.5-2.2-1.1-3 .1 1-.3 1.8-1 2.1.4-2.6-.2-5.6-1.1-7.9Z" fill="currentColor" />
    </svg>
  );
}

/**
 * "Hot right now" as a HAND OF PLAYING CARDS (Phase 3 trending data, from a Redis sorted set).
 *
 * The fan, the spread-on-hover and the lift of the hovered card are pure CSS (globals.css → .hot-*):
 * each card gets its rank (--i) and its seat in the hand (--d, centre-out so #1 is front and centre);
 * rotation and the arc are computed from them. On phones the hand becomes a swipeable row.
 * HotHandFx (a ~1 KB island) only adds the ‹ › spotlight cycle (keyboard friendly) and the "Shuffle" re-deal.
 */
export function HotHand({ cards, label = "Hot right now", sub = "The five most-read cards this week. Hover the hand, or shuffle it." }: {
  cards: HotCard[]; label?: string; sub?: string;
}) {
  if (cards.length === 0) return null;
  const max = Math.max(...cards.map((c) => c.views), 1);

  return (
    <section className="hot-band" aria-labelledby="hot-title">
      <div className="mx-auto grid max-w-6xl grid-cols-[minmax(0,1fr)] items-center gap-10 px-6 py-16 lg:grid-cols-[minmax(0,.8fr)_minmax(0,1.6fr)]">
        <div className="reveal">
          <h2 id="hot-title" className="flex items-center gap-2.5 text-[.72rem] font-bold uppercase tracking-[.24em] text-hot">
            <span className="relative flex h-2.5 w-2.5" aria-hidden="true">
              <span className="absolute inline-flex h-full w-full animate-ping rounded-full bg-[var(--color-coral)] opacity-60 motion-reduce:animate-none" />
              <span className="relative inline-flex h-2.5 w-2.5 rounded-full bg-[var(--color-coral)]" />
            </span>
            {label}
          </h2>
          <p className="mt-4 font-display text-[2rem] font-medium leading-[1.1] tracking-tight text-ink sm:text-[2.4rem]">
            What everyone&apos;s <span className="hl">reading</span>
          </p>
          <p className="mt-3 max-w-sm text-[.98rem] leading-relaxed text-muted">{sub}</p>
          <ol className="mt-6 hidden flex-col gap-2 text-sm lg:flex" aria-label="Ranking">
            {cards.map((c, i) => (
              <li key={c.slug} className="flex items-baseline gap-3">
                <span className="w-6 font-display font-semibold tabular-nums text-muted">{String(i + 1).padStart(2, "0")}</span>
                <span className="truncate text-fg">{c.title}</span>
                <span className="ml-auto shrink-0 tabular-nums text-muted">{compact(c.views)} reads</span>
              </li>
            ))}
          </ol>
        </div>

        <HotHandFx labels={cards.map((c) => c.title)}>
        <ol className="hot-hand" style={{ "--n": cards.length } as React.CSSProperties} aria-label="Most-read posts, ranked">
          {cards.map((c, i) => {
            const s = sectionByKey(c.section);
            const heat = Math.max(8, Math.round((c.views / max) * 100));
            const d = seat(i);
            return (
              <li key={c.slug} data-rank={i} className={`hot-card ${i === 0 ? "is-top" : ""}`}
                  style={{ "--i": i, "--d": d, "--d2": d * d, "--z": 20 - i * 2, "--heat": `${heat}%` } as React.CSSProperties}>
                <div className="hot-card-face">
                  <div className="hot-card-media" aria-hidden="true">
                    <Cover src={c.coverImageUrl} alt="" section={s.key} label={s.monogram} labelClassName="-translate-y-1/3 text-4xl opacity-70" />
                  </div>
                  <span className="hot-rank tl" aria-hidden="true">{i + 1}<Flame /></span>
                  <span className="hot-rank br" aria-hidden="true">{i + 1}<Flame /></span>
                  <div className="hot-card-panel">
                    <p className="hot-card-kicker"><Suit kind={s.key} /> {s.label}{c.readingMinutes ? ` · ${c.readingMinutes} min` : ""}</p>
                    <Link href={`/posts/${c.slug}`} className="hot-card-title after:absolute after:inset-0 after:z-10 after:content-['']">
                      {c.title}
                    </Link>
                    <div className="hot-heat" aria-label={`${c.views} reads`}>
                      <span className="hot-heat-bar" />
                      <span className="hot-heat-num">{compact(c.views)}</span>
                    </div>
                  </div>
                </div>
              </li>
            );
          })}
        </ol>
        </HotHandFx>
      </div>
    </section>
  );
}
