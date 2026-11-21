import Link from "next/link";
import type { PostSummary } from "@/lib/types";
import { formatDate, sectionByKey } from "@/lib/sections";
import { Cover } from "./Cover";
import { Suit } from "./Suit";

/**
 * A post as a premium playing card: full-bleed cover, a frosted "liquid glass" panel, corner indices.
 *
 * A Server Component (no "use client"): it ships ZERO JavaScript. The tilt + pointer-following shine come
 * from ONE delegated listener (CardFx.tsx) that writes CSS variables onto whichever [data-fx] card is hovered;
 * everything else (sweep, lift, parallax) is pure CSS in globals.css (.pcard*).
 */
export function PostCard({ post, eager = false, className = "" }: { post: PostSummary; eager?: boolean; className?: string }) {
  const section = sectionByKey(post.section);
  return (
    <article className={`pcard group ${className}`} data-fx="9">
      <div className="pcard-body">
        <div className="pcard-media" style={{ backgroundImage: section.gradient }}>
          <Cover src={post.coverImageUrl} alt="" section={section.key} eager={eager}
                 label={section.monogram} labelClassName="-translate-y-[60%] text-6xl opacity-80" />
        </div>
        <div className="pcard-shine" aria-hidden="true" />
        <span className="pcard-idx tl" aria-hidden="true">{section.monogram}<Suit kind={section.key} /></span>

        <div className="pcard-panel glass">
          <span className="pcard-idx br" aria-hidden="true">{section.monogram}<Suit kind={section.key} /></span>
          <p className="pcard-kicker">{section.label} · {post.readingMinutes} min</p>
          <h3 className="pcard-title">
            {/* The whole card is the link (after:inset-0) — one tab stop, one accessible name. */}
            <Link href={`/posts/${post.slug}`} className="after:absolute after:inset-0 after:z-10 after:content-['']">
              {post.title}
            </Link>
          </h3>
          {post.summary && <p className="pcard-summary">{post.summary}</p>}
          <p className="pcard-meta">
            <time dateTime={post.publishedAt}>{formatDate(post.publishedAt)}</time>
            <span className="go" aria-hidden="true">Read →</span>
          </p>
        </div>
      </div>
    </article>
  );
}
