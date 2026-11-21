import Link from "next/link";
import { SECTIONS } from "@/lib/sections";

export function SiteHeader() {
  return (
    <header className="sticky top-0 z-20 border-b border-[var(--line)] bg-[var(--bg)]/85 backdrop-blur">
      <nav className="mx-auto flex h-14 max-w-5xl items-center gap-5 px-4">
        <Link href="/" className="flex items-center gap-2 font-extrabold tracking-tight">
          <span className="grid h-7 w-7 place-items-center rounded-lg bg-gradient-to-br from-brand to-brand-2 text-sm text-white">P</span>
          Pulse
        </Link>
        <div className="ml-auto flex gap-4 text-sm font-medium">
          {SECTIONS.map((s) => (
            <Link key={s.slug} href={`/${s.slug}`} className="text-[var(--muted)] hover:text-brand">{s.label}</Link>
          ))}
        </div>
      </nav>
    </header>
  );
}
