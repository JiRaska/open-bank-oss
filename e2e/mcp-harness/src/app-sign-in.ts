// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import type { Page } from 'playwright-core'
import { authorizeInBrowser } from './browser.ts'
import { assertScaTarget } from './guard.ts'
import { authorizeUrlFromParams, exchangeCode, validateAuthorizationResponse } from './oauth.ts'
import type { AsMetadata } from './oauth.ts'
import { pkcePair, randomState } from './pkce.ts'

const APP = 'openbank-app'
const CALLBACK = 'https://open-bank.tech/app/oauth/callback'

export function assertSandboxAppAuthority(as: AsMetadata, expectedIssuer: string): void {
  for (const url of [expectedIssuer, as.issuer, as.authorization_endpoint, as.token_endpoint]) assertScaTarget(url)
  const issuer = new URL(as.issuer)
  const loopback = issuer.hostname === 'localhost' || issuer.hostname === '[::1]' ||
    /^127\.\d{1,3}\.\d{1,3}\.\d{1,3}$/.test(issuer.hostname)
  const authorizationBase = `${issuer.origin}/realms/openbank-customers/protocol/openid-connect`
  if (as.issuer !== expectedIssuer || issuer.href !== `${issuer.origin}/realms/openbank-customers` ||
      (issuer.protocol !== 'https:' && !loopback) || as.authorization_endpoint !== `${authorizationBase}/auth` ||
      as.token_endpoint !== `${authorizationBase}/token`) {
    throw new Error('app sign-in requires the exact configured sandbox customers realm and its own OIDC endpoints')
  }
}

export async function signInSandboxApp(
  page: Page,
  as: AsMetadata,
  expectedIssuer: string,
  partyId: string,
  timeoutMs: number,
): Promise<string> {
  assertSandboxAppAuthority(as, expectedIssuer)
  const pkce = pkcePair()
  const state = randomState()
  const authorizationUrl = authorizeUrlFromParams(as, {
    clientId: APP, redirectUri: CALLBACK, scope: 'openid sca:decide', prompt: 'login', state,
    codeChallenge: pkce.challenge, codeChallengeMethod: 'S256',
  })
  const callbackUrl = await authorizeInBrowser(page, authorizationUrl, CALLBACK, { timeoutMs })
  return redeemSandboxAppAuthorization(as, expectedIssuer, partyId, callbackUrl, state, pkce.verifier)
}

export async function redeemSandboxAppAuthorization(
  as: AsMetadata,
  expectedIssuer: string,
  partyId: string,
  callbackUrl: string,
  state: string,
  verifier: string,
): Promise<string> {
  assertSandboxAppAuthority(as, expectedIssuer)
  const callback = new URL(callbackUrl)
  if (`${callback.origin}${callback.pathname}` !== CALLBACK || callback.username || callback.password || callback.hash) {
    throw new Error('unexpected app callback')
  }
  if (callback.searchParams.has('error')) throw new Error('sandbox app authorization was refused')
  const callbackIssuer = callback.searchParams.get('iss')
  if (callbackIssuer !== null && callbackIssuer !== expectedIssuer) {
    throw new Error('iss mismatch in sandbox app authorization')
  }
  const code = validateAuthorizationResponse(callbackUrl, {
    state, issuer: expectedIssuer, issRequired: Boolean(as.authorization_response_iss_parameter_supported),
  })
  const result = await exchangeCode(as, { code, verifier, redirectUri: CALLBACK, auth: { clientId: APP } })
  const accessToken: unknown = result.json?.access_token
  if (result.status !== 200 || result.json?.token_type !== 'Bearer' || typeof accessToken !== 'string') {
    throw new Error(`sandbox app code exchange did not produce an access token (HTTP ${result.status})`)
  }
  // This is a sanity check on the HTTPS token response. Customer Edge verifies the JWT itself.
  const parts = accessToken.split('.')
  let claims
  try {
    if (parts.length !== 3 || parts.some((part) => !/^[a-zA-Z0-9_-]+$/.test(part))) throw new Error()
    claims = JSON.parse(Buffer.from(parts[1], 'base64url').toString('utf8'))
  } catch {
    throw new Error('sandbox app token was not a JWT')
  }
  const audience = Array.isArray(claims?.aud) ? claims.aud : [claims?.aud]
  const now = Date.now() / 1000
  if (claims?.iss !== expectedIssuer || claims.azp !== APP || !audience.includes('openbank-edge') ||
      claims.party_id !== partyId || 'act' in claims || !Array.isArray(claims.realm_access?.roles) ||
      !claims.realm_access.roles.includes('ROLE_CUSTOMER') || typeof claims.scope !== 'string' ||
      !claims.scope.split(' ').includes('sca:decide') || !Number.isSafeInteger(claims.exp) ||
      claims.exp <= now || claims.exp > now + 600) {
    throw new Error('sandbox app token does not match the short-lived customer session')
  }
  return accessToken
}
