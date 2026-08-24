// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

"use client"
import { useSearchParams } from "next/navigation"
import { signIn } from "next-auth/react"
import { Suspense } from "react"

function ErrorContent() {
  const params = useSearchParams()
  const error = params.get("error")

  const messages: Record<string, string> = {
    Configuration: "Chyba konfigurace serveru. Kontaktujte správce.",
    AccessDenied: "Přístup byl odepřen poskytovatelem identity.",
    Verification: "Ověřovací odkaz je neplatný nebo vypršel.",
    Default: "Nastala neočekávaná chyba při přihlašování.",
  }

  return (
    <div style={{
      minHeight: "100vh", display: "flex", alignItems: "center", justifyContent: "center",
      background: "var(--bg)", fontFamily: "var(--font-sans)",
    }}>
      <div style={{ textAlign: "center", maxWidth: "420px", padding: "40px" }}>
        <div style={{ fontSize: "48px", marginBottom: "16px" }}>⚠️</div>
        <div style={{ fontSize: "20px", fontWeight: 700, color: "var(--text-primary)", marginBottom: "8px" }}>
          Chyba přihlášení
        </div>
        <div style={{ fontSize: "13px", color: "var(--text-secondary)", marginBottom: "24px" }}>
          {messages[error ?? "Default"] ?? messages.Default}
        </div>
        <button type="button" aria-label="Retry sign-in / Zkusit přihlášení znovu" onClick={() => signIn("keycloak")} style={{
          padding: "10px 24px", borderRadius: "8px", border: "none",
          background: "var(--accent)", color: "#fff", fontSize: "13px",
          fontWeight: 600, cursor: "pointer",
        }}>
          Zkusit znovu
        </button>
      </div>
    </div>
  )
}

export default function AuthErrorPage() {
  return <Suspense><ErrorContent /></Suspense>
}
