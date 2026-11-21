import type { SectionKey } from "./types";

/**
 * URL slug ("tech") <-> API enum ("TECH"), plus display data. One source of truth.
 *
 * Pulse has TWO arenas: "Code & Cortex" (slug /tech, key TECH — engineering AND AI/LLMs) and Football.
 * Change a label here and it changes everywhere (nav, footer, doors, cards, metadata).
 * The backend moved every AI post and feed into TECH; the "AI" key is still accepted by the
 * SectionKey type for safety (an old cached response, an old event), but it is never displayed:
 * sectionByKey("AI") quietly resolves to Tech, and /ai permanently redirects to /tech (next.config.ts).
 */
export interface SectionInfo {
  slug: string;
  key: SectionKey;
  label: string;
  blurb: string;
  /** Page heading: text before / highlighted word (coral underline) / text after. */
  heading: [string, string, string];
  /** Short line used on the landing-page "door" and in cards. */
  tagline: string;
  /** Call-to-action on the landing-page door. */
  cta: string;
  /** Placeholder cover gradient for posts without a photo. */
  gradient: string;
  /** Playing-card corner index. */
  monogram: string;
}

export const SECTIONS: SectionInfo[] = [
  {
    slug: "tech", key: "TECH", label: "Code & Cortex",
    blurb: "Code for the systems that scale — Kafka, caches, databases. Cortex for the AI that thinks on top of them — LLMs, agents, RAG. Explained like a friend at a whiteboard. No hype, just how it really works.",
    heading: ["Where code meets the ", "cortex", ""],
    tagline: "Systems that scale, and the AI that thinks on top of them.",
    cta: "Enter the server room",
    gradient: "linear-gradient(135deg, #1d2b4a 0%, #2b7a7d 100%)", monogram: "</>",
  },
  {
    slug: "football", key: "FOOTBALL", label: "Football",
    blurb: "Tactics, legends and the nights we'll never forget — the Premier League and the Champions League, taken apart one pass at a time.",
    heading: ["The beautiful game, ", "decoded", ""],
    tagline: "Pressing traps, half-spaces and the legends who bent the rules.",
    cta: "Walk out the tunnel",
    gradient: "linear-gradient(135deg, #14504f 0%, #2b7a7d 55%, #7fb8d6 100%)", monogram: "FC",
  },
];

export const sectionBySlug = (slug: string) => SECTIONS.find((s) => s.slug === slug);

/** Never returns undefined: legacy "AI" (or anything unknown) is shown as Tech. */
export const sectionByKey = (key: SectionKey): SectionInfo => SECTIONS.find((s) => s.key === key) ?? SECTIONS[0];

export function formatDate(iso: string): string {
  return new Date(iso).toLocaleDateString("en-GB", { day: "numeric", month: "short", year: "numeric", timeZone: "UTC" });
}
