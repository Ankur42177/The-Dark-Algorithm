import type { FeedItem } from "@/lib/types";
import { formatDate, sectionByKey } from "@/lib/sections";
import { Cover } from "./Cover";
import { Suit } from "./Suit";

/** "CF" from "Cloudflare Blog", "MF" from "Martin Fowler": the card's corner index for outside writers. */
export function initials(name: string): string {
  const words = name.replace(/\(.*?\)/g, "").split(/[\s\-_.]+/).filter((w) => /^[A-Za-z0-9]/.test(w));
  return (words.slice(0, 2).map((w) => w[0]).join("") || "↗").toUpperCase();
}

/**
 * An external article ("Around the web") as a playing card, matching PostCard.
 * Opens the ORIGINAL in a new tab — rel="noopener noreferrer nofollow": the new tab can't reach back into
 * our page, we don't leak our URL, and we don't pass search-engine ranking to links we don't vouch for.
 */
export function WebCard({ item, className = "" }: { item: FeedItem; className?: string }) {
  const section = sectionByKey(item.section);
  const mono = initials(item.sourceName);
  return (
    <article className={`pcard group ${className}`} data-fx="9">
      <div className="pcard-body">
        <div className="pcard-media" style={{ backgroundImage: section.gradient }}>
          <Cover src={item.imageUrl} alt="" section={section.key} label={mono}
                 labelClassName="-translate-y-[60%] text-6xl opacity-80" />
        </div>
        <div className="pcard-shine" aria-hidden="true" />
        <span className="pcard-idx tl" aria-hidden="true">{mono}<Suit kind="WEB" /></span>

        <div className="pcard-panel glass">
          <span className="pcard-idx br" aria-hidden="true">{mono}<Suit kind="WEB" /></span>
          <p className="pcard-kicker truncate">Around the web · {item.sourceName}</p>
          <h3 className="pcard-title">
            <a href={item.url} target="_blank" rel="noopener noreferrer nofollow"
               className="after:absolute after:inset-0 after:z-10 after:content-['']">
              {item.title}
              <span className="sr-only"> (opens {item.sourceName} in a new tab)</span>
            </a>
          </h3>
          {item.excerpt && <p className="pcard-summary">{item.excerpt}</p>}
          <p className="pcard-meta">
            <time dateTime={item.publishedAt}>{formatDate(item.publishedAt)}</time>
            <span className="go" aria-hidden="true">Visit ↗</span>
          </p>
        </div>
      </div>
    </article>
  );
}
