import type { SectionKey } from "./types";

/** URL slug ("tech") <-> API enum ("TECH"), plus display data. One source of truth. */
export const SECTIONS: { slug: string; key: SectionKey; label: string; blurb: string; emoji: string }[] = [
  { slug: "tech", key: "TECH", label: "Tech", blurb: "Distributed systems, databases and the craft of software.", emoji: "⚙️" },
  { slug: "football", key: "FOOTBALL", label: "Football", blurb: "Tactics, data and the beautiful game.", emoji: "⚽" },
  { slug: "ai", key: "AI", label: "AI", blurb: "LLMs, agents, RAG and what's actually new.", emoji: "🤖" },
];

export const sectionBySlug = (slug: string) => SECTIONS.find((s) => s.slug === slug);
export const sectionByKey = (key: SectionKey) => SECTIONS.find((s) => s.key === key)!;

export function formatDate(iso: string): string {
  return new Date(iso).toLocaleDateString("en-GB", { day: "numeric", month: "short", year: "numeric", timeZone: "UTC" });
}
