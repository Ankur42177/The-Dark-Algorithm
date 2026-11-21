"use client";
/**
 * Admin → Newsletter: subscriber counts, this month's send status, the 50 newest subscribers (emails arrive
 * masked from the server), and a guarded "send this month's wildcard now" action.
 */
import { useCallback, useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import { api, ApiError, isNotDeployed, type MonthlySendResult, type NewsletterOverview } from "@/lib/adminApi";
import { NotAvailable } from "@/components/admin/AdminTabs";

const statusBadge: Record<string, string> = { ACTIVE: "badge-green", PENDING: "badge-amber", UNSUBSCRIBED: "badge-gray" };
const when = (iso: string | null) => (iso ? new Date(iso).toLocaleString() : "—");
const monthName = (period: string) => {
  const [y, m] = period.split("-").map(Number);
  return y && m ? new Date(Date.UTC(y, m - 1, 1)).toLocaleDateString("en-GB", { month: "long", year: "numeric", timeZone: "UTC" }) : period;
};

export default function NewsletterAdmin() {
  const router = useRouter();
  const [data, setData] = useState<NewsletterOverview | null>(null);
  const [state, setState] = useState<"loading" | "ready" | "unavailable" | "error">("loading");
  const [error, setError] = useState("");
  const [confirming, setConfirming] = useState(false);
  const [sending, setSending] = useState(false);
  const [result, setResult] = useState<{ kind: "ok" | "error"; text: string } | null>(null);

  const load = useCallback(async () => {
    try {
      setData(await api<NewsletterOverview>("GET", "/api/v1/admin/newsletter"));
      setState("ready");
    } catch (e) {
      if (e instanceof ApiError && e.status === 401) return router.replace("/admin/login");
      if (isNotDeployed(e)) return setState("unavailable");
      setError((e as Error).message);
      setState("error");
    }
  }, [router]);

  useEffect(() => { load(); }, [load]);

  async function sendMonthly() {
    setSending(true);
    setResult(null);
    try {
      const r = await api<MonthlySendResult>("POST", "/api/v1/admin/newsletter/send-monthly");
      setResult({
        kind: r.failed > 0 ? "error" : "ok",
        text: `${monthName(r.period)}: ${r.sent} sent · ${r.skipped} skipped (already had it) · ${r.failed} failed.`,
      });
      await load();
    } catch (e) {
      if (e instanceof ApiError && e.status === 401) return router.replace("/admin/login");
      setResult({ kind: "error", text: `Send failed: ${(e as Error).message}` });
    } finally {
      setSending(false);
      setConfirming(false);
    }
  }

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-end gap-3">
        <div className="mr-auto">
          <h1 className="font-display text-3xl font-medium tracking-tight text-ink">Newsletter</h1>
          <p className="mt-1 text-sm text-muted">The monthly wildcard: who&apos;s in the draw.</p>
        </div>
        {state === "ready" && <button onClick={load} className="btn-sm">↻ Refresh</button>}
      </div>

      {state === "loading" && <div className="admin-card animate-pulse text-muted">Counting envelopes…</div>}
      {state === "unavailable" && <NotAvailable what="The newsletter dashboard" detail="The newsletter service didn't answer. Check that content-service and the gateway are running, then refresh." />}
      {state === "error" && <p role="alert" className="admin-card text-hot">Couldn&apos;t load subscribers: {error}</p>}

      {state === "ready" && data && (
        <>
          <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
            {(["ACTIVE", "PENDING", "UNSUBSCRIBED"] as const).map((k) => (
              <div key={k} className="admin-card !p-4">
                <p className="text-[.64rem] font-bold uppercase tracking-[.18em] text-muted">{k.toLowerCase()}</p>
                <p className="mt-1 font-display text-3xl font-semibold text-ink">{data.counts?.[k] ?? 0}</p>
              </div>
            ))}
            <div className="admin-card !p-4">
              <p className="text-[.64rem] font-bold uppercase tracking-[.18em] text-muted">total</p>
              <p className="mt-1 font-display text-3xl font-semibold text-ink">{data.total}</p>
            </div>
          </div>

          {/* this month's send */}
          <div className="admin-card flex flex-wrap items-center gap-4">
            <div className="mr-auto">
              <p className="kicker">This month · {monthName(data.period)}</p>
              <p className="mt-1.5 text-sm text-fg">
                <b className="font-display text-xl text-ink">{data.sentThisPeriod}</b> of {data.counts?.ACTIVE ?? 0} active subscribers have received this month&apos;s wildcard.
              </p>
            </div>
            {!confirming ? (
              <button className="btn-sm primary" onClick={() => { setConfirming(true); setResult(null); }} disabled={(data.counts?.ACTIVE ?? 0) === 0}>
                ✉︎ Send monthly wildcard…
              </button>
            ) : (
              <div role="group" aria-label="Confirm send" className="flex flex-wrap items-center gap-2 rounded-2xl border border-[var(--hot)] px-3 py-2">
                <span className="text-sm font-semibold text-ink">Send this month&apos;s wildcard now?</span>
                <button className="btn-sm primary" onClick={sendMonthly} disabled={sending} autoFocus>{sending ? "Sending…" : "Yes, send it"}</button>
                <button className="btn-sm" onClick={() => setConfirming(false)} disabled={sending}>Cancel</button>
              </div>
            )}
            {result && (
              <p role="status" className={`w-full rounded-xl px-3 py-2 text-sm ${result.kind === "ok" ? "bg-green-600/15 text-ink" : "bg-red-600/15 text-hot"}`}>{result.text}</p>
            )}
          </div>

          <div className="admin-card overflow-x-auto !p-0">
            <div className="border-b border-line px-4 py-3 text-xs text-muted">50 newest · emails are masked by the server</div>
            <table className="admin-table">
              <thead><tr><th>Email</th><th>Name</th><th>Status</th><th>Joined</th><th>Confirmed</th><th>Unsubscribed</th></tr></thead>
              <tbody>
                {data.recent.map((s) => (
                  <tr key={s.id}>
                    <td className="font-medium text-ink">{s.email}</td>
                    <td>{s.name ?? "—"}</td>
                    <td><span className={`badge ${statusBadge[s.status] ?? "badge-gray"}`}>{s.status}</span></td>
                    <td className="whitespace-nowrap text-muted">{when(s.createdAt)}</td>
                    <td className="whitespace-nowrap text-muted">{when(s.confirmedAt)}</td>
                    <td className="whitespace-nowrap text-muted">{when(s.unsubscribedAt)}</td>
                  </tr>
                ))}
                {data.recent.length === 0 && <tr><td colSpan={6} className="py-10 text-center text-muted">No subscribers yet — the first wildcard is waiting.</td></tr>}
              </tbody>
            </table>
          </div>
        </>
      )}
    </div>
  );
}
