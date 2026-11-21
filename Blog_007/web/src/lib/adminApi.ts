/**
 * Browser-side client for the admin API.
 *  - credentials: "include"  → send/receive the HttpOnly auth cookies cross-origin (3000 → 8081/8080)
 *  - X-XSRF-TOKEN header     → echo the XSRF-TOKEN cookie (double-submit CSRF protection)
 *  - on 401: call /auth/refresh once (rotates the refresh token) and retry the request
 */
const API = process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8081";

export class ApiError extends Error {
  constructor(public status: number, message: string) {
    super(message);
  }
}

function xsrf(): string | undefined {
  // XSRF-TOKEN is deliberately NOT HttpOnly so this code can read it. The auth cookies ARE HttpOnly.
  return document.cookie.split("; ").find((c) => c.startsWith("XSRF-TOKEN="))?.split("=")[1];
}

async function raw(method: string, path: string, body?: unknown, extraHeaders: Record<string, string> = {}) {
  const headers: Record<string, string> = { ...extraHeaders };
  if (body !== undefined) headers["Content-Type"] = "application/json";
  const token = xsrf();
  if (token) headers["X-XSRF-TOKEN"] = decodeURIComponent(token);
  return fetch(`${API}${path}`, {
    method,
    headers,
    credentials: "include",
    body: body === undefined ? undefined : JSON.stringify(body),
  });
}

export async function api<T>(method: string, path: string, body?: unknown, headers?: Record<string, string>): Promise<T> {
  let res = await raw(method, path, body, headers);
  if (res.status === 401 && !path.startsWith("/api/v1/auth/")) {
    const refreshed = await raw("POST", "/api/v1/auth/refresh");
    if (refreshed.ok) res = await raw(method, path, body, headers);
  }
  if (!res.ok) {
    const problem = await res.json().catch(() => ({}));
    throw new ApiError(res.status, problem.detail ?? `HTTP ${res.status}`);
  }
  return (res.status === 204 ? undefined : await res.json()) as T;
}

export type Status = "DRAFT" | "SCHEDULED" | "PUBLISHED" | "UNPUBLISHED";
export interface AdminPostRow { id: string; slug: string; title: string; section: string; status: Status; publishedAt: string | null; updatedAt: string; version: number }
export interface AdminPost extends AdminPostRow { summary: string | null; bodyMd: string; bodyHtml: string; tags: string[]; coverImageUrl: string | null; readingMinutes: number }
export interface Me { username: string; displayName: string }
