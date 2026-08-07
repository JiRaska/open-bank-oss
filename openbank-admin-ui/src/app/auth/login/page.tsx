// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

"use client"
import { signIn } from "next-auth/react"
import { useSearchParams } from "next/navigation"
import { Suspense } from "react"

function LoginContent() {
  const params = useSearchParams()
  const error = params.get("error")
  const callbackUrl = params.get("callbackUrl") || "/dashboard"

  return (
    <div style={{
      minHeight: "100vh", display: "flex", flexDirection: "column", alignItems: "center", justifyContent: "center",
    background: "var(--bg, #f8fafc)", fontFamily: "var(--font-sans, 'Inter', system-ui, sans-serif)",
    }}>
      <a
        href="#main"
        style={{
          position: "absolute", left: "-9999px", top: "0", zIndex: 100,
          // #4f46e5, not --sidebar-accent (#6366f1) — same contrast fix as the sign-in button below.
          padding: "10px 16px", background: "#4f46e5", color: "#fff",
          borderRadius: "0 0 8px 0", fontSize: "13px", fontWeight: 600,
        }}
        onFocus={e => { e.currentTarget.style.left = "0" }}
        onBlur={e => { e.currentTarget.style.left = "-9999px" }}
      >
        Přeskočit na obsah
      </a>
      <main id="main" style={{
        width: "380px", background: "var(--surface, #ffffff)", border: "1px solid var(--border, #e2e8f0)",
        borderRadius: "16px", padding: "40px", boxShadow: "var(--shadow-lg, 0 10px 15px -3px rgba(0,0,0,0.1))",
      }}>
        {/* Logo */}
        <div style={{ display: "flex", alignItems: "center", gap: "12px", marginBottom: "32px" }}>
          <div style={{
            width: "40px", height: "40px", background: "var(--sidebar-accent, #6366f1)", borderRadius: "10px",
            display: "flex", alignItems: "center", justifyContent: "center",
          }}>
            <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="white" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
              <rect x="3" y="3" width="18" height="18" rx="2"/>
              <path d="M3 9h18M9 21V9"/>
            </svg>
          </div>
          <div>
            <div style={{ fontSize: "16px", fontWeight: 700, color: "var(--text-primary, #0f172a)" }}>OpenBank Admin</div>
            <div style={{ fontSize: "11px", color: "var(--text-tertiary, #94a3b8)" }}>Operations Portal</div>
          </div>
        </div>

        <h1 style={{ margin: "0 0 8px", fontSize: "20px", fontWeight: 700, color: "var(--text-primary, #0f172a)" }}>
          Přihlášení
        </h1>
        <div style={{ marginBottom: "28px", fontSize: "13px", color: "var(--text-secondary, #475569)" }}>
          Přihlaste se pomocí firemního účtu Keycloak
        </div>

        {error && (
          <div style={{
            marginBottom: "20px", padding: "12px 14px", borderRadius: "8px",
            background: "#fef2f2", border: "1px solid #fecaca",
            fontSize: "13px", color: "#dc2626",
          }}>
            {error === "SessionExpired" ? "Vaše relace vypršela. Přihlaste se znovu." : "Chyba přihlášení. Zkuste to znovu."}
          </div>
        )}

        <button
          onClick={() => signIn("keycloak", { callbackUrl })}
          style={{
            width: "100%", padding: "12px", borderRadius: "8px",
            // Darker than --sidebar-accent (#6366f1) on purpose: white-on-#6366f1
            // measures ~4.47:1, just under WCAG 2.1 AA's 4.5:1 for normal text.
            // #4f46e5 (~6.5:1) keeps the same hue with margin to spare.
            background: "#4f46e5", border: "none", color: "#ffffff",
            fontSize: "14px", fontWeight: 600, cursor: "pointer",
            display: "flex", alignItems: "center", justifyContent: "center", gap: "10px",
            transition: "opacity 0.15s, background-color 0.15s",
          }}
          onMouseEnter={e => (e.currentTarget.style.opacity = "0.9")}
          onMouseLeave={e => (e.currentTarget.style.opacity = "1")}
        >
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
            <path d="M15 3h4a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2h-4"/>
            <polyline points="10 17 15 12 10 7"/>
            <line x1="15" y1="12" x2="3" y2="12"/>
          </svg>
          Přihlásit se přes Keycloak SSO
        </button>

        <div style={{ marginTop: "24px", padding: "12px", background: "var(--surface-2, #f8fafc)", borderRadius: "8px", fontSize: "11px", color: "var(--text-tertiary, #94a3b8)" }}>
          <strong style={{ color: "var(--text-secondary, #475569)" }}>Zero-Trust Security</strong><br/>
          Přístup je řízen rolemi (RBAC). Všechny akce jsou auditovány dle EBA ICT Risk a PSD2 požadavků.
        </div>
      </main>
      <footer style={{ marginTop: "20px", fontSize: "11px" }}>
        {/* var(--text-tertiary) fails contrast at this size (~2.4:1) — use --text-secondary (~7.2:1). */}
        <a href="/privacy" style={{ color: "var(--text-secondary, #475569)" }}>Ochrana osobních údajů</a>
      </footer>
    </div>
  )
}

export default function LoginPage() {
  return (
    <Suspense>
      <LoginContent />
    </Suspense>
  )
}
