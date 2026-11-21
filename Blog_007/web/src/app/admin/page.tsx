"use client";
import Link from "next/link";
import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import { api, ApiError, type AdminPostRow, type Me } from "@/lib/adminApi";

const badge: Record<string, string> = {
  DRAFT: "bg-gray-500", SCHEDULED: "bg-amber-600", PUBLISHED: "bg-green-600", UNPUBLISHED: "bg-red-600",
};

export default function AdminHome() {
  const router = useRouter();
  const [me, setMe] = useState<Me | null>(null);
  const [posts, setPosts] = useState<AdminPostRow[]>([]);
  const [error, setError] = useState<string | null>(null);

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

  if (!me) return <p className="mt-10 text-[var(--muted)]">{error ?? "Loading…"}</p>;
  return (
    <div>
      <div className="mb-6 flex items-center gap-3">
        <h1 className="text-2xl font-bold">Posts</h1>
        <span className="text-sm text-[var(--muted)]">signed in as {me.displayName}</span>
        <Link href="/admin/posts/new" className="ml-auto rounded-lg bg-brand px-4 py-2 text-sm font-semibold text-white">New post</Link>
        <button onClick={logout} className="rounded-lg border border-[var(--line)] px-3 py-2 text-sm">Log out</button>
      </div>
      <table className="w-full text-sm">
        <thead><tr className="text-left text-[var(--muted)]"><th className="py-2">Title</th><th>Section</th><th>Status</th><th>Updated</th></tr></thead>
        <tbody>
          {posts.map((p) => (
            <tr key={p.id} className="border-t border-[var(--line)]">
              <td className="py-2"><Link className="font-semibold hover:text-brand" href={`/admin/posts/${p.id}`}>{p.title}</Link></td>
              <td>{p.section}</td>
              <td><span className={`rounded-full px-2 py-0.5 text-xs font-semibold text-white ${badge[p.status]}`}>{p.status}</span></td>
              <td className="text-[var(--muted)]">{new Date(p.updatedAt).toLocaleString()}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
