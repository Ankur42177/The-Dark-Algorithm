"use client";
/**
 * Shared by /newsletter/confirm and /newsletter/unsubscribe: reads ?token=… from the URL and POSTs it ONLY when the
 * reader presses the button.
 *
 * Why a button instead of POSTing on page load? Corporate mail-security products ("safe links" scanners) open
 * every link in an email in a real browser, JavaScript included. An auto-POST would let a robot confirm a
 * subscription someone else typed in (defeating double opt-in) or silently unsubscribe a real reader.
 * Robots load pages; they don't press buttons. (Phase 7 security review.)
 *
 * The token is also removed from the address bar (history.replaceState) once read, so it doesn't linger in
 * browser history or get copied along with the URL.
 */
import Link from "next/link";
import { useEffect, useRef, useState } from "react";
import { useSearchParams } from "next/navigation";
import { postPublic } from "@/lib/publicApi";

type Copy = { ask: string; button: string; working: string; okTitle: string; okBody: string; failTitle: string };
type State = { kind: "ready" } | { kind: "working" } | { kind: "ok" } | { kind: "error"; message: string };

export function TokenAction({ path, copy }: { path: string; copy: Copy }) {
  const urlToken = useSearchParams().get("token")?.trim() ?? "";
  const token = useRef(urlToken);
  const [state, setState] = useState<State>(urlToken ? { kind: "ready" } : { kind: "error", message: "This link is missing its token. Try copying the whole link from the email." });

  useEffect(() => {
    if (urlToken && typeof window !== "undefined") {
      window.history.replaceState(null, "", window.location.pathname);   // keep it in memory, not in the URL
    }
  }, [urlToken]);

  async function act() {
    if (state.kind !== "ready") return;                                    // one POST per click, no double submit
    setState({ kind: "working" });
    const res = await postPublic<{ status: string }>(path, { token: token.current });
    setState(res.ok ? { kind: "ok" } : {
      kind: "error",
      message: res.status === 404 ? "This link has expired or was already used." : res.message,
    });
  }

  return (
    <div className="reveal mx-auto max-w-xl px-6 py-24 text-center" aria-live="polite">
      {state.kind === "ready" && (
        <>
          <h1 className="font-display text-4xl font-medium tracking-tight text-ink">{copy.ask}</h1>
          <button type="button" onClick={act} className="btn-pill mt-9">{copy.button} <span className="arrow">→</span></button>
        </>
      )}
      {state.kind === "working" && (
        <>
          <div className="mx-auto h-12 w-12 animate-spin rounded-full border-4 border-line border-t-[var(--color-coral)] motion-reduce:animate-none" aria-hidden="true" />
          <p className="mt-6 font-display text-2xl text-ink">{copy.working}</p>
        </>
      )}
      {state.kind === "ok" && (
        <>
          <p className="mx-auto grid h-16 w-16 place-items-center rounded-full bg-[var(--band-soft)] text-3xl text-accent" aria-hidden="true">✓</p>
          <h1 className="mt-6 font-display text-4xl font-medium tracking-tight text-ink">{copy.okTitle}</h1>
          <p className="mt-4 leading-relaxed text-muted">{copy.okBody}</p>
          <Link href="/" className="btn-pill mt-9">Back to the deck <span className="arrow">→</span></Link>
        </>
      )}
      {state.kind === "error" && (
        <>
          <p className="mx-auto grid h-16 w-16 place-items-center rounded-full bg-[var(--band-soft)] text-3xl text-hot" aria-hidden="true">!</p>
          <h1 className="mt-6 font-display text-4xl font-medium tracking-tight text-ink">{copy.failTitle}</h1>
          <p className="mt-4 leading-relaxed text-muted">{state.message}</p>
          <div className="mt-9 flex flex-wrap justify-center gap-5">
            <Link href="/#newsletter" className="btn-pill">Sign up again <span className="arrow">→</span></Link>
            <Link href="/contact" className="btn-ghost">Tell me what happened <span className="arrow">→</span></Link>
          </div>
        </>
      )}
    </div>
  );
}
