import type { Metadata, Viewport } from "next";
import Link from "next/link";
import { Inter, Outfit } from "next/font/google";
import "./globals.css";
import { SiteHeader } from "@/components/SiteHeader";
import { ChatLauncher } from "@/components/chat/ChatLauncher";
import { CardFx } from "@/components/CardFx";
import { SECTIONS } from "@/lib/sections";

/**
 * next/font downloads the fonts at BUILD time and serves them from our own domain:
 * no request to Google from the reader's browser, no layout shift (size-adjusted fallback).
 * Inter = body text; Outfit = geometric display face for headings.
 */
const inter = Inter({ subsets: ["latin"], display: "swap", variable: "--font-inter" });
const outfit = Outfit({ subsets: ["latin"], display: "swap", variable: "--font-outfit" });

export const metadata: Metadata = {
  metadataBase: new URL(process.env.SITE_URL ?? "http://localhost:3000"),
  title: { default: "Pulse — Systems that scale. Football that sings.", template: "%s · Pulse" },
  description: "Ankur Kumar on distributed systems, AI and the beautiful game — plus the sharpest reads from official engineering blogs.",
  authors: [{ name: "Ankur Kumar" }],
  creator: "Ankur Kumar",
  publisher: "Ravenclaw_007 LLC",
};

export const viewport: Viewport = {
  themeColor: [
    { media: "(prefers-color-scheme: light)", color: "#fbfcfe" },
    { media: "(prefers-color-scheme: dark)", color: "#060b18" },
  ],
};

/**
 * Runs in <head> BEFORE the page paints: reads the saved theme ("system" | "light" | "dark") and sets
 * data-theme on <html>, so a dark-mode reader never sees a white flash. Wrapped in try/catch because
 * localStorage can throw (privacy modes). Kept tiny on purpose — it blocks rendering for a few microseconds.
 */
const THEME_SCRIPT = `(function(){try{var p=localStorage.getItem("pulse-theme");if(p!=="light"&&p!=="dark")p="system";var t=p==="system"?(matchMedia("(prefers-color-scheme: dark)").matches?"dark":"light"):p;var d=document.documentElement;d.dataset.theme=t;d.dataset.themePref=p;d.style.colorScheme=t}catch(e){}})()`;

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    // suppressHydrationWarning: the script above changes <html>'s attributes before React hydrates (expected).
    <html lang="en" className={`${inter.variable} ${outfit.variable}`} suppressHydrationWarning>
      <head>
        <script dangerouslySetInnerHTML={{ __html: THEME_SCRIPT }} />
      </head>
      <body className="min-h-screen overflow-x-clip font-sans antialiased">
        <a href="#main" className="sr-only focus:not-sr-only focus:fixed focus:left-4 focus:top-4 focus:z-50 focus:rounded-full focus:bg-[var(--card)] focus:px-4 focus:py-2 focus:text-ink">
          Skip to content
        </a>
        <SiteHeader />
        <main id="main">{children}</main>
        <footer className="mt-12 border-t border-line">
          <div className="mx-auto grid max-w-6xl gap-10 px-6 py-14 sm:grid-cols-[1.4fr_1fr_1fr]">
            <div>
              <Link href="/" className="font-display text-lg font-semibold tracking-[.3em] text-ink">PULSE</Link>
              <p className="mt-3 max-w-xs text-sm leading-relaxed text-muted">
                Systems that scale. Football that sings. Written for curious people who like to know how things really work.
              </p>
            </div>
            <nav aria-label="Sections" className="flex flex-col gap-3">
              <p className="kicker !text-muted">Arenas</p>
              {SECTIONS.map((s) => <Link key={s.slug} href={`/${s.slug}`} className="nav-link w-fit">{s.label}</Link>)}
            </nav>
            <nav aria-label="Stay in touch" className="flex flex-col gap-3">
              <p className="kicker !text-muted">Stay close</p>
              <Link href="/#newsletter" className="nav-link w-fit">Monthly wildcard</Link>
              <Link href="/contact" className="nav-link w-fit">Contact</Link>
            </nav>
          </div>
          <div className="border-t border-line">
            <p className="mx-auto max-w-6xl px-6 py-6 text-sm text-muted">© 2026 Ravenclaw_007 LLC · Designed and developed by Ankur Kumar</p>
          </div>
        </footer>
        {/* One delegated listener powers every card's tilt + shine (no per-card JS). */}
        <CardFx />
        {/* Phase 8: a tiny button; the chat panel's JS loads only when clicked. */}
        <ChatLauncher />
      </body>
    </html>
  );
}
