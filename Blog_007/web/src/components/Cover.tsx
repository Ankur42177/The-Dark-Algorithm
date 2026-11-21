import type { SectionKey } from "@/lib/types";
import { sectionByKey } from "@/lib/sections";

/**
 * A post's cover: the real photo when there is one, otherwise generated art in the section's colours.
 * Plain <img loading="lazy">, not next/image: photos come straight from Wikimedia Commons, which asks automated
 * clients (like Next's server-side image optimizer) to identify themselves; the reader's browser is fine.
 */
/**
 * Ask Wikimedia for a resized thumbnail instead of the full original (often 1+ MB): cards need ~960 px.
 * Wikimedia serves thumbnails at standard widths, so we only ever ask for 960 or 1280.
 *   upload.wikimedia.org/wikipedia/commons/a/ab/File.jpg      → …/commons/thumb/a/ab/File.jpg/960px-File.jpg
 *   …/commons/thumb/a/ab/File.jpg/1280px-File.jpg              → …/960px-File.jpg
 */
function sized(url: string, width: 960 | 1280): string {
  const original = url.match(/^https:\/\/upload\.wikimedia\.org\/wikipedia\/commons\/([0-9a-f])\/([0-9a-f]{2})\/([^/]+)$/);
  if (original) {
    const [, a, ab, file] = original;
    if (/\.(jpe?g|png|webp)$/i.test(file)) return `https://upload.wikimedia.org/wikipedia/commons/thumb/${a}/${ab}/${file}/${width}px-${file}`;
  }
  if (/^https:\/\/(upload|thumb)\.wikimedia\.org\/.+\/thumb\//.test(url)) return url.replace(/\/\d+px-([^/]+)$/, `/${width}px-$1`);
  return url;
}

/** A small deterministic hash: the same title always gets the same art. */
function seed(text: string): number {
  let h = 2166136261;
  for (let i = 0; i < text.length; i++) h = Math.imul(h ^ text.charCodeAt(i), 16777619);
  return h >>> 0;
}

export function Cover({ src, alt, section, className = "", eager = false, width = 960, labelClassName = "text-4xl", label }: {
  src: string | null; alt: string; section: SectionKey; className?: string; eager?: boolean; width?: 960 | 1280;
  /** Size/position of the monogram on generated art (cards push it up, above their glass panel). */
  labelClassName?: string;
  /** Text drawn on generated art; defaults to the section monogram. */
  label?: string;
}) {
  const s = sectionByKey(section);
  if (src) {
    return (
      // eslint-disable-next-line @next/next/no-img-element
      <img src={sized(src, width)} alt={alt} loading={eager ? "eager" : "lazy"} decoding="async"
           className={`h-full w-full object-cover object-[50%_22%] ${className}`} referrerPolicy="no-referrer" />
    );
  }
  const h = seed(alt);
  const pick = (shift: number, min: number, span: number) => `${min + ((h >>> shift) % span)}%`;
  const art = {
    "--x1": pick(0, 8, 40), "--y1": pick(4, 8, 40), "--r1": pick(8, 12, 14),
    "--x2": pick(12, 55, 40), "--y2": pick(16, 50, 45), "--r2": pick(20, 18, 16),
    "--angle": `${(h >>> 24) % 180}deg`,
  } as React.CSSProperties;
  return (
    <div {...(alt ? { role: "img", "aria-label": alt } : { "aria-hidden": true })} className={`relative grid h-full w-full place-items-center ${className}`}
         style={{ backgroundColor: "#1d2b4a", backgroundImage: s.gradient, filter: `hue-rotate(${(h % 41) - 20}deg)` }}>
      <div className="cover-art absolute inset-0" aria-hidden="true" style={art} />
      <span aria-hidden="true" className={`relative font-display font-semibold tracking-widest text-white/90 ${labelClassName}`}>{label ?? s.monogram}</span>
    </div>
  );
}
