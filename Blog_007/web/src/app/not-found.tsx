import Link from "next/link";

export default function NotFound() {
  return (
    <div className="py-20 text-center">
      <h1 className="text-3xl font-bold">Page not found</h1>
      <p className="mt-2 text-[var(--muted)]">That post may have been unpublished.</p>
      <Link href="/" className="mt-6 inline-block font-semibold text-brand">← Back home</Link>
    </div>
  );
}
