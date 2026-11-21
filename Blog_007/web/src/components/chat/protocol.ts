/**
 * Harvey's wire protocol: the SSE contract with assistant-service (via the gateway), parsed defensively.
 *
 *   POST /api/v1/assistant/chat  {sessionId, message}  →  text/event-stream
 *   status* → token* → citation* → action* → done        (or a terminal `error`)
 *
 * Every payload is validated field by field: the model's output is UNTRUSTED (prompt injection could try to
 * smuggle a javascript: URL or a path outside the site), so we keep only the shapes we expect.
 */

export const API = process.env.NEXT_PUBLIC_ASSISTANT_URL ?? process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080";

export type Citation = {
  ref: number;
  title: string;
  url: string;
  external: boolean;
  source: string;
  section: "TECH" | "FOOTBALL" | null;
  imageUrl: string | null;
};
export type Action = { path: string; auto: boolean; label: string };
export type Usage = { mode: string; toolCalls: number; ttftMs: number; totalMs: number; sessionId?: string; reason?: string | null };
export type ChatErrorKind = "rate" | "offline" | "invalid" | "server" | "cut";
export type ChatError = { kind: ChatErrorKind; message: string };

export type StreamHandlers = {
  status: (text: string, stage: string) => void;
  token: (text: string) => void;
  citation: (c: Citation) => void;
  action: (a: Action) => void;
  done: (u: Usage) => void;
};

// ------------------------------------------------------------------ validation helpers

const str = (v: unknown, max = 500): string | null => (typeof v === "string" ? v.slice(0, max) : null);
const num = (v: unknown): number => (typeof v === "number" && Number.isFinite(v) ? v : 0);

