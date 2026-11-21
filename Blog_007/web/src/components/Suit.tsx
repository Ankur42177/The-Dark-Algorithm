import type { SectionKey } from "@/lib/types";

/**
 * Tiny "suit" glyphs for the playing-card corner index (like ♠ ♥ on real cards):
 * Tech = a diamond-shaped chip, Football = a ball, external article = an arrow out.
 */
export function Suit({ kind }: { kind: SectionKey | "WEB" }) {
  if (kind === "FOOTBALL") {
    return (
      <svg viewBox="0 0 16 16" aria-hidden="true">
        <circle cx="8" cy="8" r="6.6" fill="none" stroke="currentColor" strokeWidth="1.4" />
        <path d="M8 5.2 10.6 7l-1 3H6.4l-1-3z" fill="currentColor" />
      </svg>
    );
  }
  if (kind === "WEB") {
    return (
      <svg viewBox="0 0 16 16" aria-hidden="true">
        <path d="M5 11 11 5M6 5h5v5" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round" />
      </svg>
    );
  }
  return (
    <svg viewBox="0 0 16 16" aria-hidden="true">
      <path d="M8 1.5 14.5 8 8 14.5 1.5 8z" fill="currentColor" />
      <path d="M8 5.2 10.8 8 8 10.8 5.2 8z" fill="rgb(0 0 0 / .35)" />
    </svg>
  );
}
