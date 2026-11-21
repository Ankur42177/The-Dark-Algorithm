import type { Metadata } from "next";
import { Suspense } from "react";
import { TokenAction } from "@/components/forms/TokenAction";

export const metadata: Metadata = { title: "Unsubscribe", robots: { index: false, follow: false } };

/** /newsletter/unsubscribe?token=… — the one-click link at the bottom of every email. */
export default function UnsubscribePage() {
  return (
    <Suspense>
      <TokenAction path="/api/v1/newsletter/unsubscribe" copy={{
        working: "Taking you off the list…",
        okTitle: "Unsubscribed. No hard feelings.",
        okBody: "You won't get any more wildcards. If you ever miss the surprise, the door is always open.",
        failTitle: "We couldn't find that subscription",
      }} />
    </Suspense>
  );
}
