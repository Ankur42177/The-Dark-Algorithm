"use client";
import { useEffect } from "react";

const API = process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8081";

/**
 * Counts a view (Phase 3) WITHOUT slowing the page: navigator.sendBeacon queues a tiny POST
 * that the browser sends in the background, even if the reader navigates away. No body, so it's
 * a "simple" cross-origin request (no CORS preflight). Renders nothing.
 */
export function ViewBeacon({ slug }: { slug: string }) {
  useEffect(() => {
    const url = `${API}/api/v1/posts/${encodeURIComponent(slug)}/view`;
    if (navigator.sendBeacon) navigator.sendBeacon(url);
  }, [slug]);
  return null;
}
