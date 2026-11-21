"use client";
/**
 * Newsletter sign-up (double opt-in): POST /api/v1/newsletter/subscribe → 202 {"status":"pending"},
 * then the reader clicks the link in the confirmation email (/newsletter/confirm?token=…).
 * `website` is a honeypot: people never see it, naive bots fill it in, the server quietly ignores those.
 */
import { useState } from "react";
import { looksLikeEmail, postPublic } from "@/lib/publicApi";

type State = { kind: "idle" | "sending" } | { kind: "done" } | { kind: "error"; message: string };

export function NewsletterForm({ compact = false }: { compact?: boolean }) {
  const [email, setEmail] = useState("");
  const [name, setName] = useState("");
  const [website, setWebsite] = useState("");
  const [state, setState] = useState<State>({ kind: "idle" });
  const [touched, setTouched] = useState(false);
  const invalid = touched && !looksLikeEmail(email);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setTouched(true);
    if (!looksLikeEmail(email)) return;
    setState({ kind: "sending" });
    const res = await postPublic<{ status: string }>("/api/v1/newsletter/subscribe", {
      email: email.trim(), name: name.trim() || undefined, website,
    });
    setState(res.ok ? { kind: "done" } : { kind: "error", message: res.message });
  }

  if (state.kind === "done") {
    return (
      <div role="status" className="rounded-3xl border border-line bg-card p-6">
        <p className="font-display text-xl font-semibold text-ink">Almost there — check your inbox ✉︎</p>
        <p className="mt-2 text-sm leading-relaxed text-muted">
          We&apos;ve sent a confirmation link to <b className="text-ink">{email.trim()}</b>. Click it and your first wildcard
          is on its way next month. (Nothing there? Peek in spam, then come back and try again.)
        </p>
      </div>
    );
  }

  const sending = state.kind === "sending";
  return (
    <form onSubmit={submit} noValidate className="relative space-y-3" aria-describedby="nl-note">
      <div className={`grid gap-3 ${compact ? "sm:grid-cols-[1fr_auto]" : "sm:grid-cols-2"}`}>
        {!compact && (
          <label className="block">
            <span className="sr-only">First name (optional)</span>
            <input className="field" name="name" autoComplete="given-name" maxLength={80} placeholder="First name (optional)"
                   value={name} onChange={(e) => setName(e.target.value)} />
          </label>
        )}
        <label className="block">
          <span className="sr-only">Email address</span>
          <input className="field" type="email" name="email" required autoComplete="email" inputMode="email" maxLength={254}
                 placeholder="you@example.com" value={email} aria-invalid={invalid}
                 aria-describedby={invalid ? "nl-email-err" : undefined}
                 onChange={(e) => setEmail(e.target.value)} onBlur={() => email && setTouched(true)} />
        </label>
        {compact && (
          <button className="btn-pill" disabled={sending}>{sending ? "Sealing…" : <>Deal me in <span className="arrow">→</span></>}</button>
        )}
      </div>
      {/* honeypot — leave empty */}
      <div className="hp" aria-hidden="true">
        <label>Website<input type="text" name="website" tabIndex={-1} autoComplete="off" value={website} onChange={(e) => setWebsite(e.target.value)} /></label>
      </div>
      {invalid && <p id="nl-email-err" className="text-sm text-hot">That email looks a little off — mind checking it?</p>}
      {state.kind === "error" && <p role="alert" className="text-sm text-hot">{state.message}</p>}
      {!compact && (
        <button className="btn-pill w-full sm:w-auto" disabled={sending}>
          {sending ? "Sealing the envelope…" : <>Deal me in <span className="arrow">→</span></>}
        </button>
      )}
      <p id="nl-note" className="text-xs text-muted">One email a month. No spam, no tracking pixels, one-click unsubscribe.</p>
    </form>
  );
}
