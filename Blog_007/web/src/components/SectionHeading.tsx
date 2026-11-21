/** Centered heading with ONE highlighted word (coral underline that draws itself in as you scroll). */
export function SectionHeading({ before, highlight, after = "", kicker, sub, as: Tag = "h2", align = "center" }: {
  before: string; highlight: string; after?: string; kicker?: string; sub?: string; as?: "h1" | "h2"; align?: "center" | "left";
}) {
  const centered = align === "center";
  return (
    <div className={`reveal mb-10 ${centered ? "text-center" : ""}`}>
      {kicker && <p className="kicker mb-3">{kicker}</p>}
      <Tag className="font-display text-[2rem] font-medium leading-[1.1] tracking-tight text-ink sm:text-[2.6rem]">
        {before}<span className="hl">{highlight}</span>{after}
      </Tag>
      {sub && <p className={`mt-4 max-w-xl text-[1.02rem] leading-relaxed text-muted ${centered ? "mx-auto" : ""}`}>{sub}</p>}
    </div>
  );
}
