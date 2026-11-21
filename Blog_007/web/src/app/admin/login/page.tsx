"use client";
import { useState } from "react";
import { useRouter } from "next/navigation";
import { api, ApiError, type Me } from "@/lib/adminApi";

export default function LoginPage() {
  const router = useRouter();
  const [username, setUsername] = useState("ankur");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await api<Me>("POST", "/api/v1/auth/login", { username, password });
      router.push("/admin");
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Login failed");
    } finally {
      setBusy(false);
    }
  }

  return (
    <form onSubmit={submit} className="mx-auto mt-16 max-w-sm space-y-4 rounded-2xl border border-[var(--line)] bg-[var(--card)] p-6">
      <h1 className="text-2xl font-bold">Admin login</h1>
      <label className="block text-sm">Username
        <input value={username} onChange={(e) => setUsername(e.target.value)} autoComplete="username"
          className="mt-1 w-full rounded-lg border border-[var(--line)] bg-transparent px-3 py-2" />
      </label>
      <label className="block text-sm">Password
        <input type="password" value={password} onChange={(e) => setPassword(e.target.value)} autoComplete="current-password"
          className="mt-1 w-full rounded-lg border border-[var(--line)] bg-transparent px-3 py-2" />
      </label>
      {error && <p className="text-sm text-red-500" role="alert">{error}</p>}
      <button disabled={busy} className="w-full rounded-lg bg-brand py-2 font-semibold text-white disabled:opacity-50">
        {busy ? "Signing in…" : "Sign in"}
      </button>
    </form>
  );
}
