// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

import { createContext, useContext } from 'react'

/**
 * The operator's realm roles as the SERVER read them from the session cookie while
 * rendering this page (RootLayout → AppProviders). The client useSession() round-trip
 * resolves a moment later; until then the permission-filtered shell (sidebar nav,
 * persona workspace links) would otherwise render with roles=[] and re-paint the whole
 * console when the session lands. The admin-operator-access journey measured a stable
 * 0.12+ workspace CLS from exactly that re-paint. Seeding the first client render with
 * the same roles the server used makes hydration match and the resolve a no-op.
 *
 * Deliberately NOT the next-auth SessionProvider `session` prop: passing it would
 * suppress the client's initial /api/auth/session fetch, which the mobile-shell e2e
 * tests hold and release to model the permissions-resolve window.
 */
const InitialRolesContext = createContext<string[] | null>(null)

export function InitialRolesProvider({ roles, children }: { roles: string[] | null; children: React.ReactNode }) {
  return <InitialRolesContext.Provider value={roles}>{children}</InitialRolesContext.Provider>
}

export function useInitialRoles(): string[] | null {
  return useContext(InitialRolesContext)
}
