"use client";
/**
 * The ONLY chat code on every page: a small button. The real chat panel (and its JavaScript) is downloaded
 * with a dynamic import the first time the reader clicks — readers who never chat pay ~0 KB (PLAN §14.2).
 */
import { useState, type ComponentType } from "react";

type PanelProps = { onClose: () => void };

export function ChatLauncher() {
  const [Panel, setPanel] = useState<ComponentType<PanelProps> | null>(null);
  const [open, setOpen] = useState(false);
  const [loading, setLoading] = useState(false);

  async function openChat() {
    setOpen(true);
    if (!Panel) {
      setLoading(true);
      // import() = a separate JS chunk, fetched only now.
      const mod = await import("./ChatPanel");
      setPanel(() => mod.ChatPanel);
      setLoading(false);
    }
  }

  return (
    <>
      {open && Panel && <Panel onClose={() => setOpen(false)} />}
      {!open && (
        <button onClick={openChat} aria-label="Ask Pulse"
          className="fixed bottom-5 right-5 z-30 rounded-full bg-brand px-5 py-3 text-sm font-semibold text-white shadow-lg hover:brightness-110">
          {loading ? "Loading…" : "✨ Ask Pulse"}
        </button>
      )}
    </>
  );
}
