import { notFound } from "next/navigation";
import type { Metadata } from "next";
import { listFeed, listPosts, listTrending } from "@/lib/api";
import { SECTIONS, sectionBySlug } from "@/lib/sections";
import { PostCard } from "@/components/PostCard";
import { LoadMore } from "@/components/LoadMore";
import { FeedList } from "@/components/FeedList";
import { TrendingStrip } from "@/components/TrendingStrip";

export const revalidate = 300;
/** Only /tech, /football and /ai exist. Anything else is a 404 without touching the backend. */
export const dynamicParams = false;

/** Build these three pages at build time (Static Site Generation). */
export function generateStaticParams() {
  return SECTIONS.map((s) => ({ section: s.slug }));
}

type Props = { params: Promise<{ section: string }> };

export async function generateMetadata({ params }: Props): Promise<Metadata> {
  const s = sectionBySlug((await params).section);
  return s ? { title: s.label, description: s.blurb } : {};
}

export default async function SectionPage({ params }: Props) {
  const s = sectionBySlug((await params).section);
  if (!s) notFound();
  const [page, feed, trending] = await Promise.all([listPosts(s.key, 10), listFeed(s.key, 6), listTrending(s.key)]);

  return (
    <>
      <header className="mb-8">
        <h1 className="text-3xl font-extrabold tracking-tight">{s.emoji} {s.label}</h1>
        <p className="mt-1 text-[var(--muted)]">{s.blurb}</p>
      </header>
      <TrendingStrip items={trending} />
      <h2 className="mb-3 text-sm font-semibold uppercase tracking-wide text-[var(--muted)]">From Ankur</h2>
      <div className="grid gap-4 sm:grid-cols-2">{page.items.map((p) => <PostCard key={p.id} post={p} />)}</div>
      {page.items.length === 0 && <p className="text-[var(--muted)]">No posts yet.</p>}
      {/* Only this small island ships JavaScript. */}
      <LoadMore section={s.key} initialCursor={page.nextCursor} />
      <FeedList items={feed.items} />
    </>
  );
}
