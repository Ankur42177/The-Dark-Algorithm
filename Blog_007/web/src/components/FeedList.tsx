import type { FeedItem } from "@/lib/types";
import { formatDate } from "@/lib/sections";
import { initials } from "./WebCard";

/** A deterministic hue per source, so "Cloudflare Blog" always gets the same little badge colour. */
function hue(name: string): number {
  let h = 0;
  for (let i = 0; i < name.length; i++) h = (h * 31 + name.charCodeAt(i)) % 360;
  return h;
}

/**
 * "Around the web" (Phase 7): curated reads from official engineering blogs, filtered by ingestion-service.
 * Fair-use rules (PLAN §5): metadata + a short excerpt only, always link to the original.
 */
export function FeedList({ items, title = "Around the web", sub }: { items: FeedItem[]; title?: string; sub?: string }) {
  if (items.length === 0) return null;
  return (
    <div className="mt-20">
      <div className="reveal mb-7 flex flex-wrap items-end gap-x-6 gap-y-2">
        <div>
          <p className="kicker">Around the web</p>
          <h3 className="mt-2 font-display text-2xl font-medium tracking-tight text-ink sm:text-[1.9rem]">{title}</h3>
        </div>
        {sub && <p className="max-w-md text-sm leading-relaxed text-muted sm:ml-auto sm:text-right">{sub}</p>}
      </div>
      <ul className="reveal-stagger grid gap-5 md:grid-cols-2 lg:grid-cols-3">
        {items.map((it) => (
          <li key={it.id}
              className="group relative flex flex-col rounded-3xl border border-line bg-card p-6 shadow-[0_1px_2px_rgb(20_33_61/.05)] transition duration-300 hover:-translate-y-1 hover:border-accent hover:shadow-[0_24px_48px_-28px_rgb(20_33_61/.5)] motion-reduce:hover:translate-y-0">
            <p className="flex items-center gap-3">
              <span aria-hidden="true" className="grid h-9 w-9 shrink-0 place-items-center rounded-xl font-display text-[.72rem] font-bold text-white"
                    style={{ background: `linear-gradient(135deg, hsl(${hue(it.sourceName)} 45% 38%), hsl(${(hue(it.sourceName) + 40) % 360} 55% 30%))` }}>
                {initials(it.sourceName)}
              </span>
              <span className="truncate text-[.68rem] font-bold uppercase tracking-[.16em] text-accent">{it.sourceName}</span>
              <span aria-hidden="true" className="ml-auto text-muted transition duration-300 group-hover:-translate-y-0.5 group-hover:translate-x-0.5 group-hover:text-accent">↗</span>
            </p>
            <a href={it.url} target="_blank" rel="noopener noreferrer nofollow"
               className="mt-4 block font-display text-[1.08rem] font-medium leading-snug text-ink after:absolute after:inset-0 after:rounded-3xl group-hover:text-accent">
              {it.title}
              <span className="sr-only"> (opens {it.sourceName} in a new tab)</span>
            </a>
            {it.excerpt && <p className="mt-2 line-clamp-3 text-sm leading-relaxed text-muted">{it.excerpt}</p>}
            <p className="mt-auto pt-4 text-xs text-muted">{it.author ? `${it.author} · ` : ""}{formatDate(it.publishedAt)}</p>
          </li>
        ))}
      </ul>
    </div>
  );
}
