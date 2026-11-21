import Link from "next/link";

export default function NotFound() {
  return (
    <div className="mx-auto max-w-xl px-6 py-28 text-center">
      <p className="font-display text-[7rem] font-semibold leading-none tracking-tight text-ink/10" aria-hidden="true">404</p>
      <h1 className="-mt-6 font-display text-4xl font-medium tracking-tight text-ink"><span className="hl">Offside!</span> This page isn&apos;t in play.</h1>
      <p className="mt-4 text-muted">The post may have been unpublished, or the link took a wrong turn. VAR agrees.</p>
      <div className="mt-9 flex flex-wrap justify-center gap-5">
        <Link href="/" className="btn-pill">Back to kick-off <span className="arrow">→</span></Link>
        <Link href="/contact" className="btn-ghost">Report a broken link <span className="arrow">→</span></Link>
      </div>
    </div>
  );
}
