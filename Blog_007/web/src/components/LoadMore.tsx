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
      const res = await fetch(`${API}/api/v1/posts?section=${section}&limit=9&cursor=${encodeURIComponent(cursor)}`);
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      const page = (await res.json()) as CursorPage<PostSummary>;
      setPosts((p) => [...p, ...page.items]);
      setCursor(page.nextCursor);
    } catch (e) {
      setError("The dealer dropped the deck. Try again?");
      console.error(e);
    } finally {
      setLoading(false);
    }
  }

  return (
    <>
      {posts.length > 0 && (
        <div className="mt-8 grid gap-8 sm:grid-cols-2 lg:grid-cols-3">
          {posts.map((p) => <PostCard key={p.id} post={p} />)}
        </div>
      )}
      <div className="mt-12 text-center">
        {cursor && (
          <button onClick={load} disabled={loading} className="btn-pill disabled:opacity-50">
            {loading ? "Shuffling…" : <>Deal me more cards <span className="arrow">↓</span></>}
          </button>
        )}
        {error && <p className="mt-2 text-sm text-hot">{error}</p>}
      </div>
    </>
  );
}
