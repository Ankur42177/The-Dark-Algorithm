"use client";
import Link from "next/link";
import { useCallback, useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import { api, ApiError, type DeadLetter, type FeedSource, type FetchRun, type Section } from "@/lib/adminApi";

/**
 * Admin → Ingestion (Phase 7). Three views over ingestion-service (via the gateway):
 *  - Sources: pause/resume, "fetch now", add a feed (the server SSRF-checks the URL before saving).
 *  - Recent runs: the fetch_runs audit trail (what happened on each attempt).
 *  - Dead letters: jobs that failed 5 times or permanently; "Requeue" gives one a fresh start.
 * A client component: the admin bundle is never downloaded by public readers (separate route segment).
 */
const runBadge: Record<FetchRun["status"], string> = {
  OK: "badge-green", NOT_MODIFIED: "badge-gray", SKIPPED: "badge-amber", FAILED: "badge-red", RUNNING: "badge-blue",
};
const SECTION_LABEL: Record<string, string> = { TECH: "Code & Cortex", AI: "Code & Cortex", FOOTBALL: "Football" };

const when = (iso: string | null) => (iso ? new Date(iso).toLocaleString() : "—");

export default function IngestionAdmin() {
  const router = useRouter();
  const [sources, setSources] = useState<FeedSource[]>([]);
  const [runs, setRuns] = useState<FetchRun[]>([]);
  const [dlq, setDlq] = useState<DeadLetter[]>([]);
  const [message, setMessage] = useState<string | null>(null);
  const [loaded, setLoaded] = useState(false);

  const load = useCallback(async () => {
    try {
      const [s, r, d] = await Promise.all([
        api<FeedSource[]>("GET", "/api/v1/admin/sources"),
        api<FetchRun[]>("GET", "/api/v1/admin/ingestion/runs"),
        api<DeadLetter[]>("GET", "/api/v1/admin/ingestion/dlq"),
      ]);
      setSources(s);
      setRuns(r);
      setDlq(d);
      setLoaded(true);
    } catch (e) {
      if (e instanceof ApiError && e.status === 401) router.replace("/admin/login");
      else setMessage((e as Error).message);
    }
  }, [router]);

  useEffect(() => {
    load();
  }, [load]);

  /** Run an admin action, show its outcome, then refresh all three tables. */
  async function act(label: string, fn: () => Promise<unknown>): Promise<boolean> {
    setMessage(null);
    let ok = false;
    try {
      await fn();
      setMessage(`${label}: done`);
      ok = true;
    } catch (e) {
      setMessage(`${label}: ${(e as Error).message}`);   // e.g. 409 "source is paused", 400 "Blocked port"
    }
    await load();
    return ok;
  }

  if (!loaded) return <div className="admin-card animate-pulse text-muted">{message ?? "Loading ingestion…"}</div>;
  return (
    <div className="space-y-10">
      <div className="flex items-end gap-3">
        <div className="mr-auto">
          <h1 className="font-display text-3xl font-medium tracking-tight text-ink">Ingestion</h1>
          <p className="mt-1 text-sm text-muted">Feeds, fetch runs and dead letters. <Link href="/admin" className="text-accent hover:underline">← Posts</Link></p>
        </div>
        <button onClick={load} className="btn-sm">↻ Refresh</button>
      </div>
      {message && <p role="status" className="admin-card !py-3 text-sm">{message}</p>}

      <section>
        <h2 className="mb-3 font-display text-xl font-medium text-ink">Sources <span className="text-muted">({sources.length})</span></h2>
        <div className="admin-card overflow-x-auto !p-0">
        <table className="admin-table">
          <thead><tr><th>Name</th><th>Section</th><th>Every</th><th>Last fetch</th><th>Failures</th><th /></tr></thead>
          <tbody>
            {sources.map((s) => (
              <tr key={s.id} className={s.active ? "" : "opacity-60"}>
                <td>
                  <a className="font-semibold text-ink hover:text-accent" href={s.feedUrl} target="_blank" rel="noopener noreferrer">{s.name}</a>
                  {s.disabledReason && <div className="text-xs text-muted">{s.disabledReason}</div>}
                </td>
                <td className="whitespace-nowrap">{SECTION_LABEL[s.section] ?? s.section}</td>
                <td>{s.fetchIntervalMin} min</td>
                <td className="whitespace-nowrap text-muted">{when(s.lastFetchedAt)}</td>
                <td>{s.consecutiveFailures}</td>
                <td className="space-x-2 whitespace-nowrap text-right">
                  <button disabled={!s.active} className="btn-sm"
                          onClick={() => act(`Fetch ${s.name}`, () => api("POST", `/api/v1/admin/sources/${s.id}/fetch-now`))}>Fetch now</button>
                  <button className="btn-sm"
                          onClick={() => act(`${s.active ? "Pause" : "Resume"} ${s.name}`,
                            () => api("PATCH", `/api/v1/admin/sources/${s.id}`, { active: !s.active }))}>{s.active ? "Pause" : "Resume"}</button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
        </div>
        <AddSource onAdd={(body) => act("Add source", () => api("POST", "/api/v1/admin/sources", body))} />
      </section>

      <section>
        <h2 className="mb-3 font-display text-xl font-medium text-ink">Recent runs</h2>
        <div className="admin-card overflow-x-auto !p-0">
        <table className="admin-table">
          <thead><tr><th>Started</th><th>Source</th><th>Try</th><th>Status</th><th>HTTP</th><th>Items (new)</th><th>Error</th></tr></thead>
          <tbody>
            {runs.map((r) => (
              <tr key={r.id}>
                <td className="whitespace-nowrap text-muted">{when(r.startedAt)}</td>
                <td>{r.sourceName ?? r.sourceId}</td>
                <td>{r.attempt}</td>
                <td><span className={`badge ${runBadge[r.status]}`}>{r.status}</span></td>
                <td>{r.httpStatus ?? "—"}</td>
                <td>{r.itemsSeen} ({r.itemsNew})</td>
                <td className="max-w-xs truncate text-muted" title={r.error ?? ""}>{r.error ?? ""}</td>
              </tr>
            ))}
          </tbody>
        </table>
        </div>
      </section>

      <section>
        <h2 className="mb-3 font-display text-xl font-medium text-ink">Dead letters ({dlq.filter((d) => !d.requeuedAt).length} waiting)</h2>
        {dlq.length === 0 ? <p className="admin-card text-sm text-muted">Nothing has failed permanently. 🎉</p> : (
          <div className="admin-card overflow-x-auto !p-0">
          <table className="admin-table">
            <thead><tr><th>When</th><th>Source</th><th>Attempts</th><th>Reason</th><th /></tr></thead>
            <tbody>
              {dlq.map((d) => (
                <tr key={d.id}>
                  <td className="whitespace-nowrap text-muted">{when(d.createdAt)}</td>
                  <td>{d.sourceName ?? d.sourceId ?? "—"}</td>
                  <td>{d.attempts}</td>
                  <td className="max-w-sm truncate" title={d.reason}>{d.reason}</td>
                  <td className="text-right">
                    {d.requeuedAt ? <span className="text-xs text-muted">requeued {when(d.requeuedAt)}</span> : (
                      <button className="btn-sm"
                              onClick={() => act("Requeue", () => api("POST", `/api/v1/admin/ingestion/dlq/${d.id}/requeue`))}>Requeue</button>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          </div>
        )}
      </section>
    </div>
  );
}

function AddSource({ onAdd }: { onAdd: (body: object) => Promise<boolean> }) {
  const [form, setForm] = useState({ name: "", siteUrl: "", feedUrl: "", section: "TECH" as Section, fetchIntervalMin: 30 });
  const field = "field !w-auto !rounded-full !py-2 text-sm";
  return (
    <form className="admin-card mt-4 flex flex-wrap items-end gap-2 !p-4 text-sm" aria-label="Add a feed"
          onSubmit={async (e) => {
            e.preventDefault();
            if (await onAdd(form)) setForm({ ...form, name: "", siteUrl: "", feedUrl: "" });   // keep input on error
          }}>
      <input required maxLength={120} placeholder="Name" className={field} value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} />
      <input required type="url" placeholder="Site URL" className={field} value={form.siteUrl} onChange={(e) => setForm({ ...form, siteUrl: e.target.value })} />
      <input required type="url" placeholder="Feed URL (RSS/Atom)" className={`${field} min-w-64`} value={form.feedUrl} onChange={(e) => setForm({ ...form, feedUrl: e.target.value })} />
      <select className={field} value={form.section} onChange={(e) => setForm({ ...form, section: e.target.value as Section })}>
        <option value="TECH">Code &amp; Cortex</option><option value="FOOTBALL">Football</option>
      </select>
      <input type="number" min={15} max={1440} className={`${field} w-20`} value={form.fetchIntervalMin}
             onChange={(e) => setForm({ ...form, fetchIntervalMin: Number(e.target.value) })} />
      <button className="btn-pill !py-2.5">Add feed</button>
    </form>
  );
}
