// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

"use client"
import { useSession, signIn, signOut } from "next-auth/react"
import { hasPermission, hasRole, hasAnyRole, Permission, Role } from "./roles"

export function useAuth() {
  const { data: session, status } = useSession()

  const roles: string[] = session?.user?.roles ?? []
  const isAuthenticated = status === "authenticated"
  const isLoading = status === "loading"
  const user = session?.user

  return {
    user,
    roles,
    isAuthenticated,
    isLoading,
    accessToken: session?.user?.accessToken,
    hasPermission: (p: Permission) => hasPermission(roles, p),
    hasRole: (r: Role) => hasRole(roles, r),
    hasAnyRole: (...r: Role[]) => hasAnyRole(roles, ...r),
    signIn: () => signIn("keycloak"),
    signOut: () => signOut({ callbackUrl: "/auth/login" }),
  }
}