/** Only same-site paths Harvey is allowed to send people to (mirrors the server's NavigationPolicy). */
const NAV_PATH = /^\/(?:tech|football|contact|#newsletter|posts\/[A-Za-z0-9-]{1,200})?$/;
export const isAllowedPath = (p: string) => NAV_PATH.test(p);

export const SECTION_LABEL: Record<string, string> = { TECH: "Code & Cortex", FOOTBALL: "Football" };

export function labelForPath(path: string): string {
  if (path === "/") return "Home";
  if (path === "/tech") return "Code & Cortex";
  if (path === "/football") return "Football";
  if (path === "/contact") return "Contact";
  if (path === "/#newsletter") return "the newsletter";
  return "the post";
}

/** An internal link must start with ONE slash ("//evil.com" is a protocol-relative external URL). */
const isInternal = (u: string) => u.startsWith("/") && !u.startsWith("//");
const isHttp = (u: string) => /^https?:\/\//i.test(u);

export function toCitation(raw: unknown, fallbackRef: number): Citation | null {
  if (!raw || typeof raw !== "object") return null;
  const r = raw as Record<string, unknown>;
  const title = str(r.title, 200);
  const url = str(r.url, 2000);
  if (!title || !url || !(isInternal(url) || isHttp(url))) return null;
  const image = str(r.imageUrl, 2000);
  const section = r.section === "TECH" || r.section === "FOOTBALL" ? r.section : null;
  return {
    ref: typeof r.ref === "number" && r.ref > 0 ? r.ref : fallbackRef,
    title,
    url,
    external: r.external === true || isHttp(url),
    source: str(r.source, 80) ?? "",
    section,
    imageUrl: image && (/^https:\/\//i.test(image) || isInternal(image)) ? image : null,
  };
}

export function toAction(raw: unknown): Action | null {
  if (!raw || typeof raw !== "object") return null;
  const r = raw as Record<string, unknown>;
  const path = str(r.path, 300);
  if (r.type !== "NAVIGATE" || !path || !isAllowedPath(path)) return null;
  return { path, auto: r.auto === true, label: str(r.label, 40) || labelForPath(path) };
}

// ------------------------------------------------------------------ SSE framing

export type SseEvent = { event: string; data: string };

/**
 * Splits a growing text buffer into complete SSE events. An event ends with a blank line; lines are
 * "field:value" (one optional space after the colon). Returns the parsed events and the unfinished tail.
 * Handles \r\n and \r line endings, comment lines (":keep-alive") and multi-line data.
 */
export function parseSse(buffer: string): { events: SseEvent[]; rest: string } {
  const text = buffer.replace(/\r\n?/g, "\n");
  const events: SseEvent[] = [];
  let start = 0;
  for (let sep = text.indexOf("\n\n"); sep >= 0; sep = text.indexOf("\n\n", start)) {
    const block = text.slice(start, sep);
    start = sep + 2;
    let event = "message";
    const data: string[] = [];
    for (const line of block.split("\n")) {
      if (!line || line.startsWith(":")) continue;
      const colon = line.indexOf(":");
      const field = colon < 0 ? line : line.slice(0, colon);
      let value = colon < 0 ? "" : line.slice(colon + 1);
      if (value.startsWith(" ")) value = value.slice(1);
      if (field === "event") event = value.trim();
      else if (field === "data") data.push(value);
    }
    if (data.length) events.push({ event, data: data.join("\n") });
  }
  return { events, rest: text.slice(start) };
}

// ------------------------------------------------------------------ the request

function retryText(seconds: number | null): string {
  if (!seconds || seconds <= 60) return "Harvey needs a breather — try again in a minute.";
  if (seconds < 3600) return `Harvey needs a breather — try again in ${Math.ceil(seconds / 60)} minutes.`;
  return "Harvey has answered a lot of questions today — he'll be back a bit later.";
}

async function problemDetail(res: Response): Promise<string | null> {
  try {
    const body = (await res.json()) as Record<string, unknown>;
    return str(body.detail, 300) ?? str(body.title, 300);
  } catch {
    return null;
  }
}

/** Thrown (rather than returned) so the caller has ONE error path for network, HTTP and stream failures. */
export class ChatFailure extends Error {
  readonly info: ChatError;
  constructor(info: ChatError) {
    super(info.message);
    this.info = info;
  }
}

/**
 * Sends one question and feeds the streamed events to `on`. Resolves when `done` arrives; throws ChatFailure
 * for every failure (HTTP error, network error, `error` event, stream closed early). An abort rejects with
 * the AbortError untouched so the caller can tell "Stop" apart from a failure.
 */
export async function streamChat(sessionId: string, message: string, signal: AbortSignal, on: StreamHandlers): Promise<void> {
  let res: Response;
  try {
    res = await fetch(`${API}/api/v1/assistant/chat`, {
      method: "POST",
      headers: { "Content-Type": "application/json", Accept: "text/event-stream" },
      body: JSON.stringify({ sessionId, message }),
      signal,
    });
  } catch (e) {
    if (signal.aborted) throw e;
    throw new ChatFailure({ kind: "offline", message: "Harvey is offline right now — check your connection and try again." });
  }

  if (!res.ok) {
    if (res.status === 429) {
      const after = Number(res.headers.get("Retry-After"));
      throw new ChatFailure({ kind: "rate", message: retryText(Number.isFinite(after) ? after : null) });
    }
    if (res.status === 400) {
      throw new ChatFailure({ kind: "invalid", message: (await problemDetail(res)) ?? "Harvey couldn't read that question — try rephrasing it." });
    }
    throw new ChatFailure({ kind: "offline", message: "Harvey is offline right now. Try again in a moment." });
  }
  if (!res.body || !(res.headers.get("content-type") ?? "").includes("text/event-stream")) {
    throw new ChatFailure({ kind: "server", message: "Harvey sent back something unexpected. Try again." });
  }

  const reader = res.body.pipeThrough(new TextDecoderStream()).getReader();
  let buffer = "";
  let citations = 0;
  try {
    for (;;) {
      const { value, done } = await reader.read();
      if (done) break;
      buffer += value;
      const { events, rest } = parseSse(buffer);
      buffer = rest;
      for (const { event, data } of events) {
        let payload: Record<string, unknown>;
        try {
          payload = JSON.parse(data) as Record<string, unknown>;
        } catch {
          continue;                                        // a malformed frame is skipped, not fatal
        }
        if (!payload || typeof payload !== "object") continue;
        switch (event) {
          case "status": {
            const text = str(payload.text, 80);
            if (text) on.status(text, str(payload.stage, 20) ?? "");
            break;
          }
          case "token": {
            const text = str(payload.text, 4000);
            if (text) on.token(text);
            break;
          }
          case "citation": {
            const c = toCitation(payload, citations + 1);
            if (c) { citations++; on.citation(c); }
            break;
          }
          case "action": {
            const a = toAction(payload);
            if (a) on.action(a);
            break;
          }
          case "done": {
            const u = (payload.usage ?? {}) as Record<string, unknown>;
            on.done({
              mode: str(u.mode, 20) ?? "llm",
              toolCalls: num(u.toolCalls),
              ttftMs: num(u.ttftMs),
              totalMs: num(u.totalMs),
              sessionId: str(u.sessionId, 64) ?? undefined,
              reason: str(u.reason, 80),
            });
            return;
          }
          case "error":
            throw new ChatFailure({ kind: "server", message: str(payload.message, 300) ?? "Something went wrong. Please try again." });
          default:
            break;                                         // unknown events are ignored (forward compatible)
        }
      }
    }
  } catch (e) {
    if (signal.aborted || e instanceof ChatFailure) throw e;
    throw new ChatFailure({ kind: "cut", message: "The connection dropped before Harvey finished." });
  } finally {
    reader.cancel().catch(() => {});
  }
  throw new ChatFailure({ kind: "cut", message: "Harvey's answer got cut off before he finished." });
}
