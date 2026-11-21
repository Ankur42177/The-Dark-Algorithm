/** Shown instantly while a page streams in (rare: most pages are pre-built). A shimmering card skeleton. */
export default function Loading() {
  return (
    <div className="mx-auto max-w-6xl px-6 py-20" aria-busy="true" aria-label="Loading">
      <div className="mx-auto h-3 w-40 animate-pulse rounded-full bg-[var(--line)] motion-reduce:animate-none" />
      <div className="mx-auto mt-5 h-10 w-2/3 max-w-xl animate-pulse rounded-2xl bg-[var(--line)] motion-reduce:animate-none" />
      <div className="mt-14 grid gap-8 sm:grid-cols-2 lg:grid-cols-3">
        {[0, 1, 2].map((i) => (
          <div key={i} className="aspect-[5/7] animate-pulse rounded-[22px] bg-[var(--band-soft)] ring-1 ring-[var(--line)] motion-reduce:animate-none" style={{ animationDelay: `${i * 150}ms` }} />
        ))}
      </div>
    </div>
  );
}
