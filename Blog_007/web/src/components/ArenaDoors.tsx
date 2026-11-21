import Link from "next/link";
import { SECTIONS } from "@/lib/sections";
import type { SectionKey } from "@/lib/types";

/**
 * "Choose your arena": two big doors, one per section. Pure CSS art (grid lines for Tech, a mown pitch for
 * Football) — no images to download. The pointer-follow tilt/glow comes from the shared CardFx listener.
 */
export function ArenaDoors({ counts }: { counts: Partial<Record<SectionKey, number>> }) {
  return (
    <div className="reveal-stagger grid gap-6 md:grid-cols-2">
      {SECTIONS.map((s) => (
        <Link key={s.slug} href={`/${s.slug}`} data-fx="6" className={`door group door-${s.slug} p-8 sm:p-10`}>
          <div className="door-art" aria-hidden="true" />
          <div className="door-glow" aria-hidden="true" />
          <span className="door-glyph" aria-hidden="true">{s.monogram}</span>

          <div className="flex h-full min-h-[22rem] flex-col">
            <div className="flex items-center justify-between">
              <span className="rounded-full border border-white/25 bg-white/10 px-3 py-1 text-[.64rem] font-bold uppercase tracking-[.2em] backdrop-blur">
                {counts[s.key] ?? 0} essays
              </span>
              <span aria-hidden="true" className="grid h-11 w-11 place-items-center rounded-full border border-white/25 bg-white/10 backdrop-blur transition duration-500 group-hover:rotate-[-45deg] group-hover:bg-white group-hover:text-[#0a1430]">→</span>
            </div>
            <div className="mt-auto max-w-sm">
              <p className="text-[.7rem] font-bold uppercase tracking-[.26em] text-white/70">Arena {s.key === "TECH" ? "01" : "02"}</p>
              <h3 className="mt-2 font-display text-[2.6rem] font-semibold leading-none tracking-tight sm:text-[3.2rem]">{s.label}</h3>
              <p className="mt-4 text-[1rem] leading-relaxed text-white/80">{s.tagline}</p>
              <p className="mt-7 inline-flex items-center gap-2 text-[.72rem] font-bold uppercase tracking-[.18em]">
                {s.cta} <span className="arrow transition-transform duration-300 group-hover:translate-x-1.5">→</span>
              </p>
            </div>
          </div>
        </Link>
      ))}
    </div>
  );
}
