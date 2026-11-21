/**
 * Server-side data access for the website.
 *
 * Every fetch is tagged. When Ankur publishes a post, content-service (Phase 6) calls
 * POST /api/revalidate with tags like ["post:my-slug", "section:TECH", "home"], and Next.js
 * throws away exactly the cached data (and pages) that used those tags.
 */
import type { CursorPage, FeedItem, PostDetail, PostSummary, SectionCount, SectionKey, TrendingItem } from "./types";

/** Where the Next.js SERVER talks to the backend. Never exposed to the browser. */
const API = process.env.PULSE_API_URL ?? "http://localhost:8081";

/** Fallback freshness: even without an event, cached data is refreshed after 5 minutes. */
const REVALIDATE_SECONDS = 300;

async function getJson<T>(path: string, tags: string[]): Promise<T | null> {
  try {
    const res = await fetch(`${API}${path}`, {
      // `next.tags` + `next.revalidate` = put this response in Next's Data Cache.
      next: { tags, revalidate: REVALIDATE_SECONDS },
      // Never let a slow backend hold a page render forever.
      signal: AbortSignal.timeout(5000),
    });
    if (res.status === 404) return null;
    if (!res.ok) throw new Error(`${res.status} ${res.statusText} for ${path}`);
    return (await res.json()) as T;
  } catch (err) {
    console.error(`[api] ${path} failed:`, (err as Error).message);
    // During `next build`: degrade gracefully (empty block) so a backend outage can't break deploys.
    if (process.env.NEXT_PHASE === "phase-production-build") return null;
    // At runtime: THROW. If this was a background regeneration, Next.js keeps serving the last good
    // page instead of caching an empty one. (Found with the Phase 2 "break it" experiment.)
    throw err;
  }
}

export async function listPosts(section?: SectionKey, limit = 10): Promise<CursorPage<PostSummary>> {
  const q = new URLSearchParams({ limit: String(limit) });
  if (section) q.set("section", section);
  const tags = ["posts", section ? `section:${section}` : "home"];
  return (await getJson<CursorPage<PostSummary>>(`/api/v1/posts?${q}`, tags)) ?? { items: [], nextCursor: null };
}

export async function getPost(slug: string): Promise<PostDetail | null> {
  return getJson<PostDetail>(`/api/v1/posts/${encodeURIComponent(slug)}`, ["posts", `post:${slug}`]);
}

export async function listSections(): Promise<SectionCount[]> {
  return (await getJson<SectionCount[]>("/api/v1/sections", ["posts", "sections"])) ?? [];
}

/** Phase 3: top posts by views (Redis sorted set). Short cache: it changes constantly. */
export async function listTrending(section?: SectionKey): Promise<TrendingItem[]> {
  const q = section ? `?section=${section}&limit=5` : "?limit=5";
  return (await getJson<TrendingItem[]>(`/api/v1/trending${q}`, ["trending"])) ?? [];
}

/** Phase 7: curated external articles ("Around the web"). */
export async function listFeed(section?: SectionKey, limit = 6): Promise<CursorPage<FeedItem>> {
  const q = new URLSearchParams({ limit: String(limit) });
  if (section) q.set("section", section);
  return (await getJson<CursorPage<FeedItem>>(`/api/v1/feed?${q}`, ["feed", section ? `feed:${section}` : "feed:home"]))
    ?? { items: [], nextCursor: null };
}
