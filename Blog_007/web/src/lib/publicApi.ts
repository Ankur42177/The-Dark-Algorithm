/**
 * Browser-side client for Pulse's PUBLIC write endpoints (newsletter + contact).
 *  - credentials: "omit" → no cookies are sent: these endpoints need no login, and leaving the admin
 *    session cookie out means a compromised form can never act as the admin.
 *  - Errors are normalised into one shape so every form can show a friendly message.
 */
const API = process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080";

export type PublicResult<T> =
  | { ok: true; status: number; data: T }
  | { ok: false; status: number; message: string };

export async function postPublic<T>(path: string, body: unknown, signal?: AbortSignal): Promise<PublicResult<T>> {
  try {
    const res = await fetch(`${API}${path}`, {
      method: "POST",
      headers: { "Content-Type": "application/json", Accept: "application/json" },
      credentials: "omit",
      body: JSON.stringify(body),
      signal: signal ?? AbortSignal.timeout(10_000),
    });
    const json = await res.json().catch(() => ({}));
    if (res.ok) return { ok: true, status: res.status, data: json as T };
    // RFC 9457 Problem Details: the human-readable reason lives in `detail`.
    const detail = typeof json?.detail === "string" ? json.detail : null;
    const retry = Number(res.headers.get("Retry-After"));
    const wait = retry > 0 ? (retry >= 90 ? `${Math.round(retry / 60)} minutes` : `${retry} seconds`) : "a couple of minutes";
    const message =
      res.status === 429 ? `Easy, tiger — too many tries. Give it ${wait} and go again.`
      : res.status === 404 ? detail ?? "That link has expired or was already used."
      : res.status === 400 ? detail ?? "Something in the form doesn't look right."
      : res.status >= 500 ? "Our servers fumbled the ball. Please try again shortly."
      : detail ?? `Unexpected response (HTTP ${res.status}).`;
    return { ok: false, status: res.status, message };
  } catch (e) {
    if ((e as Error).name === "AbortError" && signal?.aborted) return { ok: false, status: 0, message: "Cancelled." };
    return { ok: false, status: 0, message: "Couldn't reach Pulse right now. Check your connection and try again." };
  }
}

/** Deliberately simple: the server does the real validation; this only catches typos early. */
export const looksLikeEmail = (v: string) => /^[^\s@]+@[^\s@]+\.[^\s@]{2,}$/.test(v.trim());
