"use client";
import Link from "next/link";
import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import { api, ApiError, type AdminPostRow, type Me } from "@/lib/adminApi";
import { sectionByKey } from "@/lib/sections";
import type { SectionKey } from "@/lib/types";

const badge: Record<string, string> = {
  DRAFT: "badge-gray", SCHEDULED: "badge-amber", PUBLISHED: "badge-green", UNPUBLISHED: "badge-red",
};

export default function AdminHome() {
  const router = useRouter();
  const [me, setMe] = useState<Me | null>(null);
  const [posts, setPosts] = useState<AdminPostRow[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [filter, setFilter] = useState("");

  useEffect(() => {
    (async () => {
      try {
        setMe(await api<Me>("GET", "/api/v1/auth/me"));
        setPosts(await api<AdminPostRow[]>("GET", "/api/v1/admin/posts"));
      } catch (e) {
        if (e instanceof ApiError && e.status === 401) router.replace("/admin/login");
        else setError((e as Error).message);
      }
    })();
  }, [router]);

  async function logout() {
    await api("POST", "/api/v1/auth/logout").catch(() => undefined);
    router.replace("/admin/login");
  }

  if (!me) {
    return error
      ? <p role="alert" className="admin-card text-hot">Couldn&apos;t load the dashboard: {error}</p>
      : <div className="admin-card animate-pulse text-muted">Loading the back office…</div>;
  }
  const shown = posts.filter((p) => p.title.toLowerCase().includes(filter.toLowerCase()));
  const count = (s: string) => posts.filter((p) => p.status === s).length;

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-end gap-3">
        <div className="mr-auto">
          <h1 className="font-display text-3xl font-medium tracking-tight text-ink">Posts</h1>
          <p className="mt-1 text-sm text-muted">Signed in as {me.displayName}</p>
        </div>
        <Link href="/admin/posts/new" className="btn-pill !py-2.5">New post <span className="arrow">→</span></Link>
        <button onClick={logout} className="btn-sm">Log out</button>
      </div>

      <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
        {(["PUBLISHED", "DRAFT", "SCHEDULED", "UNPUBLISHED"] as const).map((s) => (
          <div key={s} className="admin-card !p-4">
            <p className="text-[.64rem] font-bold uppercase tracking-[.18em] text-muted">{s.toLowerCase()}</p>
            <p className="mt-1 font-display text-3xl font-semibold text-ink">{count(s)}</p>
          </div>
        ))}
      </div>

      <div className="admin-card !p-0">
        <div className="flex items-center gap-3 border-b border-line p-4">
          <input className="field !py-2 max-w-xs" placeholder="Filter by title…" value={filter} onChange={(e) => setFilter(e.target.value)} aria-label="Filter posts by title" />
          <span className="ml-auto text-xs text-muted">{shown.length} of {posts.length}</span>
        </div>
        <div className="overflow-x-auto">
          <table className="admin-table">
            <thead><tr><th>Title</th><th>Section</th><th>Status</th><th>Updated</th></tr></thead>
            <tbody>
              {shown.map((p) => (
                <tr key={p.id}>
                  <td><Link className="font-semibold text-ink hover:text-accent" href={`/admin/posts/${p.id}`}>{p.title}</Link></td>
                  <td className="whitespace-nowrap">{sectionByKey(p.section as SectionKey).label}</td>
                  <td><span className={`badge ${badge[p.status]}`}>{p.status}</span></td>
                  <td className="whitespace-nowrap text-muted">{new Date(p.updatedAt).toLocaleString()}</td>
                </tr>
              ))}
              {shown.length === 0 && <tr><td colSpan={4} className="py-10 text-center text-muted">No posts match.</td></tr>}
            </tbody>
          </table>
        </div>
      </div>
    </div>
  );
}
