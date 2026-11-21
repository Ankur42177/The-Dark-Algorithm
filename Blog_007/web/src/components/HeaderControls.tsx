"use client";
/**
 * The header's two toggles, in one small client island:
 *
 *  1. Theme: System / Light / Dark. The choice lives in localStorage ("pulse-theme"). The inline script in
 *     layout.tsx applies it BEFORE first paint (no flash); this component only changes it afterwards.
 *  2. Play mode ⚽: the football physics overlay. Its code (PlayField) is NOT in this bundle — it is fetched
 *     with import() the first time someone switches Play on. Under prefers-reduced-motion it stays off.
 */
import { useCallback, useEffect, useState, type ComponentType } from "react";
import { HarveyAvatar } from "./chat/HarveyAvatar";
import { HARVEY_OPEN } from "./chat/ChatLauncher";

type Pref = "system" | "light" | "dark";
const KEY = "pulse-theme";

function resolve(pref: Pref): "light" | "dark" {
  if (pref !== "system") return pref;
  return window.matchMedia("(prefers-color-scheme: dark)").matches ? "dark" : "light";
}

function applyTheme(pref: Pref) {
  const root = document.documentElement;
  const theme = resolve(pref);
  root.dataset.theme = theme;
  root.dataset.themePref = pref;
  root.style.colorScheme = theme;
}

const ICONS: Record<Pref, React.ReactNode> = {
  system: (
    <svg width="15" height="15" viewBox="0 0 16 16" aria-hidden="true">
      <rect x="1.8" y="2.5" width="12.4" height="8.5" rx="1.6" fill="none" stroke="currentColor" strokeWidth="1.5" />
      <path d="M5.5 13.8h5M8 11v2.8" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" />
    </svg>
  ),
  light: (
    <svg width="15" height="15" viewBox="0 0 16 16" aria-hidden="true">
      <circle cx="8" cy="8" r="3" fill="currentColor" />
      <path d="M8 1v1.6M8 13.4V15M1 8h1.6M13.4 8H15M3 3l1.1 1.1M11.9 11.9 13 13M3 13l1.1-1.1M11.9 4.1 13 3" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" />
    </svg>
  ),
  dark: (
    <svg width="15" height="15" viewBox="0 0 16 16" aria-hidden="true">
      <path d="M13.6 10.2A6 6 0 0 1 5.8 2.4a6 6 0 1 0 7.8 7.8Z" fill="currentColor" />
    </svg>
  ),
};

function ThemeToggle() {
  const [pref, setPref] = useState<Pref>("system");

  // Read what the inline script already applied (keeps React in sync with the DOM).
  useEffect(() => {
    const p = document.documentElement.dataset.themePref;
    if (p === "light" || p === "dark" || p === "system") setPref(p);
  }, []);

  // In "system" mode, follow the OS live (e.g. macOS switching to dark at sunset).
  useEffect(() => {
    if (pref !== "system") return;
    const mq = window.matchMedia("(prefers-color-scheme: dark)");
    const onChange = () => applyTheme("system");
    mq.addEventListener("change", onChange);
    return () => mq.removeEventListener("change", onChange);
  }, [pref]);

  function choose(p: Pref) {
    setPref(p);
    try { localStorage.setItem(KEY, p); } catch { /* private mode: still works for this page view */ }
    applyTheme(p);
  }

  const order: Pref[] = ["system", "light", "dark"];
  function onKeyDown(e: React.KeyboardEvent) {
    if (e.key !== "ArrowRight" && e.key !== "ArrowLeft") return;
    e.preventDefault();
    const next = order[(order.indexOf(pref) + (e.key === "ArrowRight" ? 1 : 2)) % 3];
    choose(next);
    (e.currentTarget.querySelector(`[data-value="${next}"]`) as HTMLButtonElement | null)?.focus();
  }

  return (
    <div role="radiogroup" aria-label="Colour theme" className="seg" onKeyDown={onKeyDown}>
      {order.map((p) => (
        <button key={p} type="button" role="radio" data-value={p} aria-checked={pref === p} tabIndex={pref === p ? 0 : -1}
                aria-label={`${p[0].toUpperCase()}${p.slice(1)} theme`} title={`${p[0].toUpperCase()}${p.slice(1)}`}
                onClick={() => choose(p)}>
          {ICONS[p]}
        </button>
      ))}
    </div>
  );
}

type FieldProps = { onStop: () => void };

function PlayToggle() {
  const [on, setOn] = useState(false);
  const [Field, setField] = useState<ComponentType<FieldProps> | null>(null);
  const [note, setNote] = useState<string | null>(null);

  const stop = useCallback(() => setOn(false), []);

  async function toggle() {
    if (on) return stop();
    if (window.matchMedia("(prefers-reduced-motion: reduce)").matches) {
      setNote("Play mode is resting: your device asks for reduced motion, so the footballs stay in the kit bag.");
      window.setTimeout(() => setNote(null), 5000);
      return;
    }
    setOn(true);
    if (!Field) {
      const mod = await import("./play/PlayField");   // separate chunk, downloaded only now
      setField(() => mod.PlayField);
    }
  }

  return (
    <>
      <button type="button" onClick={toggle} aria-pressed={on} title="Play mode: rain footballs on the page"
              className="icon-btn play-btn !inline-flex !h-[2.35rem] !w-auto items-center gap-1.5 !px-3 text-[.66rem] font-bold uppercase tracking-[.16em]">
        <svg width="16" height="16" viewBox="0 0 16 16" aria-hidden="true">
          <circle cx="8" cy="8" r="6.8" fill="none" stroke="currentColor" strokeWidth="1.4" />
          <path d="M8 5.3 10.5 7.1l-.95 2.95h-3.1L5.5 7.1z" fill="currentColor" />
          <path d="M8 5.3V1.6M10.5 7.1l3.4-1.1M9.55 10.05l2.1 2.9M6.45 10.05l-2.1 2.9M5.5 7.1 2.1 6" stroke="currentColor" strokeWidth="1.1" />
        </svg>
        <span className="hidden md:inline">Play</span>
      </button>
      {on && Field && <Field onStop={stop} />}
      {note && (
        <p role="status" className="glass fixed left-1/2 top-20 z-50 w-[min(26rem,calc(100vw-2rem))] -translate-x-1/2 rounded-2xl px-4 py-3 text-sm text-[var(--glass-ink)]">
          {note}
        </p>
      )}
    </>
  );
}

/** The header's primary call to action: opens Harvey's chat (ChatLauncher listens for the event). */
function AskHarvey() {
  return (
    <button type="button" onClick={() => window.dispatchEvent(new CustomEvent(HARVEY_OPEN))}
            className="btn-pill !gap-2 !p-1 sm:!py-1.5 sm:!pl-1.5 sm:!pr-5" aria-label="Ask Harvey, Pulse's assistant">
      <span className="grid place-items-center rounded-full bg-white/15 p-0.5"><HarveyAvatar size={26} /></span>
      <span className="hidden sm:inline">Ask Harvey</span>
    </button>
  );
}

export function HeaderControls() {
  return (
    <div className="flex items-center gap-1.5 sm:gap-3">
      <PlayToggle />
      <ThemeToggle />
      <AskHarvey />
    </div>
  );
}
