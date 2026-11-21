import Link from "next/link";
import type { TrendingItem } from "@/lib/types";

/** "Trending" (Phase 3): top posts by views from a Redis sorted set. Hidden when empty. */
export function TrendingStrip({ items }: { items: TrendingItem[] }) {
  if (items.length === 0) return null;
  return (
    <div className="mb-10 rounded-2xl border border-[var(--line)] bg-[var(--card)] p-4">
      <h2 className="mb-2 text-sm font-semibold uppercase tracking-wide text-[var(--muted)]">🔥 Trending</h2>
      <ol className="flex flex-wrap gap-x-6 gap-y-2 text-sm">
        {items.map((t, i) => (
          <li key={t.slug}>
            <span className="mr-1 font-bold text-brand">{i + 1}.</span>
            <Link href={`/posts/${t.slug}`} className="hover:text-brand">{t.title}</Link>
          </li>
        ))}
      </ol>
    </div>
  );
}
