import Link from "next/link";
import type { FeedItem, PostSummary } from "@/lib/types";
import { sectionByKey } from "@/lib/sections";
import { Cover } from "./Cover";

const prettyTag = (t?: string) => (t ? t.replaceAll("-", " ").replace(/^\w/, (c) => c.toUpperCase()) : "");

export type CatchEntry = { kind: "post"; post: PostSummary } | { kind: "web"; item: FeedItem };

/** The round glass "read" button that sits on a thumbnail (an arrow, not a play triangle: these are articles). */
function ReadButton({ big = false, external = false }: { big?: boolean; external?: boolean }) {
  return (
    <span aria-hidden="true" className={`read-btn ${big ? "h-14 w-14" : "h-9 w-9"}`}>
      <svg width={big ? 20 : 14} height={big ? 20 : 14} viewBox="0 0 16 16">
        <path d={external ? "M5 11 11 5M6 5h5v5" : "M3 8h9.5M8.5 4l4 4-4 4"} fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" />
      </svg>
    </span>
  );
}

function meta(e: CatchEntry) {
  if (e.kind === "web") {
    return {
      href: e.item.url, title: e.item.title, external: true, img: e.item.imageUrl, section: sectionByKey(e.item.section),
      category: `Around the web · ${e.item.sourceName}`,
    };
  }
  const s = sectionByKey(e.post.section);
  return {
    href: `/posts/${e.post.slug}`, title: e.post.title, external: false, img: e.post.coverImageUrl, section: s,
    category: `${s.label}${e.post.tags[0] ? ` · ${prettyTag(e.post.tags[0])}` : ""}`,
  };
}

function EntryLink({ e, className, children }: { e: CatchEntry; className: string; children: React.ReactNode }) {
  const m = meta(e);
  return m.external
    ? <a href={m.href} target="_blank" rel="noopener noreferrer nofollow" className={className}>{children}<span className="sr-only"> (opens in a new tab)</span></a>
    : <Link href={m.href} className={className}>{children}</Link>;
}

/**
 * "Did you catch this?" — one featured story with a split two-photo collage, then a list of rows
 * (title + category on the left, thumbnail with a glass arrow on the right). Pure Server Component: zero JS.
 */
export function CatchUp({ featured, partner, rows }: { featured: CatchEntry; partner?: CatchEntry; rows: CatchEntry[] }) {
  const f = meta(featured);
  const p = partner ? meta(partner) : null;
  return (
    <section className="py-14" aria-labelledby="catch-title">
      <div className="mx-auto max-w-6xl px-6">
        <div className="reveal mb-8">
          <p className="kicker">Catch-up</p>
          <h2 id="catch-title" className="mt-2 font-display text-[1.9rem] font-medium leading-tight tracking-tight text-ink sm:text-[2.3rem]">
            Did you <span className="hl">catch</span> this? <span aria-hidden="true">👀</span>
          </h2>
        </div>

        <div className="grid gap-10 lg:grid-cols-[1.12fr_1fr] lg:gap-12">
          {/* featured */}
          <EntryLink e={featured} className="catch-feature group reveal block self-start lg:sticky lg:top-24">
            <div className="catch-collage">
              <div className="relative overflow-hidden" style={{ backgroundImage: f.section.gradient }}>
                <Cover src={f.img} alt="" section={f.section.key} eager width={1280} className="catch-img" />
              </div>
              {p && (
                <div className="relative overflow-hidden" style={{ backgroundImage: p.section.gradient }}>
                  <Cover src={p.img} alt="" section={p.section.key} className="catch-img" />
                </div>
              )}
              <span className="absolute bottom-4 right-4 z-10"><ReadButton big external={f.external} /></span>
            </div>
            <h3 className="mt-5 font-display text-[1.6rem] font-semibold leading-[1.15] tracking-tight text-ink transition-colors group-hover:text-accent sm:text-[1.95rem]">
              {f.title}
            </h3>
            <p className="mt-2 text-sm text-muted">{f.category}</p>
          </EntryLink>

          {/* rows */}
          <ul className="reveal-stagger divide-y divide-[var(--line)] border-y border-line">
            {rows.map((e) => {
              const m = meta(e);
              return (
                <li key={e.kind === "post" ? e.post.id : e.item.id}>
                  <EntryLink e={e} className="catch-row group">
                    <div className="min-w-0">
                      <p className="line-clamp-3 font-display text-[1.06rem] font-semibold leading-snug text-ink transition-colors group-hover:text-accent">{m.title}</p>
                      <p className="mt-1.5 truncate text-[.8rem] text-muted">{m.category}</p>
                    </div>
                    <div className="catch-thumb" style={{ backgroundImage: m.section.gradient }}>
                      <Cover src={m.img} alt="" section={m.section.key} labelClassName="text-xl opacity-70" className="catch-img" />
                      <span className="absolute bottom-2 right-2 z-10"><ReadButton external={m.external} /></span>
                    </div>
                  </EntryLink>
                </li>
              );
            })}
          </ul>
        </div>
      </div>
    </section>
  );
}
