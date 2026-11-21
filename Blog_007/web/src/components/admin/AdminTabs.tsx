"use client";
/** Admin navigation (hidden on the login page). Admin code is a separate route segment: readers never download it. */
import Link from "next/link";
import { usePathname } from "next/navigation";

const TABS = [
  { href: "/admin", label: "Posts", match: (p: string) => p === "/admin" || p.startsWith("/admin/posts") },
  { href: "/admin/ingestion", label: "Ingestion", match: (p: string) => p.startsWith("/admin/ingestion") },
  { href: "/admin/newsletter", label: "Newsletter", match: (p: string) => p.startsWith("/admin/newsletter") },
  { href: "/admin/contact", label: "Inbox", match: (p: string) => p.startsWith("/admin/contact") },
];

export function AdminTabs() {
  const path = usePathname() ?? "/admin";
  if (path.startsWith("/admin/login")) return null;
  return (
    <div className="mb-8 flex flex-wrap items-center gap-4">
      <p className="kicker mr-2">Back office</p>
      <nav aria-label="Admin" className="no-scrollbar flex gap-1 overflow-x-auto rounded-full border border-line bg-card p-1">
        {TABS.map((t) => {
          const active = t.match(path);
          return (
            <Link key={t.href} href={t.href} aria-current={active ? "page" : undefined}
                  className={`whitespace-nowrap rounded-full px-4 py-1.5 text-[.72rem] font-bold uppercase tracking-[.14em] transition ${active ? "bg-ink text-page" : "text-muted hover:text-ink"}`}>
              {t.label}
            </Link>
          );
        })}
      </nav>
    </div>
  );
}

/** Shared "the backend for this isn't live yet" state for admin views. */
export function NotAvailable({ what, detail }: { what: string; detail?: string }) {
  return (
    <div className="admin-card grid place-items-center px-6 py-16 text-center">
      <div className="grid h-14 w-14 place-items-center rounded-2xl bg-[var(--band-soft)] text-2xl" aria-hidden="true">🛠️</div>
      <p className="mt-5 font-display text-2xl font-medium text-ink">{what} isn&apos;t available yet</p>
      <p className="mt-2 max-w-md text-sm leading-relaxed text-muted">
        {detail ?? "The service behind this screen is still being built. Once it's deployed, this page lights up automatically — no front-end change needed."}
      </p>
    </div>
  );
}
