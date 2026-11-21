import { NewsletterForm } from "./forms/NewsletterForm";

/** The sealed envelope: pure CSS shapes; on hover the mystery card slides up and the wax seal wobbles. */
function Envelope() {
  return (
    <div className="envelope-wrap relative px-4 pt-16" aria-hidden="true">
      <span className="sparkle left-[8%] top-[18%] text-lg">✦</span>
      <span className="sparkle right-[10%] top-[8%] text-2xl [animation-delay:1.1s]">✦</span>
      <span className="sparkle right-[2%] top-[52%] text-sm [animation-delay:2s]">✦</span>
      <div className="envelope">
        <div className="env-back" />
        <div className="env-flap" />
        <div className="env-card">
          <div className="absolute inset-[7px] rounded-[10px] border border-[#d9cfbd]" />
          <span className="absolute left-3 top-2.5 font-display text-sm font-bold text-[#b9473a]">?<br /><span className="text-[.7rem]">♦</span></span>
          <div className="grid h-[60%] place-items-center">
            <span className="font-display text-6xl font-bold text-[#14213d]">?</span>
          </div>
        </div>
        <div className="env-front" />
        <div className="env-seal">P</div>
      </div>
      <p className="mt-6 text-center font-display text-[.72rem] font-bold uppercase tracking-[.3em] text-muted">Draw · once a month</p>
    </div>
  );
}

/**
 * "The Monthly Wildcard": one hand-picked article, once a month, chosen at random from the archive.
 * A Server Component; only the form inside is a client island.
 */
export function NewsletterBlock() {
  return (
    <section id="newsletter" className="scroll-mt-24 py-14" aria-labelledby="nl-title">
      <div className="mx-auto max-w-6xl px-6">
        <div className="reveal relative overflow-hidden rounded-[36px] border border-line bg-[var(--band-soft)] px-6 py-12 sm:px-12 sm:py-16">
          <div aria-hidden="true" className="blob -left-24 -top-24 h-80 w-80" style={{ "--blob": "rgb(226 103 90 / .16)", "--dur": "26s" } as React.CSSProperties} />
          <div aria-hidden="true" className="blob -bottom-32 right-0 h-96 w-96" style={{ "--blob": "rgb(43 122 125 / .18)", "--dx": "-50px" } as React.CSSProperties} />
          <div className="relative grid items-center gap-12 md:grid-cols-[1fr_1.1fr]">
            <Envelope />
            <div>
              <p className="kicker">The monthly wildcard</p>
              <h2 id="nl-title" className="mt-3 font-display text-[2.1rem] font-medium leading-[1.08] tracking-tight text-ink sm:text-[2.7rem]">
                One <span className="hl">surprise</span> a month
              </h2>
              <p className="mt-5 max-w-md text-[1.05rem] leading-relaxed text-fg">
                One hand-picked article, once a month, chosen at random from the archive — a surprise in your inbox.
                No spam, one-click unsubscribe.
              </p>
              <div className="mt-8 max-w-lg"><NewsletterForm /></div>
            </div>
          </div>
        </div>
      </div>
    </section>
  );
}
