import { getPost, listTrending } from "./api";
import type { SectionKey } from "./types";
import type { HotCard } from "@/components/HotHand";

/**
 * Trending (Redis sorted set: slug + views) enriched with each post's cover and reading time, for the
 * "Hot right now" hand of cards. Up to 5 getPost calls, in parallel, each cached by Next's data cache
 * (tag post:{slug}), so this costs nothing per reader once the page is built.
 */
export async function hotCards(section?: SectionKey): Promise<HotCard[]> {
  const trending = await listTrending(section);
  const posts = await Promise.all(trending.slice(0, 5).map((t) => getPost(t.slug).catch(() => null)));
  return trending.slice(0, 5).map((t, i) => ({
    slug: t.slug,
    title: t.title,
    section: (t.section === "AI" ? "TECH" : t.section) as SectionKey,
    views: t.views,
    coverImageUrl: posts[i]?.coverImageUrl ?? null,
    readingMinutes: posts[i]?.readingMinutes ?? null,
  }));
}
