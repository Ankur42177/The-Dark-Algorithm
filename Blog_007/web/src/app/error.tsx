"use client";
/**
 * Error boundary for every public page: if rendering throws at runtime (e.g. the API is down and there's no
 * cached page to fall back to), readers get this friendly screen with a retry instead of a blank page.
 */
import Link from "next/link";
import { useEffect } from "react";

export default function GlobalError({ error, reset }: { error: Error & { digest?: string }; reset: () => void }) {
  useEffect(() => { console.error(error); }, [error]);
  return (
    <div className="mx-auto max-w-xl px-6 py-28 text-center">
      <p className="font-display text-[6rem] font-semibold leading-none tracking-tight text-ink/10" aria-hidden="true">VAR</p>
      <h1 className="-mt-4 font-display text-4xl font-medium tracking-tight text-ink">The play is <span className="hl">under review</span></h1>
      <p className="mt-4 text-muted">Something went wrong loading this page — probably a backend taking a breather. Give it another go.</p>
      <div className="mt-9 flex flex-wrap justify-center gap-5">
        <button type="button" onClick={reset} className="btn-pill">Try again <span className="arrow">↻</span></button>
        <Link href="/" className="btn-ghost">Back to kick-off <span className="arrow">→</span></Link>
      </div>
      {error.digest && <p className="mt-8 text-xs text-muted">Reference: {error.digest}</p>}
    </div>
  );
}
