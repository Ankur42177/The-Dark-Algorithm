import { notFound } from "next/navigation";
import type { Metadata } from "next";
import { getPost, listPosts } from "@/lib/api";
import { SECTIONS, formatDate, sectionByKey } from "@/lib/sections";
import { ViewBeacon } from "@/components/ViewBeacon";

export const revalidate = 300;
/** Slugs not built at build time are rendered on first request, then cached (new posts work). */
export const dynamicParams = true;

/** Pre-render recent posts of every section at build time. */
export async function generateStaticParams() {
  const pages = await Promise.all(SECTIONS.map((s) => listPosts(s.key, 50)));
  return pages.flatMap((p) => p.items.map((post) => ({ slug: post.slug })));
}

type Props = { params: Promise<{ slug: string }> };

export async function generateMetadata({ params }: Props): Promise<Metadata> {
  const post = await getPost((await params).slug);
  if (!post) return {};
  return {
    title: post.title,
    description: post.summary ?? undefined,
    openGraph: { title: post.title, description: post.summary ?? undefined, type: "article", publishedTime: post.publishedAt },
  };
}

export default async function PostPage({ params }: Props) {
  const post = await getPost((await params).slug);
  if (!post) notFound();
  const section = sectionByKey(post.section);

  return (
    <article className="mx-auto max-w-2xl">
      <p className="mb-3 text-sm font-semibold uppercase tracking-wide text-brand">{section.emoji} {section.label}</p>
      <h1 className="text-3xl font-extrabold leading-tight tracking-tight sm:text-4xl">{post.title}</h1>
      <p className="mt-3 text-sm text-[var(--muted)]">
        By Ankur Kumar · <time dateTime={post.publishedAt}>{formatDate(post.publishedAt)}</time> · {post.readingMinutes} min read
      </p>
      {/* bodyHtml was rendered from Markdown and SANITIZED by content-service at publish time (Phase 4). */}
      <div className="prose-pulse mt-8" dangerouslySetInnerHTML={{ __html: post.bodyHtml }} />
      <div className="mt-8 flex flex-wrap gap-2 text-xs text-[var(--muted)]">
        {post.tags.map((t) => <span key={t} className="rounded-full border border-[var(--line)] px-2 py-0.5">#{t}</span>)}
      </div>
      <ViewBeacon slug={post.slug} />
    </article>
  );
}
