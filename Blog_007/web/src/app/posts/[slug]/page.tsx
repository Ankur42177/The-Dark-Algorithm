import Link from "next/link";
import { notFound } from "next/navigation";
import type { Metadata } from "next";
import { getPost, listPosts } from "@/lib/api";
import { SECTIONS, formatDate, sectionByKey } from "@/lib/sections";
import { ViewBeacon } from "@/components/ViewBeacon";
import { Cover } from "@/components/Cover";
import { PostCard } from "@/components/PostCard";

export const revalidate = 300;
/** Slugs not built at build time are rendered on first request, then cached (new posts work). */
export const dynamicParams = true;

/** Pre-render recent posts of every section at build time. */
export async function generateStaticParams() {
  const pages = await Promise.all(SECTIONS.map((s) => listPosts(s.key, 50)));
  return pages.flatMap((p) => p.items.map((post) => ({ slug: post.slug })));
}

type Props = { params: Promise<{ slug: string }> };

/** Photo credits are paragraphs that START with <em>…</em>; tag them so CSS can set them small and quiet.
 *  Only adds a class attribute to already-sanitized HTML — never injects content. */
const withCaptions = (html: string) => html.replace(/<p>(?=\s*<em>)/g, '<p class="caption">');

export async function generateMetadata({ params }: Props): Promise<Metadata> {
  const post = await getPost((await params).slug);
  if (!post) return {};
  return {
    title: post.title,
    description: post.summary ?? undefined,
    openGraph: {
      title: post.title, description: post.summary ?? undefined, type: "article", publishedTime: post.publishedAt,
      images: post.coverImageUrl ? [post.coverImageUrl] : undefined,
    },
  };
}

export default async function PostPage({ params }: Props) {
  const post = await getPost((await params).slug);
  if (!post) notFound();
  const section = sectionByKey(post.section);
  const more = (await listPosts(post.section, 4)).items.filter((p) => p.slug !== post.slug).slice(0, 3);

  return (
    <>
      <div className="read-progress" aria-hidden="true" />
      <article>
        <header className="mx-auto max-w-3xl px-6 pt-16 text-center">
          <Link href={`/${section.slug}`} className="chip !text-accent">
            {section.label}
          </Link>
          <h1 className="enter mt-6 font-display text-[2.3rem] font-medium leading-[1.1] tracking-[-.02em] text-ink sm:text-[3.2rem]">{post.title}</h1>
          {post.summary && <p className="mx-auto mt-5 max-w-2xl text-lg leading-relaxed text-muted">{post.summary}</p>}
          <p className="mt-6 text-[.68rem] font-bold uppercase tracking-[.2em] text-muted">
            Ankur Kumar · <time dateTime={post.publishedAt}>{formatDate(post.publishedAt)}</time> · {post.readingMinutes} min read
          </p>
        </header>

        {post.coverImageUrl && (
          <div className="mx-auto mt-10 max-w-5xl px-6">
            <div className="relative aspect-[16/9] overflow-hidden rounded-3xl shadow-[0_30px_60px_-30px_rgb(20_33_61/.55)] ring-1 ring-line">
              <Cover src={post.coverImageUrl} alt={post.title} section={post.section} eager width={1280} />
            </div>
          </div>
        )}

        {/* bodyHtml was rendered from Markdown and SANITIZED by content-service at publish time (Phase 4). */}
        <div className="prose-pulse mx-auto mt-10 max-w-[44rem] px-6" dangerouslySetInnerHTML={{ __html: withCaptions(post.bodyHtml) }} />

        {post.tags.length > 0 && (
          <div className="mx-auto mt-10 flex max-w-[44rem] flex-wrap gap-2 px-6">
            {post.tags.map((t) => (
              <span key={t} className="chip">{t.replaceAll("-", " ")}</span>
            ))}
          </div>
        )}
        <ViewBeacon slug={post.slug} />
      </article>

      {more.length > 0 && (
        <section className="mt-24 bg-[var(--band-soft)] py-20">
          <div className="mx-auto max-w-6xl px-6">
            <h2 className="reveal mb-10 text-center font-display text-[2rem] font-medium tracking-tight text-ink">Next card from <span className="hl">{section.label}</span></h2>
            <div className="reveal-stagger grid gap-8 sm:grid-cols-2 lg:grid-cols-3">{more.map((p) => <PostCard key={p.id} post={p} />)}</div>
          </div>
        </section>
      )}
    </>
  );
}
