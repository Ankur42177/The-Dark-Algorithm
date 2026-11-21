/**
 * A tiny, SAFE markdown renderer for Harvey's answers.
 *
 * The answer is model output — untrusted text that a prompt injection could fill with <script> or
 * <img onerror=…>. So we never use dangerouslySetInnerHTML: we parse a small markdown subset ourselves and
 * build React elements, and React escapes every string. Supported: paragraphs, "- " / "1. " lists,
 * "#" headings (shown as bold lines), ``` code blocks, **bold**, *italic*, `inline code`, [n] citation markers.
 * Markdown links [text](url) are shown as plain text (links only come from validated citation events).
 * Unclosed **bold / `code at the end of a line render as if closed, so streaming text doesn't flicker.
 */
import { Fragment, type ReactNode } from "react";

type Block =
  | { kind: "p"; lines: string[] }
  | { kind: "ul" | "ol"; items: string[]; start: number }
  | { kind: "h"; text: string }
  | { kind: "code"; lines: string[] };

const BULLET = /^\s*[-*•]\s+(.*)$/;
const NUMBERED = /^\s*(\d{1,3})[.)]\s+(.*)$/;
const HEADING = /^\s{0,3}#{1,6}\s+(.*)$/;

export function parseBlocks(text: string): Block[] {
  const blocks: Block[] = [];
  let cur = null as Block | null;
  const flush = () => { if (cur) blocks.push(cur); cur = null; };
  for (const line of text.split("\n")) {
    if (cur?.kind === "code") {
      if (line.trim().startsWith("```")) flush();
      else cur.lines.push(line);
      continue;
    }
    if (line.trim().startsWith("```")) { flush(); cur = { kind: "code", lines: [] }; continue; }
    if (!line.trim()) { flush(); continue; }
    const h = HEADING.exec(line);
    if (h) { flush(); blocks.push({ kind: "h", text: h[1] }); continue; }
    const b = BULLET.exec(line);
    const n = b ? null : NUMBERED.exec(line);
    if (b || n) {
      const kind = b ? "ul" : "ol";
      if (cur?.kind !== kind) { flush(); cur = { kind, items: [], start: n ? Number(n[1]) : 1 }; }
      (cur as { items: string[] }).items.push(b ? b[1] : n![2]);
      continue;
    }
    // A plain line right after a list item continues that item (soft wrap); otherwise it's paragraph text.
    if (cur && (cur.kind === "ul" || cur.kind === "ol") && /^\s{2,}/.test(line)) {
      cur.items[cur.items.length - 1] += " " + line.trim();
      continue;
    }
    if (cur?.kind !== "p") { flush(); cur = { kind: "p", lines: [] }; }
    (cur as { lines: string[] }).lines.push(line);
  }
  flush();
  return blocks;
}

const INLINE = /`([^`\n]+)(?:`|$)|\*\*([^*\n]+)(?:\*\*|$)|(?<![*\w])\*([^*\n]+)\*(?!\*)|\[([^\]\n]{1,200})\]\([^)\s]{1,2000}\)|\[(\d{1,2})\]/g;

export function renderInline(text: string, renderRef: (n: number) => ReactNode, keyPrefix = ""): ReactNode[] {
  const out: ReactNode[] = [];
  let last = 0;
  let i = 0;
  for (const m of text.matchAll(INLINE)) {
    const at = m.index ?? 0;
    if (at > last) out.push(text.slice(last, at));
    const k = `${keyPrefix}${i++}`;
    if (m[1] !== undefined) out.push(<code key={k} className="harvey-code">{m[1]}</code>);
    else if (m[2] !== undefined) out.push(<strong key={k} className="font-semibold">{renderInline(m[2], renderRef, k + "-")}</strong>);
    else if (m[3] !== undefined) out.push(<em key={k}>{m[3]}</em>);
    else if (m[4] !== undefined) out.push(<Fragment key={k}>{m[4]}</Fragment>);
    else if (m[5] !== undefined) out.push(<Fragment key={k}>{renderRef(Number(m[5]))}</Fragment>);
    last = at + m[0].length;
  }
  if (last < text.length) out.push(text.slice(last));
  return out;
}

export function Markdown({ text, renderRef, caret }: { text: string; renderRef: (n: number) => ReactNode; caret?: boolean }) {
  const blocks = parseBlocks(text);
  const end = caret ? <span className="harvey-caret" aria-hidden="true" /> : null;
  return (
    <div className="harvey-md">
      {blocks.map((b, i) => {
        const tail = i === blocks.length - 1 ? end : null;
        switch (b.kind) {
          case "h":
            return <p key={i} className="font-semibold">{renderInline(b.text, renderRef)}{tail}</p>;
          case "code":
            return <pre key={i} className="harvey-pre"><code>{b.lines.join("\n")}</code>{tail}</pre>;
          case "ul":
            return <ul key={i}>{b.items.map((it, j) => <li key={j}>{renderInline(it, renderRef)}{j === b.items.length - 1 && tail}</li>)}</ul>;
          case "ol":
            return <ol key={i} start={b.start}>{b.items.map((it, j) => <li key={j}>{renderInline(it, renderRef)}{j === b.items.length - 1 && tail}</li>)}</ol>;
          default:
            return (
              <p key={i}>
                {b.lines.map((l, j) => <Fragment key={j}>{j > 0 && <br />}{renderInline(l, renderRef, `${j}-`)}</Fragment>)}
                {tail}
              </p>
            );
        }
      })}
      {blocks.length === 0 && end}
    </div>
  );
}

/** Plain text for the clipboard: markdown markers and [n] references stripped. */
export function toPlainText(text: string): string {
  return text
    .replace(/\[(\d{1,2})\]/g, "")
    .replace(/\*\*([^*\n]+)\*\*/g, "$1")
    .replace(/`([^`\n]+)`/g, "$1")
    .replace(/[ \t]+\n/g, "\n")
    .trim();
}
