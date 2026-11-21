"use client";
/**
 * Harvey's chat panel (downloaded on demand by ChatLauncher, then kept mounted so a closed panel keeps its
 * conversation and an answer can finish streaming in the background).
 *
 * Streams answers from POST /api/v1/assistant/chat (see protocol.ts). The conversation is saved in
 * sessionStorage, so it survives reloads and navigation within the tab; "New chat" starts a fresh server
 * session. Desktop: floating glass panel. ≤640 px: full-screen sheet.
 */
import { useCallback, useEffect, useRef, useState, type KeyboardEvent as ReactKeyboardEvent, type ReactNode } from "react";
import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { HarveyAvatar } from "./HarveyAvatar";
import { Markdown, toPlainText } from "./Markdown";
import { startersFor } from "./starters";
import { ChatFailure, SECTION_LABEL, isAllowedPath, streamChat, type Action, type ChatError, type Citation, type Usage } from "./protocol";

type UserMsg = { id: string; role: "user"; text: string };
type BotMsg = {
  id: string;
  role: "assistant";
  question: string;
  text: string;
  status: string | null;
  citations: Citation[];
  actions: Action[];
  state: "streaming" | "done" | "stopped" | "error";
  searchMode?: boolean;
  usage?: Usage;
  error?: ChatError;
};
type Msg = UserMsg | BotMsg;

const STORE = "pulse-harvey-chat";
const LEGACY_SESSION = "pulse-chat-session";
const MAX_SAVED = 40;
const MAX_INPUT = 1000;

const newId = (): string => {
  try {
    return crypto.randomUUID();
  } catch {
    return `h${Date.now().toString(36)}${Math.random().toString(36).slice(2, 12)}`;
  }
};

function load(): { sessionId: string; messages: Msg[] } {
  try {
    const raw = sessionStorage.getItem(STORE);
    if (raw) {
      const saved = JSON.parse(raw) as { sessionId?: unknown; messages?: unknown };
      if (typeof saved.sessionId === "string" && /^[A-Za-z0-9-]{8,64}$/.test(saved.sessionId) && Array.isArray(saved.messages)) {
        // An answer that was still streaming when the page unloaded can't be resumed: offer a retry instead.
        const messages = (saved.messages as Msg[]).map((m): Msg => (m.role === "assistant" && m.state === "streaming"
          ? { ...m, state: "stopped", status: null }
          : m));
        return { sessionId: saved.sessionId, messages };
      }
    }
    const legacy = sessionStorage.getItem(LEGACY_SESSION);
    return { sessionId: legacy && /^[A-Za-z0-9-]{8,64}$/.test(legacy) ? legacy : newId(), messages: [] };
  } catch {
    return { sessionId: newId(), messages: [] };
  }
}

function save(sessionId: string, messages: Msg[]) {
  try {
    sessionStorage.setItem(STORE, JSON.stringify({ sessionId, messages: messages.slice(-MAX_SAVED) }));
  } catch {
    /* storage full or blocked (private mode): the chat still works, it just won't survive a reload */
  }
}

const isPhone = () => typeof window !== "undefined" && window.matchMedia("(max-width: 640px)").matches;
const reducedMotion = () => typeof window !== "undefined" && window.matchMedia("(prefers-reduced-motion: reduce)").matches;

type Props = { open: boolean; onClose: () => void; onUnread?: () => void };

