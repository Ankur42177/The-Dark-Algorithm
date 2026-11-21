import Link from "next/link";
import { listFeed, listPosts, listTrending } from "@/lib/api";
import { SECTIONS } from "@/lib/sections";
import { PostCard } from "@/components/PostCard";
import { FeedList } from "@/components/FeedList";
import { TrendingStrip } from "@/components/TrendingStrip";

/**
 * Home page. Rendered on the server and cached as static HTML (Incremental Static Regeneration):
 * readers get pre-built HTML; it is refreshed when a tagged fetch is revalidated or after 5 min.
 */
export const revalidate = 300;

export default async function Home() {
  // Fetch all sections IN PARALLEL (Promise.all), not one after another.
  const [sections, trending] = await Promise.all([
    Promise.all(SECTIONS.map(async (s) => ({ s, posts: await listPosts(s.key, 3), feed: await listFeed(s.key, 3) }))),
    listTrending(),
  ]);

  return (
    <>
      <section className="mb-10 rounded-3xl bg-gradient-to-br from-brand to-brand-2 p-8 text-white">
        <h1 className="text-3xl font-extrabold tracking-tight sm:text-4xl">Tech · Football · AI</h1>
        <p className="mt-2 max-w-xl text-white/90">
          Notes from Ankur Kumar, plus the best posts from engineers, analysts and researchers around the web.
        </p>
      </section>

      <TrendingStrip items={trending} />

      {sections.map(({ s, posts, feed }) => (
        <section key={s.slug} className="mb-12">
          <div className="mb-4 flex items-baseline justify-between">
            <h2 className="text-2xl font-bold">{s.emoji} {s.label}</h2>
            <Link href={`/${s.slug}`} className="text-sm font-semibold text-brand">All {s.label} →</Link>
          </div>
          <h3 className="mb-3 text-sm font-semibold uppercase tracking-wide text-[var(--muted)]">From Ankur</h3>
          {posts.items.length === 0 ? (
            <p className="text-[var(--muted)]">No posts yet.</p>
          ) : (
            <div className="grid gap-4 md:grid-cols-3">{posts.items.map((p) => <PostCard key={p.id} post={p} />)}</div>
          )}
          <FeedList items={feed.items} />
        </section>
      ))}
    </>
  );
}
