import type { Metadata } from "next";
import { Inter } from "next/font/google";
import "./globals.css";
import { SiteHeader } from "@/components/SiteHeader";
import { ChatLauncher } from "@/components/chat/ChatLauncher";

/**
 * next/font downloads Inter at BUILD time and serves it from our own domain:
 * no request to Google from the reader's browser, no layout shift (size-adjusted fallback).
 * One variable font file covers every weight.
 */
const inter = Inter({ subsets: ["latin"], display: "swap", variable: "--font-inter" });

export const metadata: Metadata = {
  metadataBase: new URL(process.env.SITE_URL ?? "http://localhost:3000"),
  title: { default: "Pulse — Tech · Football · AI", template: "%s · Pulse" },
  description: "Ankur Kumar's blog on tech, football and AI, plus the best posts from around the web.",
  authors: [{ name: "Ankur Kumar" }],
  creator: "Ankur Kumar",
  publisher: "Ravenclaw_007 LLC",
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="en" className={inter.variable}>
      <body className="min-h-screen font-sans antialiased">
        <SiteHeader />
        <main className="mx-auto max-w-5xl px-4 py-8">{children}</main>
        <footer className="mx-auto max-w-5xl px-4 py-10 text-sm text-[var(--muted)]">
          © 2026 Ravenclaw_007 LLC · Designed and developed by Ankur Kumar · Built with Spring Boot microservices, Kafka, RabbitMQ, Redis and Next.js.
        </footer>
        {/* Phase 8: a tiny button; the chat panel's JS loads only when clicked. */}
        <ChatLauncher />
      </body>
    </html>
  );
}
