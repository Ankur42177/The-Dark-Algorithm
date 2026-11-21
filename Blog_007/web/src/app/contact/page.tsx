import type { Metadata } from "next";
import { ContactSection } from "@/components/ContactSection";

export const metadata: Metadata = {
  title: "Contact",
  description: "Questions, corrections or a hot take on the inverted full-back — send Ankur Kumar a message.",
};

/** Static page: the form inside is the only client island. */
export default function ContactPage() {
  return (
    <div className="pt-6">
      <ContactSection as="h1" />
    </div>
  );
}
