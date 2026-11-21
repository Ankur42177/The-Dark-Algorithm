"use client";
/**
 * The chat panel (loaded on demand). Streams the answer from POST /api/v1/assistant/chat as Server-Sent
 * Events. EventSource only supports GET, so we use fetch() and read the response body stream ourselves,
 * splitting it into "event: …" / "data: …" blocks.
 */
import { useEffect, useRef, useState } from "react";
import { useRouter } from "next/navigation";

const API = process.env.NEXT_PUBLIC_ASSISTANT_URL ?? process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8083";

type Citation = { title: string; url: string; slug?: string | null; source: string };
type Action = { type: "NAVIGATE"; path: string; auto: boolean };
type Usage = { mode: string; toolCalls: number; tools: string[]; ttftMs: number; totalMs: number };
type Msg = { role: "user" | "assistant"; text: string; citations: Citation[]; actions: Action[]; usage?: Usage; error?: string };

function sessionId(): string {
  try {
    const existing = sessionStorage.getItem("pulse-chat-session");
    if (existing) return existing;
    const id = crypto.randomUUID();
    sessionStorage.setItem("pulse-chat-session", id);
    return id;
  } catch {
    return crypto.randomUUID();
  }
}

export function ChatPanel({ onClose }: { onClose: () => void }) {
  const router = useRouter();
  const [messages, setMessages] = useState<Msg[]>([]);
  const [input, setInput] = useState("");
  const [busy, setBusy] = useState(false);
  const bottom = useRef<HTMLDivElement>(null);

  useEffect(() => bottom.current?.scrollIntoView({ behavior: "smooth" }), [messages]);

  function update(fn: (m: Msg) => Msg) {
    setMessages((all) => [...all.slice(0, -1), fn(all[all.length - 1])]);
  }

  async function send(question: string) {
    if (!question.trim() || busy) return;
    setInput("");
    setBusy(true);
    setMessages((m) => [...m, { role: "user", text: question, citations: [], actions: [] },
      { role: "assistant", text: "", citations: [], actions: [] }]);
    try {
      const res = await fetch(`${API}/api/v1/assistant/chat`, {
        method: "POST",
        headers: { "Content-Type": "application/json", Accept: "text/event-stream" },
        body: JSON.stringify({ sessionId: sessionId(), message: question }),
      });
      if (!res.ok || !res.body) throw new Error(res.status === 429 ? "Too many questions — try again in a minute." : `HTTP ${res.status}`);
      const reader = res.body.pipeThrough(new TextDecoderStream()).getReader();
      let buffer = "";
      for (;;) {
        const { value, done } = await reader.read();
        if (done) break;
        buffer += value;
        let sep: number;
        while ((sep = buffer.indexOf("\n\n")) >= 0) {          // one SSE event ends with a blank line
          const block = buffer.slice(0, sep);
          buffer = buffer.slice(sep + 2);
          const event = /^event:(.*)$/m.exec(block)?.[1]?.trim();
          const data = block.split("\n").filter((l) => l.startsWith("data:")).map((l) => l.slice(5)).join("\n");
          if (!event || !data) continue;
          const payload = JSON.parse(data);
          if (event === "token") update((m) => ({ ...m, text: m.text + payload.text }));
          if (event === "citation") update((m) => ({ ...m, citations: [...m.citations, payload] }));
          if (event === "action") {
            update((m) => ({ ...m, actions: [...m.actions, payload] }));
            if (payload.auto) router.push(payload.path);       // only when the reader explicitly asked to go there
          }
          if (event === "done") update((m) => ({ ...m, usage: payload.usage }));
          if (event === "error") update((m) => ({ ...m, error: payload.message }));
        }
      }
    } catch (e) {
      update((m) => ({ ...m, error: (e as Error).message }));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div role="dialog" aria-label="Ask Pulse"
      className="fixed bottom-5 right-5 z-40 flex h-[32rem] w-[min(24rem,calc(100vw-2.5rem))] flex-col overflow-hidden rounded-2xl border border-[var(--line)] bg-[var(--card)] shadow-2xl">
      <div className="flex items-center justify-between border-b border-[var(--line)] px-4 py-3">
        <b>✨ Ask Pulse</b>
        <button onClick={onClose} aria-label="Close" className="text-[var(--muted)]">✕</button>
      </div>
      <div className="flex-1 space-y-3 overflow-y-auto p-4 text-sm">
        {messages.length === 0 && (
          <div className="space-y-2 text-[var(--muted)]">
            <p>Ask about posts on Tech, Football or AI.</p>
            {["What's new in AI this week?", "Explain Kafka consumer groups", "Take me to the football section"].map((q) => (
              <button key={q} onClick={() => send(q)} className="block rounded-lg border border-[var(--line)] px-3 py-1 text-left hover:border-brand">{q}</button>
            ))}
          </div>
        )}
        {messages.map((m, i) => (
          <div key={i} className={m.role === "user" ? "ml-8 rounded-xl bg-brand/15 p-3" : "mr-4"}>
            <p className="whitespace-pre-wrap">{m.text || (m.role === "assistant" && busy && i === messages.length - 1 ? "…" : "")}</p>
            {m.citations.length > 0 && (
              <ul className="mt-2 space-y-1">
                {m.citations.map((c) => (
                  <li key={c.url}>
                    <a href={c.url} {...(c.url.startsWith("http") ? { target: "_blank", rel: "noopener noreferrer nofollow" } : {})}
                      className="text-brand underline">{c.title}</a>
                    <span className="text-[var(--muted)]"> · {c.source}</span>
                  </li>
                ))}
              </ul>
            )}
            {m.actions.filter((a) => !a.auto).map((a) => (
              <button key={a.path} onClick={() => router.push(a.path)} className="mt-2 rounded-lg bg-brand px-3 py-1 text-white">Take me there →</button>
            ))}
            {m.error && <p className="mt-1 text-red-500">{m.error}</p>}
            {m.usage && (
              <p className="mt-1 text-xs text-[var(--muted)]">
                {m.usage.mode === "search" ? "search mode (AI unavailable)" : m.usage.mode === "cache" ? "cached answer" : `${m.usage.toolCalls} tool call(s)`}
                {" · "}first word {m.usage.ttftMs} ms · total {(m.usage.totalMs / 1000).toFixed(1)} s
              </p>
            )}
          </div>
        ))}
        <div ref={bottom} />
      </div>
      <form onSubmit={(e) => { e.preventDefault(); send(input); }} className="flex gap-2 border-t border-[var(--line)] p-3">
        <input value={input} onChange={(e) => setInput(e.target.value)} maxLength={1000} placeholder="Ask a question…"
          className="flex-1 rounded-lg border border-[var(--line)] bg-transparent px-3 py-2 text-sm" />
        <button disabled={busy} className="rounded-lg bg-brand px-3 py-2 text-sm font-semibold text-white disabled:opacity-50">Send</button>
      </form>
    </div>
  );
}
