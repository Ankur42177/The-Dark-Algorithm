import type { Metadata } from "next";
import { Suspense } from "react";
import { TokenAction } from "@/components/forms/TokenAction";

export const metadata: Metadata = { title: "Confirm subscription", robots: { index: false, follow: false } };

/** /newsletter/confirm?token=… — the link from the double-opt-in email. Suspense is required around useSearchParams. */
export default function ConfirmPage() {
  return (
    <Suspense>
      <TokenAction path="/api/v1/newsletter/confirm" copy={{
        ask: "One click to join the draw",
        button: "Yes, confirm my subscription",
        working: "Breaking the wax seal…",
        okTitle: "You're in the draw!",
        okBody: "Subscription confirmed. Once a month, one essay is pulled at random from the archive and lands in your inbox. Enjoy the surprise.",
        failTitle: "That seal won't break",
      }} />
    </Suspense>
  );
}
