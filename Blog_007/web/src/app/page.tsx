import Link from "next/link";
import { getPost, listFeed, listPosts, listSections } from "@/lib/api";
import { hotCards } from "@/lib/hot";
import { SECTIONS } from "@/lib/sections";
import type { FeedItem, PostSummary, SectionKey } from "@/lib/types";
import { PostCard } from "@/components/PostCard";
import { WebCard } from "@/components/WebCard";
import { HeroDeck } from "@/components/HeroDeck";
import { HotHand } from "@/components/HotHand";
import { ArenaDoors } from "@/components/ArenaDoors";
import { SectionHeading } from "@/components/SectionHeading";
import { NewsletterBlock } from "@/components/NewsletterBlock";
import { ContactSection } from "@/components/ContactSection";
import { TrendingRail } from "@/components/TrendingRail";
import { CatchUp, type CatchEntry } from "@/components/CatchUp";

/**
 * The landing page. Rendered on the server and cached as static HTML (Incremental Static Regeneration):
 * readers get pre-built HTML; it is refreshed when a tagged fetch is revalidated or after 5 min.
 */
export const revalidate = 300;

/** The owner's pick: always the top card of the hero deck, even when newer posts exist. */
const COVER_STAR = "kevin-de-bruyne-the-making-of-the-premier-leagues-greatest-midfielder";

type DeckEntry = { kind: "post"; post: PostSummary } | { kind: "web"; item: FeedItem };

/** Post, post, web, post, web… — Pulse first, the wider web shuffled in. */
function shuffleIn(posts: PostSummary[], web: FeedItem[]): DeckEntry[] {
  const out: DeckEntry[] = [];
  let w = 0;
  posts.forEach((post, i) => {
    out.push({ kind: "post", post });
    if (i >= 1 && w < web.length) out.push({ kind: "web", item: web[w++] });
  });
  while (w < web.length) out.push({ kind: "web", item: web[w++] });
  return out;
}

