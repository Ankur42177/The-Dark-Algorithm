import { notFound } from "next/navigation";
import type { Metadata } from "next";
import { listFeed, listPosts, listSections } from "@/lib/api";
import { hotCards } from "@/lib/hot";
import { SECTIONS, sectionBySlug } from "@/lib/sections";
import { PostCard } from "@/components/PostCard";
import { LoadMore } from "@/components/LoadMore";
import { FeedList } from "@/components/FeedList";
import { HotHand } from "@/components/HotHand";
import { NewsletterForm } from "@/components/forms/NewsletterForm";

export const revalidate = 300;
/** Only /tech and /football exist (/ai redirects to /tech). Anything else is a 404 without touching the backend. */
export const dynamicParams = false;

/** Build these pages at build time (Static Site Generation). */
export function generateStaticParams() {
  return SECTIONS.map((s) => ({ section: s.slug }));
}

type Props = { params: Promise<{ section: string }> };

export async function generateMetadata({ params }: Props): Promise<Metadata> {
  const s = sectionBySlug((await params).section);
  return s ? { title: s.label, description: s.blurb } : {};
}

/** First page = 12 cards (4 rows of 3); "Deal more" adds 9 at a time so rows stay full. */
const FIRST_PAGE = 12;

export default async function SectionPage({ params }: Props) {
  const s = sectionBySlug((await params).section);
  if (!s) notFound();
  const [page, feed, trending, counts] = await Promise.all([
    listPosts(s.key, FIRST_PAGE), listFeed(s.key, 6), hotCards(s.key), listSections(),
  ]);
  const count = counts
    .filter((c) => c.section === s.key || (s.key === "TECH" && c.section === "AI"))
    .reduce((n, c) => n + c.posts, 0) || page.items.length;
  const isTech = s.key === "TECH";

  return (
    <>
      <section className={`${isTech ? "mood-tech" : "mood-football"} relative overflow-clip`}>
        <div aria-hidden="true" className="blob -left-24 -top-32 h-[30rem] w-[30rem]"
             style={{ "--blob": isTech ? "rgb(43 122 125 / .28)" : "rgb(47 154 124 / .28)", "--dur": "24s" } as React.CSSProperties} />
        <div aria-hidden="true" className="blob -bottom-40 -right-20 h-[32rem] w-[32rem]"
             style={{ "--blob": isTech ? "rgb(90 96 200 / .2)" : "rgb(226 103 90 / .16)", "--dx": "-50px", "--dur": "30s" } as React.CSSProperties} />
        <div className="relative mx-auto max-w-6xl px-6 py-20 text-center md:py-28">
          <p className="kicker enter">{s.label} · {count} essays in the deck</p>
          <h1 className="enter mx-auto mt-5 max-w-3xl font-display text-[2.7rem] font-medium leading-[1.04] tracking-[-.02em] text-ink sm:text-[4rem]"
              style={{ "--delay": ".08s" } as React.CSSProperties}>
            {s.heading[0]}<span className="hl">{s.heading[1]}</span>{s.heading[2]}
          </h1>
          <p className="enter mx-auto mt-6 max-w-2xl text-[1.08rem] leading-relaxed text-fg" style={{ "--delay": ".16s" } as React.CSSProperties}>{s.blurb}</p>
          <div className="enter mt-9 flex flex-wrap justify-center gap-3" style={{ "--delay": ".24s" } as React.CSSProperties}>
            {(isTech
              ? ["Distributed systems", "Kafka", "Redis", "Databases", "LLMs", "Agents", "RAG"]
              : ["Tactics", "Premier League", "Champions League", "Legends", "The basics"]
            ).map((t) => <span key={t} className="chip">{t}</span>)}
          </div>
        </div>
      </section>
      <HotHand cards={trending} label={isTech ? "Most-read circuits" : "Crowd favourites"}
               sub={isTech ? "The Code & Cortex essays people can't stop opening. Hover the hand." : "The football essays the crowd keeps coming back to. Hover the hand."} />

      <div className="mx-auto max-w-6xl px-6 py-20">
        {page.items.length === 0 ? (
          <div className="rounded-3xl border border-dashed border-line px-6 py-20 text-center">
            <p className="font-display text-2xl text-ink">The deck is being shuffled.</p>
            <p className="mt-2 text-muted">New cards land here soon — grab the monthly wildcard so you don&apos;t miss them.</p>
          </div>
        ) : (
          <div className="reveal-stagger grid gap-8 sm:grid-cols-2 lg:grid-cols-3">
            {page.items.map((p, i) => <PostCard key={p.id} post={p} eager={i < 3} />)}
          </div>
        )}
        {/* Only this small island ships JavaScript. */}
        <LoadMore section={s.key} initialCursor={page.nextCursor} />

        <FeedList items={feed.items}
                  title={isTech ? "Straight from the people building it" : "Sharp eyes from the stands"}
                  sub={isTech
                    ? "Fresh posts from official engineering and AI research blogs, picked for depth over noise."
                    : "The best tactical writing on the web this week — opens on the original site."} />

        <div className="reveal mt-24 grid items-center gap-8 rounded-[32px] border border-line bg-[var(--band-soft)] p-8 sm:p-12 md:grid-cols-[1.1fr_1fr]">
          <div>
            <p className="kicker">The monthly wildcard</p>
            <p className="mt-3 font-display text-[1.8rem] font-medium leading-tight tracking-tight text-ink">
              One random essay a month. <span className="hl">Zero</span> spam.
            </p>
            <p className="mt-3 text-sm leading-relaxed text-muted">A surprise card from the archive, once a month. One-click unsubscribe.</p>
          </div>
          <NewsletterForm compact />
        </div>
      </div>
    </>
  );
}
