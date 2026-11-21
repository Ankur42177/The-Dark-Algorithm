import type { FeedItem } from "@/lib/types";
import { formatDate } from "@/lib/sections";

/**
 * "Around the web" (Phase 7): curated posts from famous bloggers.
 * Fair-use rules (PLAN §5): metadata + a short excerpt only, always link to the original.
 */
export function FeedList({ items }: { items: FeedItem[] }) {
  if (items.length === 0) return null;
  return (
    <div className="mt-6">
      <h3 className="mb-3 text-sm font-semibold uppercase tracking-wide text-[var(--muted)]">Around the web</h3>
      <ul className="divide-y divide-[var(--line)] rounded-2xl border border-[var(--line)] bg-[var(--card)]">
        {items.map((it) => (
          <li key={it.id} className="p-4">
            <a href={it.url} target="_blank" rel="noopener noreferrer nofollow" className="font-semibold hover:text-brand">
              {it.title}
            </a>
            {it.excerpt && <p className="mt-1 line-clamp-2 text-sm text-[var(--muted)]">{it.excerpt}</p>}
            <p className="mt-1 text-xs text-[var(--muted)]">
              {it.sourceName}{it.author ? ` · ${it.author}` : ""} · {formatDate(it.publishedAt)} ·{" "}
              <a href={it.url} target="_blank" rel="noopener noreferrer nofollow" className="font-semibold text-brand">Read on {it.sourceName} ↗</a>
            </p>
          </li>
        ))}
      </ul>
    </div>
  );
}
