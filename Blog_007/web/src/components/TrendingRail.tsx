import Link from "next/link";
import type { PostSummary } from "@/lib/types";
import { sectionByKey } from "@/lib/sections";
import { Cover } from "./Cover";
import { RailScroller } from "./RailScroller";

const WEEK = 7 * 24 * 3600 * 1000;
const prettyTag = (t?: string) => (t ? t.replaceAll("-", " ").replace(/^\w/, (c) => c.toUpperCase()) : "");

/** Little clock glyph for the reading-time badge. */
function Clock() {
  return (
    <svg width="12" height="12" viewBox="0 0 16 16" aria-hidden="true">
      <circle cx="8" cy="8" r="6.3" fill="none" stroke="currentColor" strokeWidth="1.6" />
      <path d="M8 4.6V8l2.3 1.5" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" />
    </svg>
  );
}

/**
 * "Trending" rail (inspired by sports-app video rails): tall photo cards in a horizontal scroll-snap row,
 * title over the image, a glass reading-time badge, a "NEW" pill for posts younger than a week.
 * A Server Component; only RailScroller's ‹ › buttons ship JavaScript.
 */
export function TrendingRail({ posts }: { posts: PostSummary[] }) {
  if (posts.length === 0) return null;
  const now = Date.now();
  return (
    <section className="py-14" aria-labelledby="rail-title">
      <div className="mx-auto max-w-6xl px-6">
        <div className="reveal mb-7 max-w-xl md:pr-28">
          <p className="kicker flex items-center gap-2">
            <svg width="16" height="16" viewBox="0 0 16 16" aria-hidden="true"><path d="M1.5 11.5 6 7l3 3 5.5-5.5M10.5 4.5h4v4" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" /></svg>
            On the rise
          </p>
          <h2 id="rail-title" className="mt-2 font-display text-[1.9rem] font-medium leading-tight tracking-tight text-ink sm:text-[2.3rem]">
            Trending in the <span className="hl">stands</span> — and the server room
          </h2>
        </div>
        <RailScroller label="Trending essays">
          {posts.map((p, i) => {
            const s = sectionByKey(p.section);
            const fresh = now - new Date(p.publishedAt).getTime() < WEEK;
            return (
              <Link key={p.id} href={`/posts/${p.slug}`} className="rail-card group">
                <div className="rail-media" aria-hidden="true" style={{ backgroundImage: s.gradient }}>
                  <Cover src={p.coverImageUrl} alt="" section={s.key} eager={i < 3} labelClassName="-translate-y-1/3 text-5xl opacity-70" />
                </div>
                {fresh && <span className="rail-new">New</span>}
                <span className="rail-badge"><Clock />{p.readingMinutes} min</span>
                <div className="rail-body">
                  <p className="rail-cat">{s.label}{p.tags[0] ? ` · ${prettyTag(p.tags[0])}` : ""}</p>
                  <h3 className="rail-title">{p.title}</h3>
                </div>
              </Link>
            );
          })}
        </RailScroller>
      </div>
    </section>
  );
}
