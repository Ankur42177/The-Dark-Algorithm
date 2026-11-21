import { useId } from "react";

/**
 * Harvey, Pulse's chatbot: a friendly glassy disc (teal → navy), two eyes, a smile and a little spark.
 * Pure SVG — no image download, crisp at any size, works in both themes.
 * `blink` adds a CSS blink every few seconds (switched off under prefers-reduced-motion).
 */
export function HarveyAvatar({ size = 40, blink = false, className = "" }: { size?: number; blink?: boolean; className?: string }) {
  const id = useId().replace(/:/g, "");
  return (
    <svg width={size} height={size} viewBox="0 0 64 64" aria-hidden="true" className={`${blink ? "harvey-blink" : ""} ${className}`}>
      <defs>
        <radialGradient id={`hb-${id}`} cx="35%" cy="28%" r="80%">
          <stop offset="0" stopColor="#5fd0c9" />
          <stop offset=".45" stopColor="#2b7a7d" />
          <stop offset="1" stopColor="#14213d" />
        </radialGradient>
        <linearGradient id={`hg-${id}`} x1="0" y1="0" x2="0" y2="1">
          <stop offset="0" stopColor="#fff" stopOpacity=".55" />
          <stop offset="1" stopColor="#fff" stopOpacity="0" />
        </linearGradient>
      </defs>
      <circle cx="32" cy="33" r="27" fill={`url(#hb-${id})`} />
      {/* glass rim + specular highlight */}
      <circle cx="32" cy="33" r="26.2" fill="none" stroke="#fff" strokeOpacity=".35" strokeWidth="1.4" />
      <ellipse cx="25" cy="18.5" rx="14" ry="7" fill={`url(#hg-${id})`} />
      {/* face */}
      <g className="harvey-eyes" fill="#fff">
        <rect x="21" y="28" width="6" height="9" rx="3" />
        <rect x="37" y="28" width="6" height="9" rx="3" />
      </g>
      <path d="M24.5 42.5c4.4 4 10.6 4 15 0" fill="none" stroke="#fff" strokeWidth="3" strokeLinecap="round" />
      <circle cx="18.5" cy="40" r="2.6" fill="#ff8f7f" opacity=".55" />
      <circle cx="45.5" cy="40" r="2.6" fill="#ff8f7f" opacity=".55" />
      {/* the spark */}
      <path className="harvey-spark" d="M53 3.5c.6 3.3 1.9 4.6 5.2 5.2-3.3.6-4.6 1.9-5.2 5.2-.6-3.3-1.9-4.6-5.2-5.2 3.3-.6 4.6-1.9 5.2-5.2Z" fill="#ffd27a" />
    </svg>
  );
}
