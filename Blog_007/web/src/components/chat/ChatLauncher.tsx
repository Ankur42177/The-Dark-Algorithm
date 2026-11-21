"use client";
/**
 * Harvey's launcher — the ONLY chat code on every page: an avatar button and a greeting bubble.
 * The real chat panel (and its JavaScript) is downloaded with a dynamic import the first time someone opens it
 * (or hovers the avatar), so readers who never chat pay ~0 KB for it (PLAN §14.2). Once loaded, the panel stays
 * mounted while closed, so the conversation — even an answer still streaming — survives close/reopen.
 *
 * Opens from: the floating avatar, the greeting bubble, or anything that dispatches
 *   window.dispatchEvent(new CustomEvent("harvey:open"))      ← the header's "Ask Harvey" pill does this
 */
import { useCallback, useEffect, useRef, useState, type ComponentType } from "react";
import { HarveyAvatar } from "./HarveyAvatar";

type PanelProps = { open: boolean; onClose: () => void; onUnread?: () => void };
export const HARVEY_OPEN = "harvey:open";

let chunk: Promise<ComponentType<PanelProps>> | null = null;
const loadPanel = () => (chunk ??= import("./ChatPanel").then((m) => m.ChatPanel));   // import() = a separate JS chunk

export function ChatLauncher() {
  const [Panel, setPanel] = useState<ComponentType<PanelProps> | null>(null);
  const [open, setOpen] = useState(false);
  const [loading, setLoading] = useState(false);
  const [bubble, setBubble] = useState(false);
  const [unread, setUnread] = useState(false);
  const [returning, setReturning] = useState(false);
  const opener = useRef<HTMLElement | null>(null);
  const avatarRef = useRef<HTMLButtonElement>(null);

  const close = useCallback(() => {
    setOpen(false);
    // Give focus back to whatever opened Harvey (the header pill or the avatar).
    window.setTimeout(() => {
      const back = opener.current?.isConnected ? opener.current : avatarRef.current;
      back?.focus();
    }, 0);
  }, []);
  const markUnread = useCallback(() => setUnread(true), []);

  const openChat = useCallback(async () => {
    const active = document.activeElement;
    opener.current = active instanceof HTMLElement && active !== document.body ? active : null;
    setBubble(false);
    setUnread(false);
    setOpen(true);
    if (!Panel) {
      setLoading(true);
      try {
        const C = await loadPanel();
        setPanel(() => C);
      } catch {
        chunk = null;                                   // network blip: let the next click try again
        setOpen(false);
      } finally {
        setLoading(false);
      }
    }
  }, [Panel]);

  // Say hello shortly after every page load; tidy the bubble away after ~6 s.
  useEffect(() => {
    try { setReturning(!!sessionStorage.getItem("pulse-harvey-chat")); } catch { /* storage blocked */ }
    const show = window.setTimeout(() => setBubble(true), 1400);
    const hide = window.setTimeout(() => setBubble(false), 7600);
    return () => { window.clearTimeout(show); window.clearTimeout(hide); };
  }, []);

  useEffect(() => {
    const onOpen = () => { void openChat(); };
    window.addEventListener(HARVEY_OPEN, onOpen);
    return () => window.removeEventListener(HARVEY_OPEN, onOpen);
  }, [openChat]);

  const prefetch = () => { void loadPanel().catch(() => { chunk = null; }); };

  return (
    <>
      {Panel && <Panel open={open} onClose={close} onUnread={markUnread} />}
      {!(open && Panel) && (
        <div className="fixed bottom-5 right-5 z-40 flex items-end gap-3">
          {bubble && !open && (
            <div className="harvey-bubble glass relative max-w-[15.5rem] rounded-2xl rounded-br-md py-3 pl-4 pr-8 text-sm leading-snug text-[var(--glass-ink)]" role="status">
              <button type="button" onClick={openChat} onPointerEnter={prefetch} className="text-left" data-testid="harvey-bubble">
                {returning ? (
                  <><b className="font-display">Welcome back</b> <span aria-hidden="true">👋</span><br />
                    <span className="text-[var(--glass-muted)]">Pick up where we left off?</span></>
                ) : (
                  <><b className="font-display">Hi, I&apos;m Harvey</b> <span aria-hidden="true">👋</span><br />
                    <span className="text-[var(--glass-muted)]">Ask me about code, AI or football.</span></>
                )}
              </button>
              <button type="button" onClick={() => setBubble(false)} aria-label="Dismiss Harvey's greeting"
                      className="absolute right-1.5 top-1.5 grid h-6 w-6 place-items-center rounded-full text-[var(--glass-muted)] hover:bg-black/10 hover:text-[var(--glass-ink)]">
                <svg width="10" height="10" viewBox="0 0 10 10" aria-hidden="true"><path d="M1.5 1.5l7 7m0-7-7 7" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" /></svg>
              </button>
            </div>
          )}
          <button ref={avatarRef} type="button" onClick={openChat} onPointerEnter={prefetch} onFocus={prefetch}
                  data-testid="harvey-launcher" aria-busy={loading} aria-haspopup="dialog"
                  aria-label={unread ? "Chat with Harvey — new reply" : "Chat with Harvey, Pulse's assistant"}
                  className="harvey-pop group relative grid h-16 w-16 place-items-center rounded-full shadow-[0_18px_40px_-12px_rgb(20_33_61/.65)] transition-transform duration-300 hover:-translate-y-1 hover:scale-105 active:scale-95">
            <span aria-hidden="true" className="harvey-halo absolute inset-0 rounded-full" />
            <HarveyAvatar size={64} blink className={loading ? "animate-pulse" : ""} />
            {unread && <span aria-hidden="true" className="absolute right-0.5 top-0.5 h-3.5 w-3.5 rounded-full border-2 border-white bg-[var(--color-coral)]" />}
          </button>
        </div>
      )}
    </>
  );
}
