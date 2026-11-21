import type { Metadata } from "next";

/**
 * Everything under /admin: never indexed by search engines, and — because it's a separate route
 * segment — its JavaScript is never downloaded by readers of the public pages.
 */
export const metadata: Metadata = { title: "Admin", robots: { index: false, follow: false } };

export default function AdminLayout({ children }: { children: React.ReactNode }) {
  return <div className="mx-auto max-w-5xl">{children}</div>;
}
