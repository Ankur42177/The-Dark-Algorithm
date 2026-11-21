import type { Metadata } from "next";
import { Inter } from "next/font/google";
import "./globals.css";
import { SiteHeader } from "@/components/SiteHeader";

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
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="en" className={inter.variable}>
      <body className="min-h-screen font-sans antialiased">
        <SiteHeader />
        <main className="mx-auto max-w-5xl px-4 py-8">{children}</main>
        <footer className="mx-auto max-w-5xl px-4 py-10 text-sm text-[var(--muted)]">
          © Ankur Kumar · Built with Spring Boot microservices, Kafka, RabbitMQ, Redis and Next.js.
        </footer>
      </body>
    </html>
  );
}