export function ChatPanel({ open, onClose, onUnread }: Props) {
  const router = useRouter();
  const pathname = usePathname();
  const [initial] = useState(load);
  const sessionRef = useRef(initial.sessionId);
  const [messages, setMessages] = useState<Msg[]>(initial.messages);
  const [input, setInput] = useState("");
  const [announce, setAnnounce] = useState("");
  const [copied, setCopied] = useState<string | null>(null);
  const [pageTitle, setPageTitle] = useState<string | null>(null);

  const dialogRef = useRef<HTMLDivElement>(null);
  const logRef = useRef<HTMLDivElement>(null);
  const inputRef = useRef<HTMLTextAreaElement>(null);
  const ctrlRef = useRef<AbortController | null>(null);
  const stickRef = useRef(true);
  const pending = useRef<{ id: string; text: string; frame: number } | null>(null);
  const openRef = useRef(open);
  openRef.current = open;

  const last = messages[messages.length - 1];
  const busy = last?.role === "assistant" && last.state === "streaming";
  const lastBot = [...messages].reverse().find((m): m is BotMsg => m.role === "assistant");
  const resting = !!lastBot?.searchMode && !busy;

  // ---------------------------------------------------------------- persistence
  useEffect(() => { if (!busy) save(sessionRef.current, messages); }, [messages, busy]);

  // ---------------------------------------------------------------- open / close behaviour
  useEffect(() => {
    if (!open) return;
    stickRef.current = true;
    const log = logRef.current;
    if (log) log.scrollTop = log.scrollHeight;
    // the post's <h1> powers the "Summarise this post" starter chip
    setPageTitle(pathname.startsWith("/posts/") ? document.querySelector("main h1")?.textContent ?? null : null);
    const focus = window.setTimeout(() => inputRef.current?.focus(), 30);
    const onKey = (e: KeyboardEvent) => { if (e.key === "Escape") { e.preventDefault(); onClose(); } };
    window.addEventListener("keydown", onKey);
    // Full-screen sheet on phones: stop the page underneath from scrolling.
    const root = document.documentElement;
    const prevOverflow = root.style.overflow;
    if (isPhone()) root.style.overflow = "hidden";
    return () => {
      window.clearTimeout(focus);
      window.removeEventListener("keydown", onKey);
      root.style.overflow = prevOverflow;
    };
  }, [open, onClose, pathname]);

  // Keep the newest words in view — unless the reader scrolled up to re-read something.
  useEffect(() => {
    const log = logRef.current;
    if (open && log && stickRef.current) log.scrollTop = log.scrollHeight;
  }, [messages, open]);

  // Abort an in-flight answer if the panel is ever unmounted.
  useEffect(() => () => { ctrlRef.current?.abort(); if (pending.current) cancelAnimationFrame(pending.current.frame); }, []);

  // Tab / Shift+Tab cycle inside the dialog (a focus trap), so keyboard users don't fall behind the sheet.
  function trapTab(e: ReactKeyboardEvent<HTMLDivElement>) {
    if (e.key !== "Tab" || !dialogRef.current) return;
    const items = dialogRef.current.querySelectorAll<HTMLElement>(
      'a[href], button:not([disabled]), textarea:not([disabled]), input:not([disabled]), [tabindex]:not([tabindex="-1"])');
    if (!items.length) return;
    const first = items[0];
    const lastItem = items[items.length - 1];
    if (e.shiftKey && document.activeElement === first) { e.preventDefault(); lastItem.focus(); }
    else if (!e.shiftKey && document.activeElement === lastItem) { e.preventDefault(); first.focus(); }
  }

  // ---------------------------------------------------------------- message helpers
  const patch = useCallback((id: string, fn: (m: BotMsg) => BotMsg) => {
    setMessages((all) => all.map((m) => (m.id === id && m.role === "assistant" ? fn(m) : m)));
  }, []);

  /** Tokens arrive word by word; we batch them into one React update per animation frame. */
  const flush = useCallback(() => {
    const p = pending.current;
    if (!p) return;
    cancelAnimationFrame(p.frame);
    pending.current = null;
    if (p.text) patch(p.id, (m) => ({ ...m, text: m.text + p.text, status: null }));
  }, [patch]);

  const queueToken = useCallback((id: string, text: string) => {
    if (pending.current) { pending.current.text += text; return; }
    pending.current = { id, text, frame: requestAnimationFrame(() => flush()) };
  }, [flush]);

  const navigate = useCallback((a: Action) => {
    if (!isAllowedPath(a.path)) return;
    if (a.path === "/#newsletter" && window.location.pathname === "/") {
      document.getElementById("newsletter")?.scrollIntoView({ behavior: reducedMotion() ? "auto" : "smooth" });
    } else {
      router.push(a.path);
    }
    if (isPhone()) onClose();                 // the sheet covers the page; get out of the way (state is kept)
  }, [router, onClose]);

  const send = useCallback(async (question: string, retryId?: string) => {
    const q = question.trim().slice(0, MAX_INPUT);
    if (!q || ctrlRef.current) return;
    const id = retryId ?? newId();
    const fresh: BotMsg = { id, role: "assistant", question: q, text: "", status: null, citations: [], actions: [], state: "streaming" };
    if (retryId) setMessages((all) => all.map((m) => (m.id === retryId ? fresh : m)));
    else setMessages((all) => [...all, { id: newId(), role: "user", text: q }, fresh]);
    setInput("");
    stickRef.current = true;
    setAnnounce("Harvey is thinking…");

    const ctrl = new AbortController();
    ctrlRef.current = ctrl;
    try {
      await streamChat(sessionRef.current, q, ctrl.signal, {
        status: (text, stage) => patch(id, (m) => ({ ...m, status: text, searchMode: m.searchMode || stage === "fallback" })),
        token: (text) => queueToken(id, text),
        citation: (c) => { flush(); patch(id, (m) => (m.citations.some((x) => x.url === c.url) ? m : { ...m, citations: [...m.citations, c] })); },
        action: (a) => {
          flush();
          patch(id, (m) => (m.actions.some((x) => x.path === a.path) ? m : { ...m, actions: [...m.actions, a] }));
          if (a.auto) navigate(a);
        },
        done: (u) => {
          flush();
          if (u.sessionId && /^[A-Za-z0-9-]{8,64}$/.test(u.sessionId)) sessionRef.current = u.sessionId;
          let finalText = "";
          patch(id, (m) => {
            finalText = m.text;
            return { ...m, state: "done", status: null, usage: u, searchMode: u.mode === "search" || m.searchMode };
          });
          window.setTimeout(() => setAnnounce(`Harvey says: ${toPlainText(finalText).slice(0, 600)}`), 0);
          if (!openRef.current) onUnread?.();
        },
      });
    } catch (e) {
      flush();
      if (ctrl.signal.aborted) {
        patch(id, (m) => ({ ...m, state: "stopped", status: null }));
        setAnnounce("Stopped.");
      } else {
        const info: ChatError = e instanceof ChatFailure ? e.info : { kind: "server", message: "Something went wrong. Please try again." };
        patch(id, (m) => ({ ...m, state: "error", status: null, error: info }));
        setAnnounce(info.message);
        if (!openRef.current) onUnread?.();
      }
    } finally {
      if (ctrlRef.current === ctrl) ctrlRef.current = null;
    }
  }, [patch, queueToken, flush, navigate, onUnread]);

  const stop = () => ctrlRef.current?.abort();

  function newChat() {
    ctrlRef.current?.abort();
    ctrlRef.current = null;
    sessionRef.current = newId();
    setMessages([]);
    setInput("");
    setAnnounce("Started a new chat.");
    inputRef.current?.focus();
  }

  async function copy(m: BotMsg) {
    try {
      await navigator.clipboard.writeText(toPlainText(m.text));
      setCopied(m.id);
      setAnnounce("Answer copied.");
      window.setTimeout(() => setCopied((c) => (c === m.id ? null : c)), 1600);
    } catch {
      setAnnounce("Couldn't copy — select the text instead.");
    }
  }

  function onInputKey(e: ReactKeyboardEvent<HTMLTextAreaElement>) {
    if (e.key === "Enter" && !e.shiftKey && !e.nativeEvent.isComposing) {
      e.preventDefault();
      if (!busy) void send(input);
    }
  }

  // Grow the textarea with its content (up to ~5 lines).
  useEffect(() => {
    const el = inputRef.current;
    if (!el) return;
    el.style.height = "auto";
    el.style.height = `${Math.min(el.scrollHeight, 132)}px`;
  }, [input, open]);

  if (!open) return null;

  const starters = startersFor(pathname, pageTitle);

  return (
    <div ref={dialogRef} data-testid="harvey-panel" role="dialog" aria-modal="true" aria-labelledby="harvey-title" aria-describedby="harvey-sub" onKeyDown={trapTab}
      className="harvey-panel glass fixed bottom-4 right-4 z-50 flex h-[min(42rem,calc(100dvh-2rem))] w-[min(26.5rem,calc(100vw-2rem))] flex-col overflow-hidden rounded-[26px] text-[var(--glass-ink)] shadow-[0_40px_80px_-30px_rgb(6_11_24/.7)]">
      {/* ------------------------------------------------ header */}
      <div className="harvey-head flex items-center gap-3 border-b border-[var(--glass-border)] px-4 py-3">
        <HarveyAvatar size={40} blink />
        <div className="min-w-0">
          <h2 id="harvey-title" className="font-display text-[1.05rem] font-semibold leading-tight">Harvey</h2>
          <p id="harvey-sub" className="flex items-center gap-1.5 text-xs text-[var(--glass-muted)]">
            <span className={`h-1.5 w-1.5 rounded-full ${resting ? "bg-amber-400" : "bg-emerald-400"} ${busy ? "animate-pulse motion-reduce:animate-none" : ""}`} aria-hidden="true" />
            {busy ? "Typing…" : resting ? "Search mode · AI resting" : "Your guide to Code & Cortex · Football"}
          </p>
        </div>
        <div className="ml-auto flex items-center gap-1">
          <button type="button" data-testid="harvey-new-chat" onClick={newChat} disabled={messages.length === 0} aria-label="Start a new chat" title="New chat"
                  className="harvey-icon-btn">
            <svg width="16" height="16" viewBox="0 0 16 16" aria-hidden="true" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round">
              <path d="M13.5 8.5v4a1.5 1.5 0 0 1-1.5 1.5H4A1.5 1.5 0 0 1 2.5 12.5V4.5A1.5 1.5 0 0 1 4 3h4" /><path d="M11.5 1.8l2.7 2.7L8.6 10.1l-3.1.5.5-3.1z" />
            </svg>
          </button>
          <button type="button" data-testid="harvey-close" onClick={onClose} aria-label="Close chat with Harvey" title="Close (Esc)" className="harvey-icon-btn">
            <svg width="12" height="12" viewBox="0 0 12 12" aria-hidden="true"><path d="M2 2l8 8m0-8-8 8" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" /></svg>
          </button>
        </div>
      </div>

      {resting && (
        <p className="harvey-banner" role="note" data-testid="harvey-search-banner">
          <span aria-hidden="true">🌙</span> Harvey&apos;s AI brain is resting — answers show the closest matches from the archive for now.
        </p>
      )}

      {/* ------------------------------------------------ conversation */}
      <div ref={logRef} data-testid="harvey-log" role="log" aria-live="off" aria-label="Conversation with Harvey" tabIndex={-1}
           onScroll={(e) => { const el = e.currentTarget; stickRef.current = el.scrollHeight - el.scrollTop - el.clientHeight < 48; }}
           className="flex-1 space-y-4 overflow-y-auto overscroll-contain px-4 py-4 text-sm">
        {messages.length === 0 && (
          <div>
            <div className="flex gap-2.5">
              <HarveyAvatar size={28} className="mt-0.5 shrink-0" />
              <div className="harvey-bot rounded-2xl rounded-tl-md px-3.5 py-2.5 leading-relaxed">
                Hi, I&apos;m <b>Harvey</b> <span aria-hidden="true">👋</span> I know every essay on Pulse — from Kafka partitions to
                De Bruyne&apos;s assists — and I can take you anywhere on the site. Ask me anything, or try one of these:
              </div>
            </div>
            <ul className="mt-3 flex flex-wrap gap-2 pl-[2.4rem]" aria-label="Suggested questions">
              {starters.map((st) => (
                <li key={st.label}>
                  <button type="button" data-testid="harvey-starter" onClick={() => void send(st.ask)} className="harvey-chip">{st.label}</button>
                </li>
              ))}
            </ul>
          </div>
        )}

        {messages.map((m) => m.role === "user" ? (
          <div key={m.id} className="ml-10 flex justify-end">
            <p data-testid="harvey-user-msg" className="harvey-user whitespace-pre-wrap break-words rounded-2xl rounded-br-md px-3.5 py-2.5">
              <span className="sr-only">You: </span>{m.text}
            </p>
          </div>
        ) : (
          <Answer key={m.id} m={m} pathname={pathname} copied={copied === m.id}
                  onCopy={() => void copy(m)} onRetry={() => void send(m.question, m.id)} onNavigate={navigate}
                  onLink={() => { if (isPhone()) onClose(); }} />
        ))}
      </div>

      {/* ------------------------------------------------ composer */}
      <form onSubmit={(e) => { e.preventDefault(); if (!busy) void send(input); }}
            className="harvey-composer flex items-end gap-2 border-t border-[var(--glass-border)] p-3">
        <label className="sr-only" htmlFor="harvey-input">Message Harvey</label>
        <div className="relative min-w-0 flex-1">
          <textarea id="harvey-input" data-testid="harvey-input" ref={inputRef} value={input} rows={1} maxLength={MAX_INPUT}
                    onChange={(e) => setInput(e.target.value)} onKeyDown={onInputKey}
                    placeholder={busy ? "Harvey is answering…" : "Ask Harvey…"} autoComplete="off" enterKeyHint="send"
                    className="field harvey-input !rounded-[22px] !py-2.5 !pr-12" />
          {input.length > MAX_INPUT - 200 && (
            <span className="pointer-events-none absolute bottom-2.5 right-3 text-[.68rem] tabular-nums text-[var(--glass-muted)]" aria-live="polite">
              {MAX_INPUT - input.length}
            </span>
          )}
        </div>
        {busy ? (
          <button type="button" data-testid="harvey-stop" onClick={stop} aria-label="Stop Harvey's answer" title="Stop"
                  className="harvey-send grid h-11 w-11 shrink-0 place-items-center rounded-full bg-[var(--color-brand)] text-white shadow-md transition hover:brightness-110 active:scale-95">
            <span className="h-3.5 w-3.5 rounded-[3px] bg-current" aria-hidden="true" />
          </button>
        ) : (
          <button type="submit" data-testid="harvey-send" disabled={!input.trim()} aria-label="Send message"
                  className="harvey-send grid h-11 w-11 shrink-0 place-items-center rounded-full bg-[var(--color-brand)] text-white shadow-md transition hover:brightness-110 active:scale-95 disabled:opacity-45">
            <svg width="16" height="16" viewBox="0 0 16 16" aria-hidden="true"><path d="M2.5 8h10M9 4l4 4-4 4" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" /></svg>
          </button>
        )}
      </form>
      <p className="sr-only" aria-live="polite" aria-atomic="true">{announce}</p>
    </div>
  );
}

