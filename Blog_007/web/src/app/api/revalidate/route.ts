import { revalidateTag } from "next/cache";
import { timingSafeEqual } from "node:crypto";

/**
 * POST /api/revalidate   (called by content-service's Kafka consumer in Phase 6)
 * Header: x-revalidate-secret: <REVALIDATE_SECRET>
 * Body:   { "tags": ["post:my-slug", "section:TECH", "home"] }
 *
 * Two steps:
 *  1. revalidateTag(tag, "max")  — mark cached data stale. "max" = stale-while-revalidate: the next request
 *     still gets the OLD page while a fresh one is built in the background. If the backend is down, the
 *     rebuild fails and readers keep the last good page (no 500s, no cached empty pages).
 *  2. "Warm" the affected pages ourselves: request them once now, so the background rebuild happens
 *     immediately and real readers get the NEW page within milliseconds (target < 5 s, PLAN §4.3).
 */
export async function POST(request: Request) {
  const secret = process.env.REVALIDATE_SECRET ?? "";
  const given = request.headers.get("x-revalidate-secret") ?? "";
  // Constant-time comparison: doesn't leak how many characters matched (timing attack).
  const ok = secret.length > 0 && given.length === secret.length && timingSafeEqual(Buffer.from(given), Buffer.from(secret));
  if (!ok) return Response.json({ error: "unauthorized" }, { status: 401 });

  const body = (await request.json().catch(() => null)) as { tags?: unknown } | null;
  const tags = Array.isArray(body?.tags) ? body!.tags.filter((t): t is string => typeof t === "string" && t.length <= 256) : [];
  if (tags.length === 0 || tags.length > 50) return Response.json({ error: "tags must be 1..50 strings" }, { status: 400 });

  for (const tag of tags) revalidateTag(tag, "max");

  // Warm: two requests per page — the first triggers the background rebuild, the second waits for it.
  const origin = new URL(request.url).origin;
  const paths = [...new Set(tags.flatMap(pathsForTag))];
  const warmed = await Promise.allSettled(paths.map(async (p) => {
    for (let i = 0; i < 2; i++) {
      await fetch(origin + p, { cache: "no-store", signal: AbortSignal.timeout(5000) });
      await new Promise((r) => setTimeout(r, 50));
    }
    return p;
  }));
  return Response.json({
    revalidated: tags,
    warmed: warmed.filter((w) => w.status === "fulfilled").map((w) => (w as PromiseFulfilledResult<string>).value),
    at: new Date().toISOString(),
  });
}

/** Which page URLs depend on a tag. Keep in sync with the tags used in src/lib/api.ts. */
function pathsForTag(tag: string): string[] {
  if (tag === "home" || tag === "trending" || tag === "feed:home") return ["/"];
  if (tag.startsWith("section:") || tag.startsWith("feed:")) {
    const s = tag.split(":")[1].toLowerCase();
    return ["/", `/${s}`];
  }
  if (tag.startsWith("post:")) return [`/posts/${encodeURIComponent(tag.slice(5))}`];
  return [];
}
