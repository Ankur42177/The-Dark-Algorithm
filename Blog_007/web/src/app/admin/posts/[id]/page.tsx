"use client";
/**
 * The Markdown editor. /admin/posts/new creates a draft; /admin/posts/{id} edits one.
 * Live preview calls the backend, so you see EXACTLY the sanitized HTML readers will get.
 */
import { use, useEffect, useMemo, useRef, useState } from "react";
import { useRouter } from "next/navigation";
import { api, ApiError, type AdminPost } from "@/lib/adminApi";

type Form = { title: string; slug: string; summary: string; bodyMd: string; section: string; tags: string; coverImageUrl: string };
const empty: Form = { title: "", slug: "", summary: "", bodyMd: "# New post\n\nWrite **Markdown** here.", section: "TECH", tags: "", coverImageUrl: "" };

export default function EditorPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = use(params);                 // React 19: unwrap the params Promise in a client component
  const isNew = id === "new";
  const router = useRouter();
  const [form, setForm] = useState<Form>(empty);
  const [post, setPost] = useState<AdminPost | null>(null);
  const [preview, setPreview] = useState("");
  const [message, setMessage] = useState<{ kind: "ok" | "error"; text: string } | null>(null);
  const [scheduleAt, setScheduleAt] = useState("");
  // One Idempotency-Key per "create" form: a double click or retry can't create two drafts.
  const idemKey = useMemo(() => crypto.randomUUID(), []);
  const previewTimer = useRef<ReturnType<typeof setTimeout> | null>(null);

  useEffect(() => {
    if (isNew) return;
    api<AdminPost>("GET", `/api/v1/admin/posts/${id}`).then((p) => {
      setPost(p);
      setForm({ title: p.title, slug: p.slug, summary: p.summary ?? "", bodyMd: p.bodyMd, section: p.section,
        tags: p.tags.join(", "), coverImageUrl: p.coverImageUrl ?? "" });
    }).catch((e) => (e instanceof ApiError && e.status === 401 ? router.replace("/admin/login") : setMessage({ kind: "error", text: e.message })));
  }, [id, isNew, router]);

  // Debounced live preview: wait 400 ms after the last keystroke before calling the server.
  useEffect(() => {
    if (previewTimer.current) clearTimeout(previewTimer.current);
    previewTimer.current = setTimeout(() => {
      api<{ html: string }>("POST", "/api/v1/admin/preview", { bodyMd: form.bodyMd })
        .then((r) => setPreview(r.html)).catch(() => undefined);
    }, 400);
  }, [form.bodyMd]);

  const body = () => ({
    title: form.title, slug: isNew && form.slug ? form.slug : undefined, summary: form.summary || null, bodyMd: form.bodyMd,
    section: form.section, tags: form.tags.split(",").map((t) => t.trim().toLowerCase()).filter(Boolean),
    coverImageUrl: form.coverImageUrl || null, version: post?.version,
  });

  async function run(action: () => Promise<AdminPost | void>, ok: string) {
    setMessage(null);
    try {
      const p = await action();
      if (p) setPost(p);
      setMessage({ kind: "ok", text: ok });
      return p;
    } catch (e) {
      const text = e instanceof ApiError && e.status === 409
        ? `${e.message}` : (e as Error).message;
      setMessage({ kind: "error", text });
    }
  }

  const save = () => run(async () => {
    if (isNew) {
      const p = await api<AdminPost>("POST", "/api/v1/admin/posts", body(), { "Idempotency-Key": idemKey });
      router.replace(`/admin/posts/${p.id}`);
      return p;
    }
    return api<AdminPost>("PUT", `/api/v1/admin/posts/${id}`, body());
  }, "Saved");

  const field = "field mt-1.5";
  const set = (k: keyof Form) => (e: React.ChangeEvent<HTMLInputElement | HTMLTextAreaElement | HTMLSelectElement>) =>
    setForm((f) => ({ ...f, [k]: e.target.value }));

  return (
    <div>
      <div className="mb-4 flex flex-wrap items-center gap-2">
        <button onClick={() => router.push("/admin")} className="btn-sm">← Posts</button>
        <h1 className="mr-auto font-display text-3xl font-medium tracking-tight text-ink">{isNew ? "New post" : "Edit post"}</h1>
        {post && <span className="badge badge-gray">{post.status} · v{post.version}</span>}
        <button onClick={save} className="btn-sm primary">Save</button>
        {post && post.status !== "PUBLISHED" && (
          <button onClick={() => run(() => api("POST", `/api/v1/admin/posts/${id}/publish`), "Published — live on the site")}
            className="btn-sm success">Publish</button>
        )}
        {post && (post.status === "PUBLISHED" || post.status === "SCHEDULED") && (
          <button onClick={() => run(() => api("POST", `/api/v1/admin/posts/${id}/unpublish`), "Unpublished")}
            className="btn-sm danger">Unpublish</button>
        )}
      </div>
      {message && (
        <p role="status" className={`mb-4 rounded-2xl p-3 text-sm ${message.kind === "ok" ? "bg-green-600/15 text-ink" : "bg-red-600/15 text-hot"}`}>
          {message.text}
          {message.kind === "error" && message.text.includes("changed by someone else") && (
            <button onClick={() => location.reload()} className="ml-2 underline">Reload</button>
          )}
        </p>
      )}
      <div className="grid gap-6 lg:grid-cols-2">
        <div className="admin-card space-y-4 text-sm font-semibold text-ink">
          <label className="block text-sm">Title<input className={field} value={form.title} onChange={set("title")} /></label>
          {isNew && <label className="block text-sm">Slug (optional)<input className={field} value={form.slug} onChange={set("slug")} placeholder="derived from the title" /></label>}
          <div className="flex gap-3">
            <label className="block flex-1 text-sm">Section
              <select className={field} value={form.section} onChange={set("section")}>
                <option value="TECH">Code &amp; Cortex</option><option value="FOOTBALL">Football</option>
              </select>
            </label>
            <label className="block flex-1 text-sm">Tags (comma separated)<input className={field} value={form.tags} onChange={set("tags")} /></label>
          </div>
          <label className="block text-sm">Summary (SEO description)<input className={field} value={form.summary} onChange={set("summary")} /></label>
          <label className="block text-sm">Cover image URL (https)<input className={field} value={form.coverImageUrl} onChange={set("coverImageUrl")} /></label>
          <label className="block text-sm">Markdown
            <textarea className={`${field} h-96 font-mono text-sm`} value={form.bodyMd} onChange={set("bodyMd")} />
          </label>
          {post && post.status !== "PUBLISHED" && (
            <div className="flex items-end gap-2 text-sm">
              <label className="block">Schedule for<input type="datetime-local" className={field} value={scheduleAt} onChange={(e) => setScheduleAt(e.target.value)} /></label>
              <button disabled={!scheduleAt} className="btn-sm"
                onClick={() => run(() => api("POST", `/api/v1/admin/posts/${id}/schedule`, { publishAt: new Date(scheduleAt).toISOString() }), "Scheduled")}>
                Schedule
              </button>
            </div>
          )}
        </div>
        <div>
          <p className="kicker mb-2">Live preview · sanitized by the server</p>
          <div className="prose-pulse admin-card min-h-96"
            dangerouslySetInnerHTML={{ __html: preview }} />
        </div>
      </div>
    </div>
  );
}
