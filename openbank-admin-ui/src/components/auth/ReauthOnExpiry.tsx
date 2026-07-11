// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

"use client"
import { useEffect, useRef } from "react"
import { useSession, signIn } from "next-auth/react"

/**
 * Re-authenticate instead of showing dead panels when the token refresh fails.
 *
 * When the Keycloak refresh-token grant fails (SSO session gone / refresh token
 * expired), `authOptions`' jwt callback marks the session `error:
 * "RefreshAccessTokenError"` but keeps the now-stale access token. The BFF proxy
 * then keeps forwarding that dead bearer, so every service answers 401 and the
 * operator sees "Vypršela relace" panels across the console rather than being asked
 * to sign in again.
 *
 * This watches for that terminal session state and kicks off a fresh Keycloak
 * sign-in — silent if the SSO session is still alive, the login page otherwise — so
 * the console self-heals. Mounted once under SessionProvider; NextAuth's
 * refetch-on-window-focus is what surfaces the error when an operator returns to an
 * idle tab (we deliberately do NOT add a refetch interval — proactive polling would
 * keep refreshing the token and defeat the 1h idle-session cap, ADR-0080 F-AUTH-03).
 */
export function ReauthOnExpiry() {
  const { data: session } = useSession()
  // signIn() triggers a full-page redirect, but guard against a double-fire (React
  // strict mode / a re-render landing before navigation) so we never stack sign-ins.
  const triggered = useRef(false)

  useEffect(() => {
    if (session?.user?.error === "RefreshAccessTokenError" && !triggered.current) {
      triggered.current = true
      void signIn("keycloak")
    }
  }, [session?.user?.error])

  return null
}
