// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
//
// OAuth 2.1 client side of ADR-0383/0385: RFC 9728 discovery (via the official MCP SDK), RFC 9126
// PAR, PKCE S256, RFC 8707 resource, RFC 9207 iss check, DPoP-bound code exchange and refresh.
// The SDK's own `auth()` does not do PAR or DPoP, so only its discovery helpers are reused.
import {
  discoverAuthorizationServerMetadata,
  discoverOAuthProtectedResourceMetadata,
} from '@modelcontextprotocol/sdk/client/auth.js'
import type { DpopKey } from './dpop.ts'
import { assertTarget, guardedFetch } from './guard.ts'
import { createDpopProof } from './dpop.ts'
import type { RawResponse } from './http.ts'
import { send } from './http.ts'

export interface AsMetadata {
  issuer: string
  authorization_endpoint: string
  token_endpoint: string
  pushed_authorization_request_endpoint?: string
  require_pushed_authorization_requests?: boolean
  dpop_signing_alg_values_supported?: string[]
  authorization_response_iss_parameter_supported?: boolean
  code_challenge_methods_supported?: string[]
  [k: string]: unknown
}

export interface Discovery {
  resource: string
  authServer: string
  as: AsMetadata
}

export async function discover(resourceUrl: string, authServerOverride?: string): Promise<Discovery> {
  let resource = resourceUrl
  let authServer = authServerOverride
  if (!authServer) {
    // Throws when the resource serves no RFC 9728 document — today's mcp-service state.
    const prm = await discoverOAuthProtectedResourceMetadata(resourceUrl, undefined, guardedFetch())
    resource = prm.resource
    authServer = prm.authorization_servers?.[0]
    if (!authServer) throw new Error('protected-resource metadata lists no authorization_servers')
  }
  assertTarget(authServer) // a discovered AS is a target too — metadata must not redirect us
  const as = (await discoverAuthorizationServerMetadata(authServer, { fetchFn: guardedFetch() })) as AsMetadata | undefined
  if (!as) throw new Error(`no authorization-server metadata at ${authServer}`)
  for (const e of [as.authorization_endpoint, as.token_endpoint, as.pushed_authorization_request_endpoint]) if (e) assertTarget(e)
  return { resource, authServer, as }
}

export const supportsDpop = (as: AsMetadata): boolean => (as.dpop_signing_alg_values_supported ?? []).includes('ES256')
export const supportsPar = (as: AsMetadata): boolean => Boolean(as.pushed_authorization_request_endpoint)

export interface AuthorizationParams {
  clientId: string
  redirectUri: string
  scope: string
  state: string
  resource?: string
  codeChallenge?: string
  codeChallengeMethod?: string
  dpopJkt?: string
  prompt?: string
  extra?: Record<string, string>
}

const toForm = (p: AuthorizationParams): Record<string, string | undefined> => ({
  response_type: 'code',
  client_id: p.clientId,
  redirect_uri: p.redirectUri,
  scope: p.scope,
  state: p.state,
  resource: p.resource,
  code_challenge: p.codeChallenge,
  code_challenge_method: p.codeChallengeMethod,
  dpop_jkt: p.dpopJkt,
  prompt: p.prompt,
  ...p.extra,
})

export interface ClientAuth { clientId: string; clientSecret?: string }

const clientAuthForm = (a: ClientAuth): Record<string, string | undefined> =>
  ({ client_id: a.clientId, client_secret: a.clientSecret })

/** RFC 9126. Returns the raw response so attacks can assert the exact error. */
export function pushAuthorizationRequest(as: AsMetadata, p: AuthorizationParams, auth: ClientAuth, dpop?: DpopKey): Promise<RawResponse> {
  if (!as.pushed_authorization_request_endpoint) throw new Error('authorization server advertises no PAR endpoint')
  return postWithDpop(as.pushed_authorization_request_endpoint, { ...toForm(p), ...clientAuthForm(auth) }, dpop)
}

export function authorizeUrlFromPar(as: AsMetadata, clientId: string, requestUri: string, extra: Record<string, string> = {}): string {
  const u = new URL(as.authorization_endpoint)
  u.searchParams.set('client_id', clientId)
  u.searchParams.set('request_uri', requestUri)
  for (const [k, v] of Object.entries(extra)) u.searchParams.set(k, v)
  return u.toString()
}

export function authorizeUrlFromParams(as: AsMetadata, p: AuthorizationParams): string {
  const u = new URL(as.authorization_endpoint)
  for (const [k, v] of Object.entries(toForm(p))) if (v !== undefined) u.searchParams.set(k, v)
  return u.toString()
}

/**
 * RFC 9207 mix-up defence + state check on the redirect the browser landed on. Throws on any
 * mismatch: the client must not redeem a code that may belong to another authorization server.
 */
export function validateAuthorizationResponse(callbackUrl: string, expected: { state: string; issuer: string; issRequired: boolean }): string {
  const u = new URL(callbackUrl)
  const err = u.searchParams.get('error')
  if (err) throw new Error(`authorization error: ${err}`)
  if (u.searchParams.get('state') !== expected.state) throw new Error('state mismatch')
  const iss = u.searchParams.get('iss')
  if (iss === null && expected.issRequired) throw new Error('iss missing from authorization response (RFC 9207)')
  if (iss !== null && iss !== expected.issuer) throw new Error(`iss mismatch: got ${iss}, expected ${expected.issuer}`)
  const code = u.searchParams.get('code')
  if (!code) throw new Error('no code in authorization response')
  return code
}

async function postWithDpop(endpoint: string, form: Record<string, string | undefined>, dpop?: DpopKey, nonce?: string): Promise<RawResponse> {
  const headers: Record<string, string> = {}
  if (dpop) headers.DPoP = await createDpopProof(dpop, { htm: 'POST', htu: endpoint, nonce })
  const res = await send(endpoint, { method: 'POST', headers, form })
  // RFC 9449 §8: an AS may demand a nonce; retry once with it.
  const demanded = res.headers.get('dpop-nonce')
  if (dpop && !nonce && demanded && res.status === 400 && res.json?.error === 'use_dpop_nonce') {
    return postWithDpop(endpoint, form, dpop, demanded)
  }
  return res
}

export function exchangeCode(as: AsMetadata, o: { code: string; verifier?: string; redirectUri: string; resource?: string; auth: ClientAuth; dpop?: DpopKey }): Promise<RawResponse> {
  return postWithDpop(as.token_endpoint, {
    grant_type: 'authorization_code',
    code: o.code,
    code_verifier: o.verifier,
    redirect_uri: o.redirectUri,
    resource: o.resource,
    ...clientAuthForm(o.auth),
  }, o.dpop)
}

export function refreshGrant(as: AsMetadata, o: { refreshToken: string; resource?: string; auth: ClientAuth; dpop?: DpopKey }): Promise<RawResponse> {
  return postWithDpop(as.token_endpoint, {
    grant_type: 'refresh_token',
    refresh_token: o.refreshToken,
    resource: o.resource,
    ...clientAuthForm(o.auth),
  }, o.dpop)
}
