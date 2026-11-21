"use client";
/** Admin → Inbox: messages from the contact form, with "mark handled". */
import { useCallback, useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import { api, ApiError, isNotDeployed, type ContactMessage } from "@/lib/adminApi";
import { NotAvailable } from "@/components/admin/AdminTabs";

export default function ContactInbox() {
  const router = useRouter();
  const [messages, setMessages] = useState<ContactMessage[]>([]);
  const [state, setState] = useState<"loading" | "ready" | "unavailable" | "error">("loading");
  const [note, setNote] = useState<string | null>(null);
  const [showHandled, setShowHandled] = useState(false);

  const load = useCallback(async () => {
    try {
      setMessages(await api<ContactMessage[]>("GET", "/api/v1/admin/contact"));   // 100 newest
      setState("ready");
    } catch (e) {
      if (e instanceof ApiError && e.status === 401) return router.replace("/admin/login");
      if (isNotDeployed(e)) return setState("unavailable");
      setNote((e as Error).message);
      setState("error");
    }
  }, [router]);

  useEffect(() => { load(); }, [load]);

  async function markHandled(id: string) {
    setNote(null);
    try {
      const updated = await api<ContactMessage>("POST", `/api/v1/admin/contact/${id}/handled`);
      setMessages((all) => all.map((m) => (m.id === id ? updated : m)));
    } catch (e) {
      if (e instanceof ApiError && e.status === 401) return router.replace("/admin/login");
      setNote(e instanceof ApiError && e.status === 404 ? "That message no longer exists — refreshing." : `Couldn't mark it handled: ${(e as Error).message}`);
      if (e instanceof ApiError && e.status === 404) await load();
    }
  }

  const open = messages.filter((m) => !m.handledAt);
  const shown = showHandled ? messages : open;

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-end gap-3">
        <div className="mr-auto">
          <h1 className="font-display text-3xl font-medium tracking-tight text-ink">Inbox</h1>
          <p className="mt-1 text-sm text-muted">Messages from the contact form (100 newest){state === "ready" ? ` · ${open.length} waiting` : ""}.</p>
        </div>
        {state === "ready" && (
          <>
            <label className="flex items-center gap-2 text-sm text-muted">
              <input type="checkbox" checked={showHandled} onChange={(e) => setShowHandled(e.target.checked)} className="accent-[var(--color-brand)]" /> Show handled
            </label>
            <button onClick={load} className="btn-sm">↻ Refresh</button>
          </>
        )}
      </div>
      {note && <p role="status" className="admin-card !py-3 text-sm">{note}</p>}
      {state === "loading" && <div className="admin-card animate-pulse text-muted">Opening the post bag…</div>}
      {state === "unavailable" && <NotAvailable what="The contact inbox" detail="The contact service didn't answer. Check that content-service and the gateway are running, then refresh." />}
      {state === "error" && <p role="alert" className="admin-card text-hot">Couldn&apos;t load messages.</p>}
      {state === "ready" && (
        <ul className="space-y-4">
          {shown.map((m) => (
            <li key={m.id} className={`admin-card ${m.handledAt ? "opacity-60" : ""}`}>
              <div className="flex flex-wrap items-baseline gap-x-3 gap-y-1">
                <p className="font-display text-lg font-medium text-ink">{m.name}</p>
                <a href={`mailto:${m.email}`} className="text-sm text-accent hover:underline">{m.email}</a>
                <span className="ml-auto text-xs text-muted">{new Date(m.createdAt).toLocaleString()}</span>
              </div>
              <p className="mt-3 whitespace-pre-wrap text-sm leading-relaxed text-fg">{m.message}</p>
              <div className="mt-4 flex gap-2">
                <a href={`mailto:${m.email}?subject=${encodeURIComponent("Re: your message to Pulse")}`} className="btn-sm">Reply ↗</a>
                {m.handledAt ? <span className="badge badge-green self-center">Handled {new Date(m.handledAt).toLocaleDateString()}</span>
                  : <button onClick={() => markHandled(m.id)} className="btn-sm primary">Mark handled</button>}
              </div>
            </li>
          ))}
          {shown.length === 0 && <li className="admin-card py-12 text-center text-muted">Inbox zero. Enjoy the quiet. ☕</li>}
        </ul>
      )}
    </div>
  );
}