export default async function Home() {
  // Fetch everything IN PARALLEL (Promise.all), not one after another.
  const [star, latest, counts, trending, feed] = await Promise.all([
    getPost(COVER_STAR).catch(() => null),
    listPosts(undefined, 12),
    listSections(),
    hotCards(),
    listFeed(undefined, 8),
  ]);
  // Below-the-fold sections get their own fetches (cached + tagged like the rest), so the hero and the
  // "Hot right now" hand above stay exactly as they are.
  const [footballPage, techPage, webPage] = await Promise.all([
    listPosts("FOOTBALL", 16).catch(() => ({ items: [], nextCursor: null })),
    listPosts("TECH", 10).catch(() => ({ items: [], nextCursor: null })),
    listFeed(undefined, 12).catch(() => ({ items: [], nextCursor: null })),
  ]);

  // "AI" is legacy (merged into Tech): count it under TECH until every cache has caught up.
  const perSection: Partial<Record<SectionKey, number>> = {};
  for (const c of counts) {
    const key: SectionKey = c.section === "AI" ? "TECH" : c.section;
    perSection[key] = (perSection[key] ?? 0) + c.posts;
  }
  const total = counts.reduce((n, c) => n + c.posts, 0);

  const others = latest.items.filter((p) => p.slug !== COVER_STAR);
  // Prefer web articles that bring their own image; fill up with the rest.
  const web = [...feed.items.filter((f) => f.imageUrl), ...feed.items.filter((f) => !f.imageUrl)].slice(0, 3);
  const deck: DeckEntry[] = [
    ...(star ? [{ kind: "post" as const, post: star }] : []),
    ...shuffleIn(others.slice(0, star ? 4 : 5), web),
  ];
  const fresh = others.slice(0, 3);

  // Trending rail: both arenas interleaved (football, tech, …). Skips KDB (the hero's star) and the three
  // "Fresh off the press" cards further down, so no story appears twice on the page.
  const taken = new Set([COVER_STAR, ...fresh.map((p) => p.slug)]);
  const fb = footballPage.items.filter((p) => !taken.has(p.slug));
  const tech = techPage.items.filter((p) => !taken.has(p.slug));
  const rail: PostSummary[] = [];
  for (let i = 0; i < 8 && rail.length < 10; i++) {
    if (fb[i]) rail.push(fb[i]);
    if (tech[i]) rail.push(tech[i]);
  }
  // "Did you catch this?": older football photo stories + leftover tech + official-blog pieces with images,
  // dealt round-robin so the list stays mixed whatever each pool holds.
  const railIds = new Set(rail.map((p) => p.id));
  const fbRest = fb.filter((p) => !railIds.has(p.id) && p.coverImageUrl);
  const pools: CatchEntry[][] = [
    fbRest.slice(2).map((post) => ({ kind: "post" as const, post })),
    tech.filter((p) => !railIds.has(p.id)).map((post) => ({ kind: "post" as const, post })),
    webPage.items.filter((w) => w.imageUrl).slice(0, 2).map((item) => ({ kind: "web" as const, item })),
  ];
  const catchRows: CatchEntry[] = [];
  for (let i = 0; catchRows.length < 6 && pools.some((pool) => pool.length > i); i++) {
    for (const pool of pools) if (pool[i] && catchRows.length < 6) catchRows.push(pool[i]);
  }
  const catchFeatured: CatchEntry | null = fbRest[0] ? { kind: "post", post: fbRest[0] } : null;
  const catchPartner: CatchEntry | undefined = fbRest[1] ? { kind: "post", post: fbRest[1] } : undefined;

  return (
    <>
      {/* ---------------------------------------------------------------- hero */}
      <section className="band-home relative overflow-clip">
        <div aria-hidden="true" className="blob -right-32 -top-40 h-[34rem] w-[34rem]" style={{ "--blob": "rgb(255 255 255 / .55)" } as React.CSSProperties} />
        <div aria-hidden="true" className="blob -bottom-48 left-[20%] h-[28rem] w-[28rem]" style={{ "--blob": "rgb(43 122 125 / .22)", "--dur": "28s", "--dx": "-60px" } as React.CSSProperties} />
        <div aria-hidden="true" className="blob right-[30%] top-[30%] h-72 w-72" style={{ "--blob": "rgb(226 103 90 / .16)", "--dur": "19s", "--dy": "50px" } as React.CSSProperties} />

        <div className="relative mx-auto grid max-w-6xl items-center gap-14 px-6 pb-20 pt-14 md:grid-cols-[1.08fr_1fr] md:pb-24 md:pt-20">
          {/* z-10: when the deck fans out, the scattered cards slide UNDER the headline instead of covering it */}
          <div className="relative z-10">
            <p className="kicker enter">{SECTIONS[0].label} × {SECTIONS[1].label} · by Ankur Kumar</p>
            <h1 className="enter mt-5 font-display text-[2.9rem] font-medium leading-[1.02] tracking-[-.025em] text-ink sm:text-[4.1rem]" style={{ "--delay": ".08s" } as React.CSSProperties}>
              Systems that scale.<br />Football that <span className="hl">sings</span>.
            </h1>
            <p className="enter mt-7 max-w-[30rem] text-[1.08rem] leading-relaxed text-fg" style={{ "--delay": ".16s" } as React.CSSProperties}>
              Deep dives into distributed systems and the AI riding on top of them, tactical teardowns of the beautiful
              game — plus the sharpest reads from official engineering blogs. Shuffle the deck and pick a card.
            </p>
            <div className="enter mt-10 flex flex-wrap items-center gap-6" style={{ "--delay": ".24s" } as React.CSSProperties}>
              <Link href="#arena" className="btn-pill">Pick your arena <span className="arrow">→</span></Link>
              <Link href="#newsletter" className="btn-ghost">Get the monthly wildcard <span className="arrow">→</span></Link>
            </div>
            <dl className="enter mt-12 flex gap-10" style={{ "--delay": ".32s" } as React.CSSProperties}>
              <div><dt className="text-[.64rem] font-bold uppercase tracking-[.2em] text-muted">Essays</dt><dd className="mt-1 font-display text-3xl font-semibold text-ink">{total || "—"}</dd></div>
              <div><dt className="text-[.64rem] font-bold uppercase tracking-[.2em] text-muted">Arenas</dt><dd className="mt-1 font-display text-3xl font-semibold text-ink">{SECTIONS.length}</dd></div>
              <div><dt className="text-[.64rem] font-bold uppercase tracking-[.2em] text-muted">Hype</dt><dd className="mt-1 font-display text-3xl font-semibold text-ink">0%</dd></div>
            </dl>
          </div>

          {deck.length > 0 && (
            <div className="enter md:pl-6" style={{ "--delay": ".2s" } as React.CSSProperties}>
              <HeroDeck labels={deck.map((d) => (d.kind === "post" ? d.post.title : `${d.item.title} (${d.item.sourceName})`))}>
                {deck.map((d, i) => d.kind === "post"
                  ? <PostCard key={d.post.id} post={d.post} eager={i < 2} />
                  : <WebCard key={d.item.id} item={d.item} />)}
              </HeroDeck>
            </div>
          )}
        </div>
      </section>

      <HotHand cards={trending} />

      <TrendingRail posts={rail} />
      {catchFeatured && <CatchUp featured={catchFeatured} partner={catchPartner} rows={catchRows} />}

      {/* ---------------------------------------------------------------- arenas */}
      <section id="arena" className="scroll-mt-24 py-14">
        <div className="mx-auto max-w-6xl px-6">
          <SectionHeading kicker="Choose your arena" before="Two worlds. One " highlight="obsession" after="."
                          sub="Pick a door. Each one opens onto its own deck of essays, plus the best of the web in that world." />
          <ArenaDoors counts={perSection} />
        </div>
      </section>

      {/* ---------------------------------------------------------------- latest */}
      {fresh.length > 0 && (
        <section className="bg-[var(--band-soft)] py-16">
          <div className="mx-auto max-w-6xl px-6">
            <SectionHeading kicker="Latest" before="Fresh off the " highlight="press"
                            sub="The newest cards in the deck — still warm from the printer." />
            <div className="reveal-stagger grid gap-8 sm:grid-cols-2 lg:grid-cols-3">
              {fresh.map((p) => <PostCard key={p.id} post={p} />)}
            </div>
            <div className="mt-12 flex flex-wrap justify-center gap-4">
              {SECTIONS.map((s) => (
                <Link key={s.slug} href={`/${s.slug}`} className="chip !px-5 !py-2.5">All of {s.label} <span aria-hidden="true">→</span></Link>
              ))}
            </div>
          </div>
        </section>
      )}

      <NewsletterBlock />
      <ContactSection />
    </>
  );
}
