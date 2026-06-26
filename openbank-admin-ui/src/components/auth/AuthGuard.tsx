// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

"use client"
import { useEffect } from "react"
import { useSession, signIn } from "next-auth/react"
import { Permission, hasPermission } from "@/lib/auth/roles"

interface AuthGuardProps {
  children: React.ReactNode
  permission?: Permission
  fallback?: React.ReactNode
}

export function AuthGuard({ children, permission, fallback }: AuthGuardProps) {
  const { data: session, status } = useSession()

  useEffect(() => {
    if (status === "unauthenticated") {
      signIn("keycloak")
    }
  }, [status])

  if (status === "loading") {
    return (
      <div style={{ display: "flex", alignItems: "center", justifyContent: "center", height: "100vh", background: "var(--bg)" }}>
        <div style={{ textAlign: "center" }}>
          <div style={{ width: "32px", height: "32px", border: "3px solid var(--border)", borderTopColor: "var(--accent)", borderRadius: "50%", animation: "spin 0.8s linear infinite", margin: "0 auto 12px" }} />
          <div style={{ fontSize: "13px", color: "var(--text-tertiary)" }}>Ověřování identity…</div>
        </div>
      </div>
    )
  }

  if (status === "unauthenticated") return null

  if (permission && !hasPermission(session?.user?.roles ?? [], permission)) {
    return fallback ?? (
      <div style={{ display: "flex", alignItems: "center", justifyContent: "center", height: "60vh" }}>
        <div style={{ textAlign: "center", maxWidth: "400px" }}>
          <div style={{ fontSize: "48px", marginBottom: "16px" }}>🔒</div>
          <div style={{ fontSize: "18px", fontWeight: 700, color: "var(--text-primary)", marginBottom: "8px" }}>Přístup odepřen</div>
          <div style={{ fontSize: "13px", color: "var(--text-secondary)" }}>
            Nemáte oprávnění k zobrazení této stránky. Kontaktujte správce systému.
          </div>
          <div style={{ marginTop: "8px", fontSize: "11px", color: "var(--text-tertiary)", fontFamily: "JetBrains Mono, monospace" }}>
            Požadované oprávnění: {permission}
          </div>
        </div>
      </div>
    )
  }

  return <>{children}</>
}

export function Can({ permission, children, fallback }: { permission: Permission; children: React.ReactNode; fallback?: React.ReactNode }) {
  const { data: session } = useSession()
  const roles = session?.user?.roles ?? []
  if (!hasPermission(roles, permission)) return <>{fallback ?? null}</>
  return <>{children}</>
}
