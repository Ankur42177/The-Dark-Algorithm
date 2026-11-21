import Link from "next/link";
import { SECTIONS } from "@/lib/sections";
import { HeaderControls } from "./HeaderControls";

/** The logo mark: two overlapping circles (teal + sky), a quiet nod to a heartbeat "pulse". */
function Mark() {
  return (
    <svg width="34" height="22" viewBox="0 0 34 22" aria-hidden="true" className="transition-transform duration-500 group-hover:scale-110">
      <circle cx="11" cy="11" r="10" fill="var(--color-brand)" />
      <circle cx="23" cy="11" r="10" fill="var(--color-sky)" fillOpacity=".9" />
      <path d="M3 11h6l2.5-5 4 10 3-7 2 2h10" fill="none" stroke="#fff" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  );
}

/** Server Component; only <HeaderControls> (theme + play toggles) ships JavaScript. */
export function SiteHeader() {
  const links = [
    ...SECTIONS.map((s) => ({ href: `/${s.slug}`, label: s.label })),
    { href: "/#newsletter", label: "Wildcard" },
    { href: "/contact", label: "Contact" },
  ];
  return (
    <header className="site-header sticky top-0 z-30 border-b border-line">
      <nav aria-label="Main" className="mx-auto flex h-[64px] max-w-6xl items-center gap-3 px-4 sm:h-[68px] sm:gap-6 sm:px-6">
        <Link href="/" className="group flex shrink-0 items-center gap-2.5 sm:gap-3" aria-label="Pulse home">
          <Mark />
          <span className="font-display text-lg font-semibold tracking-[.22em] text-ink sm:text-xl sm:tracking-[.32em]">PULSE</span>
        </Link>
        <div className="ml-4 hidden items-center gap-7 md:flex">
          {links.map((l) => <Link key={l.href} href={l.href} className="nav-link">{l.label}</Link>)}
        </div>
        <div className="ml-auto flex items-center">
          <HeaderControls />
        </div>
      </nav>
      {/* phones: section links on their own scrollable row */}
      <div className="no-scrollbar flex gap-6 overflow-x-auto border-t border-line px-4 py-2.5 md:hidden">
        {links.map((l) => <Link key={l.href} href={l.href} className="nav-link shrink-0">{l.label}</Link>)}
      </div>
    </header>
  );
}