// ==================================================================== one answer

function Answer({ m, pathname, copied, onCopy, onRetry, onNavigate, onLink }: {
  m: BotMsg; pathname: string; copied: boolean;
  onCopy: () => void; onRetry: () => void; onNavigate: (a: Action) => void; onLink: () => void;
}) {
  const streaming = m.state === "streaming";
  const renderRef = (n: number): ReactNode => {
    const c = m.citations.find((x) => x.ref === n);
    if (!c) return streaming ? <sup className="harvey-ref is-pending">{n}</sup> : null;
    return <CiteLink c={c} onLink={onLink} className="harvey-ref" label={`Source ${n}: ${c.title}`}><sup>{n}</sup></CiteLink>;
  };

  return (
    <div className="mr-2 flex gap-2.5" data-testid="harvey-answer" data-state={m.state} data-mode={m.usage?.mode}>
      <HarveyAvatar size={28} className="mt-0.5 shrink-0" />
      <div className="min-w-0 flex-1">
        <div className="harvey-bot rounded-2xl rounded-tl-md px-3.5 py-2.5">
          <span className="sr-only">Harvey: </span>
          {m.searchMode && (
            <p className="harvey-mode" data-testid="harvey-search-mode"><span aria-hidden="true">🔎</span> Search mode — the AI is resting, so here are the closest matches.</p>
          )}
          {m.text ? (
            <Markdown text={m.text} renderRef={renderRef} caret={streaming} />
          ) : streaming ? (
            <p className="flex items-center gap-2 py-0.5 text-[var(--glass-muted)]">
              <span className="flex gap-1" aria-hidden="true">
                {[0, 1, 2].map((d) => <span key={d} className="harvey-dot" style={{ animationDelay: `${d * 140}ms` }} />)}
              </span>
              <span className="text-[.8rem]">{m.status ?? "Thinking…"}</span>
            </p>
          ) : null}

          {m.state === "stopped" && <p className="mt-1.5 text-[.8rem] text-[var(--glass-muted)]">{m.text ? "Stopped." : "Stopped before Harvey answered."}</p>}
          {m.state === "error" && m.error && (
            <p className={`harvey-error ${m.error.kind === "rate" ? "is-rate" : ""}`} role="alert" data-testid="harvey-error" data-kind={m.error.kind}>
              <span aria-hidden="true">{m.error.kind === "rate" ? "☕" : m.error.kind === "offline" ? "📡" : "⚠️"}</span> {m.error.message}
            </p>
          )}

          {m.citations.length > 0 && (
            <div className="mt-3">
              <p className="mb-1.5 text-[.66rem] font-bold uppercase tracking-[.16em] text-[var(--glass-muted)]">Sources</p>
              <ul className="grid gap-1.5">
                {m.citations.map((c) => <li key={c.url} data-testid="harvey-citation"><CitationCard c={c} onLink={onLink} /></li>)}
              </ul>
            </div>
          )}

          {m.actions.length > 0 && (
            <div className="mt-3 flex flex-wrap gap-2">
              {m.actions.map((a) => {
                const here = a.path === pathname;
                return (
                  <button key={a.path} type="button" data-testid="harvey-action" onClick={() => onNavigate(a)} className="btn-pill !px-4 !py-2 !text-[.82rem]">
                    {here ? <>You&apos;re on {a.label} <span aria-hidden="true">✓</span></> : <>Take me to {a.label} <span className="arrow" aria-hidden="true">→</span></>}
                  </button>
                );
              })}
            </div>
          )}
        </div>

        {/* toolbar under the bubble */}
        {!streaming && (
          <div className="mt-1 flex flex-wrap items-center gap-1 pl-1 text-[.7rem] text-[var(--glass-muted)]">
            {m.text && m.state !== "error" && (
              <button type="button" data-testid="harvey-copy" onClick={onCopy} className="harvey-mini" aria-label="Copy Harvey's answer">
                {copied ? "✓ Copied" : <><CopyIcon /> Copy</>}
              </button>
            )}
            {(m.state === "error" || m.state === "stopped") && (
              <button type="button" data-testid="harvey-retry" onClick={onRetry} className="harvey-mini" aria-label="Ask Harvey again">
                <RetryIcon /> Retry
              </button>
            )}
            {m.usage && <span className="ml-1">{usageText(m.usage)}</span>}
          </div>
        )}
      </div>
    </div>
  );
}

