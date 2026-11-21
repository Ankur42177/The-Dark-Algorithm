"use client";
/**
 * A CLIENT Component ("use client" above): this is the only interactive part of a section page,
 * so it's the only part that ships JavaScript. It fetches the next page straight from the API
 * using the keyset cursor (Phase 1) — the server-rendered first page stays static and cacheable.
 */
import { useState } from "react";
import type { CursorPage, PostSummary, SectionKey } from "@/lib/types";
import { PostCard } from "./PostCard";

const API = process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8081";

export function LoadMore({ section, initialCursor }: { section: SectionKey; initialCursor: string | null }) {
  const [posts, setPosts] = useState<PostSummary[]>([]);
  const [cursor, setCursor] = useState(initialCursor);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  if (!cursor && posts.length === 0) return null;

  async function load() {
    if (!cursor) return;
    setLoading(true);
    setError(null);
    try {
      const res = await fetch(`${API}/api/v1/posts?section=${section}&limit=10&cursor=${encodeURIComponent(cursor)}`);
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      const page = (await res.json()) as CursorPage<PostSummary>;
      setPosts((p) => [...p, ...page.items]);
      setCursor(page.nextCursor);
    } catch (e) {
      setError("Couldn't load more posts. Try again.");
      console.error(e);
    } finally {
      setLoading(false);
    }
  }

  return (
    <>
      {posts.length > 0 && (
        <div className="mt-4 grid gap-4 sm:grid-cols-2">
          {posts.map((p) => <PostCard key={p.id} post={p} />)}
        </div>
      )}
      <div className="mt-6 text-center">
        {cursor && (
          <button onClick={load} disabled={loading}
            className="rounded-full border border-brand px-5 py-2 text-sm font-semibold text-brand hover:bg-brand hover:text-white disabled:opacity-50">
            {loading ? "Loading…" : "Load more"}
          </button>
        )}
        {error && <p className="mt-2 text-sm text-red-500">{error}</p>}
      </div>
    </>
  );
}
