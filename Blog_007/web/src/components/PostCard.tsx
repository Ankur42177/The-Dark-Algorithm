import Link from "next/link";
import type { PostSummary } from "@/lib/types";
import { formatDate, sectionByKey } from "@/lib/sections";

/**
 * A Server Component (the default in the App Router): rendered to HTML on the server,
 * ships ZERO JavaScript to the browser. Only components marked "use client" ship JS.
 */
export function PostCard({ post }: { post: PostSummary }) {
  const section = sectionByKey(post.section);
  return (
    <article className="group relative rounded-2xl border border-[var(--line)] bg-[var(--card)] p-5 transition hover:-translate-y-0.5 hover:border-brand">
      <div className="mb-2 flex items-center gap-2 text-xs font-semibold uppercase tracking-wide text-brand">
        <span>{section.emoji} {section.label}</span>
        <span className="text-[var(--muted)]">· {post.readingMinutes} min read</span>
      </div>
      <h3 className="text-lg font-bold leading-snug">
        {/* <Link> prefetches the target page when it scrolls into view → instant navigation. */}
        <Link href={`/posts/${post.slug}`} className="after:absolute after:inset-0 group-hover:text-brand">
          {post.title}
        </Link>
      </h3>
      {post.summary && <p className="mt-2 text-sm leading-relaxed text-[var(--muted)]">{post.summary}</p>}
      <div className="mt-3 flex flex-wrap items-center gap-2 text-xs text-[var(--muted)]">
        <time dateTime={post.publishedAt}>{formatDate(post.publishedAt)}</time>
        {post.tags.map((t) => (
          <span key={t} className="rounded-full border border-[var(--line)] px-2 py-0.5">#{t}</span>
        ))}
      </div>
    </article>
  );
}
