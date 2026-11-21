import type { NextConfig } from "next";

/**
 * Next.js configuration. Explained in Learning_project → Phase 2 → "Next.js code walkthrough".
 */
const nextConfig: NextConfig = {
  // Build a minimal self-contained server in .next/standalone — small Docker images (Phase 10).
  output: "standalone",
  // Don't advertise "X-Powered-By: Next.js" (less information for attackers).
  poweredByHeader: false,
  images: {
    // External article images (Phase 7) may come from any HTTPS host; next/image resizes them.
    remotePatterns: [{ protocol: "https", hostname: "**" }],
    formats: ["image/avif", "image/webp"],
  },
  async headers() {
    return [
      {
        source: "/:path*",
        headers: [
          { key: "X-Content-Type-Options", value: "nosniff" },
          { key: "Referrer-Policy", value: "strict-origin-when-cross-origin" },
          { key: "Permissions-Policy", value: "camera=(), microphone=(), geolocation=()" },
        ],
      },
    ];
  },
};

export default nextConfig;
