// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

// Federated (single) logout (ADR-0080 P1, pentest F-AUTH-04). next-auth signOut only clears
// the local session cookie; the Keycloak SSO session stays alive, so navigating Back silently
// re-authenticates. This route builds the Keycloak end_session URL (with id_token_hint so KC
// skips the confirmation prompt). The client calls signOut() then redirects here, ending BOTH
// the portal session and the IdP session.

import { NextRequest, NextResponse } from 'next/server'
import { getToken } from 'next-auth/jwt'

const KEYCLOAK_PUBLIC_URL = process.env.KEYCLOAK_PUBLIC_URL || 'http://localhost:8080'
const KEYCLOAK_REALM = process.env.KEYCLOAK_REALM || 'openbank'
const END_SESSION = `${KEYCLOAK_PUBLIC_URL}/realms/${KEYCLOAK_REALM}/protocol/openid-connect/logout`

export const dynamic = 'force-dynamic'

export async function GET(req: NextRequest) {
  const secureCookie = req.nextUrl.protocol === 'https:'
  const token = await getToken({
    req,
    secret: process.env.NEXTAUTH_SECRET ?? process.env.AUTH_SECRET,
    secureCookie,
  })

  // The public origin, NOT req.nextUrl.origin (that is the in-container bind 0.0.0.0:3000,
  // which Keycloak would reject as an unregistered post_logout_redirect_uri).
  const publicOrigin = process.env.NEXTAUTH_URL ?? req.nextUrl.origin
  const postLogout = `${publicOrigin.replace(/\/$/, "")}/auth/login`
  const url = new URL(END_SESSION)
  url.searchParams.set('post_logout_redirect_uri', postLogout)
  if (token?.idToken) {
    url.searchParams.set('id_token_hint', token.idToken as string)
  } else {
    // Without an id_token_hint Keycloak requires the client_id to honour the redirect.
    url.searchParams.set('client_id', process.env.KEYCLOAK_CLIENT_ID || 'openbank-admin-ui')
  }

  return NextResponse.json({ url: url.toString() })
}
