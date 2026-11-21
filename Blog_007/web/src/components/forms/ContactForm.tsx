"use client";
/**
 * Contact form: POST /api/v1/contact {name, email, message, website} → 202 {"status":"received"}.
 * Client-side checks are only a courtesy; the server validates again (400 ProblemDetail) and rate-limits (429).
 * `website` is a honeypot (see .hp in globals.css).
 */
import { useState } from "react";
import { looksLikeEmail, postPublic } from "@/lib/publicApi";

const MAX = 4000;
type Errors = Partial<Record<"name" | "email" | "message", string>>;

function validate(f: { name: string; email: string; message: string }): Errors {
  const e: Errors = {};
  if (!f.name.trim()) e.name = "What should I call you?";
  if (!looksLikeEmail(f.email)) e.email = "I'll need a valid email to reply.";
  if (f.message.trim().length < 10) e.message = "Give me a little more — at least 10 characters.";
  if (f.message.length > MAX) e.message = `That's an essay! Keep it under ${MAX} characters.`;
  return e;
}

export function ContactForm() {
  const [form, setForm] = useState({ name: "", email: "", message: "", website: "" });
  const [errors, setErrors] = useState<Errors>({});
  const [status, setStatus] = useState<"idle" | "sending" | "done">("idle");
  const [serverError, setServerError] = useState<string | null>(null);

  const set = (k: keyof typeof form) => (e: React.ChangeEvent<HTMLInputElement | HTMLTextAreaElement>) => {
    setForm((f) => ({ ...f, [k]: e.target.value }));
    if (errors[k as keyof Errors]) setErrors((x) => ({ ...x, [k]: undefined }));
  };

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    const found = validate(form);
    setErrors(found);
    if (Object.keys(found).length) {
      document.getElementById(`ct-${Object.keys(found)[0]}`)?.focus();
      return;
    }
    setStatus("sending");
    setServerError(null);
    const res = await postPublic<{ status: string }>("/api/v1/contact", {
      name: form.name.trim(), email: form.email.trim(), message: form.message.trim(), website: form.website,
    });
    if (res.ok) setStatus("done");
    else { setStatus("idle"); setServerError(res.message); }
  }

  if (status === "done") {
    return (
      <div role="status" className="glass rounded-3xl p-8 text-[var(--glass-ink)]">
        <p className="font-display text-2xl font-semibold">Message received — straight into the top corner. ⚽</p>
        <p className="mt-3 leading-relaxed text-[var(--glass-muted)]">
          Thanks, {form.name.trim().split(" ")[0]}. I read every message myself and usually reply within a few days.
        </p>
        <button type="button" className="btn-ghost mt-6" onClick={() => { setForm({ name: "", email: "", message: "", website: "" }); setStatus("idle"); }}>
          Send another <span className="arrow">→</span>
        </button>
      </div>
    );
  }

  const err = (k: keyof Errors) => errors[k] && <p id={`ct-${k}-err`} className="mt-1.5 text-sm text-hot">{errors[k]}</p>;
  const sending = status === "sending";
  return (
    <form onSubmit={submit} noValidate className="glass relative space-y-4 rounded-3xl p-6 text-[var(--glass-ink)] sm:p-8">
      <div className="grid gap-4 sm:grid-cols-2">
        <label className="block text-sm font-semibold">Name
          <input id="ct-name" className="field mt-1.5" autoComplete="name" maxLength={100} value={form.name} onChange={set("name")}
                 aria-invalid={!!errors.name} aria-describedby={errors.name ? "ct-name-err" : undefined} placeholder="Ada Lovelace" />
          {err("name")}
        </label>
        <label className="block text-sm font-semibold">Email
          <input id="ct-email" className="field mt-1.5" type="email" autoComplete="email" inputMode="email" maxLength={254} value={form.email}
                 onChange={set("email")} aria-invalid={!!errors.email} aria-describedby={errors.email ? "ct-email-err" : undefined} placeholder="ada@example.com" />
          {err("email")}
        </label>
      </div>
      <label className="block text-sm font-semibold">Message
        <textarea id="ct-message" className="field mt-1.5 min-h-40 resize-y" maxLength={MAX + 200} value={form.message} onChange={set("message")}
                  aria-invalid={!!errors.message} aria-describedby={`ct-count${errors.message ? " ct-message-err" : ""}`}
                  placeholder="A question, a correction, a hot take on the inverted full-back…" />
        <span className="mt-1 flex justify-between text-xs font-normal text-[var(--glass-muted)]">
          {err("message") ?? <span />}
          <span id="ct-count" className={form.message.length > MAX ? "text-hot" : ""}>{form.message.length}/{MAX}</span>
        </span>
      </label>
      <div className="hp" aria-hidden="true">
        <label>Website<input type="text" name="website" tabIndex={-1} autoComplete="off" value={form.website} onChange={set("website")} /></label>
      </div>
      {serverError && <p role="alert" className="rounded-xl border border-[var(--hot)] px-4 py-3 text-sm text-hot">{serverError}</p>}
      <div className="flex flex-wrap items-center gap-4">
        <button className="btn-pill" disabled={sending}>{sending ? "Sending…" : <>Send it <span className="arrow">→</span></>}</button>
        <p className="text-xs text-[var(--glass-muted)]">No account needed. Your email is only used to reply.</p>
      </div>
    </form>
  );
}
