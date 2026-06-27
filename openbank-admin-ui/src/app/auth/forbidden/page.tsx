// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

"use client"
import { useSearchParams } from "next/navigation"
import { useRouter } from "next/navigation"
import { Suspense } from "react"

function ForbiddenContent() {
  const params = useSearchParams()
  const path = params.get("path") || ""
  const router = useRouter()

  return (
    <div style={{
      minHeight: "100vh", display: "flex", alignItems: "center", justifyContent: "center",
      background: "var(--bg)", fontFamily: "var(--font-sans)",
    }}>
      <div style={{ textAlign: "center", maxWidth: "480px", padding: "40px" }}>
        <div style={{ fontSize: "64px", marginBottom: "16px" }}>🔒</div>
        <div style={{ fontSize: "24px", fontWeight: 700, color: "var(--text-primary)", marginBottom: "8px" }}>
          Přístup odepřen
        </div>
        <div style={{ fontSize: "14px", color: "var(--text-secondary)", marginBottom: "24px", lineHeight: 1.6 }}>
          Nemáte dostatečná oprávnění pro přístup k této části systému.
          Pokud si myslíte, že jde o chybu, kontaktujte správce.
        </div>
        {path && (
          <div style={{ marginBottom: "24px", padding: "8px 12px", background: "var(--surface-2)", borderRadius: "6px", fontSize: "12px", fontFamily: "monospace", color: "var(--text-tertiary)" }}>
            {path}
          </div>
        )}
        <div style={{ display: "flex", gap: "12px", justifyContent: "center" }}>
          <button onClick={() => router.back()} style={{
            padding: "10px 20px", borderRadius: "8px", border: "1px solid var(--border)",
            background: "var(--surface)", color: "var(--text-primary)", fontSize: "13px",
            fontWeight: 500, cursor: "pointer",
          }}>
            Zpět
          </button>
          <button onClick={() => router.push("/dashboard")} style={{
            padding: "10px 20px", borderRadius: "8px", border: "none",
            background: "var(--accent)", color: "#fff", fontSize: "13px",
            fontWeight: 500, cursor: "pointer",
          }}>
            Na Dashboard
          </button>
        </div>
      </div>
    </div>
  )
}

export default function ForbiddenPage() {
  return <Suspense><ForbiddenContent /></Suspense>
}
