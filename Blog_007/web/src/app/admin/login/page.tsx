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
    <form onSubmit={submit} className="admin-card mx-auto mt-10 max-w-sm space-y-5 !p-8">
      <div>
        <p className="kicker">Back office</p>
        <h1 className="mt-2 font-display text-3xl font-medium tracking-tight text-ink">Welcome back</h1>
      </div>
      <label className="block text-sm font-semibold text-ink">Username
        <input value={username} onChange={(e) => setUsername(e.target.value)} autoComplete="username" className="field mt-1.5" />
      </label>
      <label className="block text-sm font-semibold text-ink">Password
        <input type="password" value={password} onChange={(e) => setPassword(e.target.value)} autoComplete="current-password" className="field mt-1.5" />
      </label>
      {error && <p className="text-sm text-hot" role="alert">{error}</p>}
      <button disabled={busy} className="btn-pill w-full">{busy ? "Signing in…" : <>Sign in <span className="arrow">→</span></>}</button>
    </form>
  );
}