function usageText(u: Usage): string {
  if (u.mode === "cache") return "instant · remembered answer";
  if (u.mode === "nav") return "instant";
  if (u.mode === "search") return "search mode";
  const secs = (u.totalMs / 1000).toFixed(1);
  return u.toolCalls > 0 ? `${secs} s · looked up ${u.toolCalls} source${u.toolCalls === 1 ? "" : "s"}` : `${secs} s`;
}

/** Internal posts use the Next router (client-side navigation); external articles open in a new tab. */
function CiteLink({ c, children, className, label, onLink }: { c: Citation; children: ReactNode; className: string; label: string; onLink: () => void }) {
  if (c.external) {
    return (
      <a href={c.url} target="_blank" rel="noopener noreferrer nofollow" className={className} aria-label={`${label} (opens in a new tab)`}>
        {children}
      </a>
    );
  }
  return <Link href={c.url} className={className} aria-label={label} onClick={onLink}>{children}</Link>;
}

function CitationCard({ c, onLink }: { c: Citation; onLink: () => void }) {
  const section = c.section ? SECTION_LABEL[c.section] : null;
  const meta = [section, c.source].filter(Boolean).join(" · ");
  const tone = c.section === "FOOTBALL" ? "is-football" : c.section === "TECH" ? "is-tech" : "is-web";
  return (
    <CiteLink c={c} onLink={onLink} className="harvey-cite group" label={`${c.title}${meta ? ` — ${meta}` : ""}`}>
      <span className={`harvey-thumb ${tone}`} aria-hidden="true">
        {c.imageUrl
          // eslint-disable-next-line @next/next/no-img-element -- tiny thumbnails from any host; next/image would add JS + a proxy hop
          ? <img src={c.imageUrl} alt="" loading="lazy" decoding="async" referrerPolicy="no-referrer"
                 onError={(e) => { e.currentTarget.style.display = "none"; }} />
          : <span>{c.title.charAt(0).toUpperCase()}</span>}
      </span>
      <span className="min-w-0 flex-1">
        <span className="harvey-cite-title">{c.title}</span>
        <span className="harvey-cite-meta">{meta || (c.external ? "External article" : "Pulse")}{c.external && " ↗"}</span>
      </span>
    </CiteLink>
  );
}

const CopyIcon = () => (
  <svg width="12" height="12" viewBox="0 0 12 12" aria-hidden="true" fill="none" stroke="currentColor" strokeWidth="1.3">
    <rect x="3.6" y="3.6" width="6.8" height="6.8" rx="1.4" /><path d="M8.2 2.4V2.2A1 1 0 0 0 7.2 1.2H2.4a1 1 0 0 0-1 1v4.8a1 1 0 0 0 1 1h.2" />
  </svg>
);
const RetryIcon = () => (
  <svg width="12" height="12" viewBox="0 0 12 12" aria-hidden="true" fill="none" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" strokeLinejoin="round">
    <path d="M10.3 6a4.3 4.3 0 1 1-1.3-3.1" /><path d="M10.4 1.6v2.6H7.8" />
  </svg>
);
