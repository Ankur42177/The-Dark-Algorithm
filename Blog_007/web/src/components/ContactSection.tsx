import { ContactForm } from "./forms/ContactForm";

/** Home-page contact block (also rendered by /contact). */
export function ContactSection({ as: Tag = "h2" }: { as?: "h1" | "h2" }) {
  return (
    <section id="contact" className="scroll-mt-24 py-12" aria-labelledby="contact-title">
      <div className="mx-auto grid max-w-6xl gap-12 px-6 md:grid-cols-[1fr_1.35fr] md:items-start">
        <div className="reveal md:sticky md:top-28">
          <p className="kicker">Say hello</p>
          <Tag id="contact-title" className="mt-3 font-display text-[2.1rem] font-medium leading-[1.08] tracking-tight text-ink sm:text-[2.7rem]">
            Got a hot take? <span className="hl">Pass</span> it over.
          </Tag>
          <p className="mt-5 max-w-sm text-[1.05rem] leading-relaxed text-muted">
            Spotted a bug in my Kafka diagram, think the back three is overrated, or want to talk shop?
            My inbox is open — and a real human reads it.
          </p>
          <ul className="mt-8 space-y-3 text-sm text-fg">
            <li className="flex items-center gap-3"><span className="grid h-8 w-8 place-items-center rounded-full bg-[var(--band-soft)] text-accent" aria-hidden="true">✓</span>Replies in a few days, usually faster</li>
            <li className="flex items-center gap-3"><span className="grid h-8 w-8 place-items-center rounded-full bg-[var(--band-soft)] text-accent" aria-hidden="true">✓</span>Corrections credited in the post</li>
            <li className="flex items-center gap-3"><span className="grid h-8 w-8 place-items-center rounded-full bg-[var(--band-soft)] text-accent" aria-hidden="true">✓</span>Never shared, never sold</li>
          </ul>
        </div>
        <div className="reveal"><ContactForm /></div>
      </div>
    </section>
  );
}
