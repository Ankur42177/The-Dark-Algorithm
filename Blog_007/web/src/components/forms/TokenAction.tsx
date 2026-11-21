"use client";
/**
 * Shared by /newsletter/confirm and /newsletter/unsubscribe: reads ?token=… from the URL, POSTs it once on
 * load, and shows the result. A ref guards against React's dev-mode double effect (and the endpoints are
 * idempotent anyway: confirming twice or unsubscribing twice is harmless).
 */
import Link from "next/link";
import { useEffect, useRef, useState } from "react";
import { useSearchParams } from "next/navigation";
import { postPublic } from "@/lib/publicApi";

type Copy = { working: string; okTitle: string; okBody: string; failTitle: string };
type State = { kind: "working" } | { kind: "ok" } | { kind: "error"; message: string };

export function TokenAction({ path, copy }: { path: string; copy: Copy }) {
  const token = useSearchParams().get("token")?.trim() ?? "";
  const [state, setState] = useState<State>(token ? { kind: "working" } : { kind: "error", message: "This link is missing its token. Try copying the whole link from the email." });
  const sent = useRef(false);

  useEffect(() => {
    if (!token || sent.current) return;
    sent.current = true;
    postPublic<{ status: string }>(path, { token }).then((res) =>
      setState(res.ok ? { kind: "ok" } : {
        kind: "error",
        message: res.status === 404 ? "This link has expired or was already used." : res.message,
      }));
  }, [path, token]);

  return (
    <div className="reveal mx-auto max-w-xl px-6 py-24 text-center" aria-live="polite">
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
