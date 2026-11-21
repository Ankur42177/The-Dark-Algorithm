/**
 * Starter chips: what Harvey suggests depends on where the reader is. Nothing is sent until a chip is
 * clicked (each question costs a rate-limit slot and an LLM call).
 * `ask` is what gets sent; `label` is what the chip shows (they differ only for "this post").
 */
export type Starter = { label: string; ask: string };

const s = (label: string, ask = label): Starter => ({ label, ask });

export function startersFor(pathname: string, pageTitle: string | null): Starter[] {
  if (pathname.startsWith("/posts/")) {
    const title = pageTitle?.trim();
    return [
      title ? s("Summarise this post", `Summarise the Pulse post "${title.slice(0, 160)}" in three bullets.`) : s("What's new on Pulse?"),
      s("What should I read next?"),
      s("Explain Kafka consumer groups like I'm 12"),
      s("Take me home"),
    ];
  }
  if (pathname.startsWith("/football")) {
    return [
      s("Who's the greatest PL midfielder?"),
      s("What's the latest in football?"),
      s("Explain the offside rule in one minute"),
      s("Take me to Code & Cortex"),
    ];
  }
  if (pathname.startsWith("/tech")) {
    return [
      s("Explain Kafka consumer groups like I'm 12"),
      s("What's new at Google DeepMind?"),
      s("How does RAG actually work?"),
      s("Take me to Football"),
    ];
  }
  if (pathname.startsWith("/contact")) {
    return [s("How do I get the monthly newsletter?"), s("What is Pulse about?"), s("Take me home")];
  }
  return [
    s("What's hot on Pulse right now?"),
    s("Explain Kafka consumer groups like I'm 12"),
    s("Who's the greatest PL midfielder?"),
    s("Take me to Football"),
  ];
}
