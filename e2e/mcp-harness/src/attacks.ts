// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
//
// Issue #16617 layer 3: the authorization attack suite. One case per negative delivery check of
// ADR-0383 and ADR-0385 that a black-box client can observe. Every case states the PRECISE
// expected outcome and throws when the server answers anything else.
//
// Status discipline — a case must never pass vacuously:
//  - ACTIVE  runs by default against today's server. A missing precondition (e.g. no token
//            configured) is SKIP, which is not a pass and makes the run exit non-zero.
//  - PENDING names the slice/issue that builds the server-side mechanism. It is never executed
//            by default and is never reported as PASS; with --include-pending it runs and reports
//            PENDING-WOULD-PASS / PENDING-FAILS, so promotion to ACTIVE is a deliberate edit.
//            A PENDING case with no body yet says what it waits for (`needs`).
import { createSign, generateKeyPairSync, randomUUID } from 'node:crypto'
import { readFileSync } from 'node:fs'
import { b64url, decodeJwtPayload } from './b64.ts'
import { assertTarget } from './guard.ts'
import { authorizeInBrowser, openPasskeyBrowser } from './browser.ts'
import type { HarnessConfig } from './config.ts'
import type { DpopKey, ProofOptions } from './dpop.ts'
import { createDpopProof, generateDpopKey, htuOf, importDpopKey } from './dpop.ts'
import type { RawResponse } from './http.ts'
import { rpc, send } from './http.ts'
import type { Session } from './journey.ts'
import { approverFor } from './journey.ts'
import type { AuthorizationParams, Discovery } from './oauth.ts'
import {
  authorizeUrlFromPar, authorizeUrlFromParams, discover, pushAuthorizationRequest, refreshGrant,
  validateAuthorizationResponse,
} from './oauth.ts'
import { pkcePair, randomState } from './pkce.ts'

export type Status = 'ACTIVE' | 'PENDING'
export type Outcome = 'PASS' | 'FAIL' | 'SKIP' | 'PENDING' | 'PENDING-WOULD-PASS' | 'PENDING-FAILS'

export class Skip extends Error {}

export interface AttackContext {
  cfg: HarnessConfig
  discovery(): Promise<Discovery>
  session(): Promise<Session & { dpop?: DpopKey }>
}

export interface AttackCase {
  id: string
  source: string
  title: string
  expected: string
  status: Status
  /** PENDING only: the slice / issue whose server-side mechanism turns this case ACTIVE. */
  enabledBy?: string
  /** PENDING without a body: what the body itself is waiting for. */
  needs?: string
  run?: (ctx: AttackContext) => Promise<void>
}

/**
 * Every assertion that HOLDS appends its label here. The known-positive test uses it to prove a
 * run against the correct stub reached the case's final assertion (not an early return), so a
 * broken twin failing for an unrelated reason can never stand in for the real proof.
 */
export const assertionLog: string[] = []
const pass = (label: string): void => { assertionLog.push(label) }

const fail = (msg: string, r?: RawResponse): never => {
  throw new Error(r ? `${msg} (got HTTP ${r.status}${r.json?.error ? ` error=${r.json.error}` : ''})` : msg)
}

const expectStatus = (r: RawResponse, status: number, what: string): void => {
  if (r.status !== status) fail(`${what}: expected HTTP ${status}`, r)
  pass(what)
}

/** An OAuth error response: exact status (400 unless given) and exact `error` code. */
const expectOAuthError = (r: RawResponse, error: string, status = 400): void => {
  if (r.status !== status || r.json?.error !== error) fail(`expected HTTP ${status} error=${error}`, r)
  pass(`oauth-error ${error}`)
}

/** Authorization-endpoint refusal: either a 4xx page, or a redirect carrying `error` and no `code`. */
const expectAuthorizeRefused = (r: RawResponse, error?: string): void => {
  if (r.status >= 400 && r.status < 500) return pass('authorize refused')
  const loc = r.headers.get('location')
  if (r.status >= 300 && r.status < 400 && loc) {
    const u = new URL(loc, 'http://x')
    if (u.searchParams.get('code')) fail(`authorize issued a code: ${u.searchParams.get('code')?.slice(0, 6)}…`)
    if (u.searchParams.get('error') && (!error || u.searchParams.get('error') === error)) return pass('authorize refused')
  }
  fail(`expected the authorization request to be refused${error ? ` with error=${error}` : ''}`, r)
}

const NO_DATA = 'Authorization unavailable'

/** The unauthenticated caller gets nothing: 401, or a tool error whose only text is the deny. */
function expectNoDataToolResult(r: RawResponse): void {
  if (r.status === 401) return pass('no-data')
  if (r.status !== 200) fail('expected HTTP 401 or a JSON-RPC tool error', r)
  const res = r.json?.result
  if (res?.isError !== true) fail(`expected isError=true, got ${JSON.stringify(r.json).slice(0, 200)}`)
  if (res.structuredContent !== undefined) fail('a denied call returned structuredContent')
  const texts = (res.content ?? []).map((c: { text?: string }) => c.text)
  if (texts.length !== 1 || texts[0] !== NO_DATA) fail(`expected the single text "${NO_DATA}", got ${JSON.stringify(texts)}`)
  pass('no-data')
}

/** A syntactically valid RS256 JWT signed by a key no authorization server knows. */
export function forgedJwt(claims: Record<string, unknown>): string {
  const { privateKey } = generateKeyPairSync('rsa', { modulusLength: 2048 })
  const now = Math.floor(Date.now() / 1000)
  const input = `${b64url(JSON.stringify({ alg: 'RS256', typ: 'JWT', kid: 'mcp-harness-forged' }))}.${b64url(JSON.stringify({ iat: now, exp: now + 300, ...claims }))}`
  return `${input}.${b64url(createSign('RSA-SHA256').update(input).sign(privateKey))}`
}

const clientIdOf = (ctx: AttackContext): string => ctx.cfg.clientId ?? (() => { throw new Skip('MCP_HARNESS_CLIENT_ID not set') })()

function baseParams(ctx: AttackContext, d: Discovery, over: Partial<AuthorizationParams> = {}): AuthorizationParams {
  return {
    clientId: clientIdOf(ctx), redirectUri: ctx.cfg.redirectUri, scope: ctx.cfg.scope, state: randomState(),
    resource: d.resource, codeChallenge: pkcePair().challenge, codeChallengeMethod: 'S256', ...over,
  }
}

const auth = (ctx: AttackContext) => ({ clientId: clientIdOf(ctx), clientSecret: ctx.cfg.clientSecret })

async function par(ctx: AttackContext, over: Partial<AuthorizationParams> = {}): Promise<{ d: Discovery; r: RawResponse; p: AuthorizationParams }> {
  const d = await ctx.discovery()
  const p = baseParams(ctx, d, over)
  return { d, r: await pushAuthorizationRequest(d.as, p, auth(ctx)), p }
}

async function freshRequestUri(ctx: AttackContext): Promise<{ d: Discovery; requestUri: string; p: AuthorizationParams }> {
  const { d, r, p } = await par(ctx)
  expectStatus(r, 201, 'precondition: a well-formed PAR')
  return { d, requestUri: r.json.request_uri, p }
}

/** One MCP call with a DPoP proof built from `proof` overrides — the DPoP attack primitive. */
async function dpopCall(ctx: AttackContext, o: { scheme?: string; proof?: string | null; proofOverrides?: Partial<ProofOptions>; key?: DpopKey }): Promise<{ r: RawResponse; proof?: string }> {
  const s = await ctx.session()
  if (s.scheme !== 'DPoP' || !s.dpop) throw new Skip('session is not DPoP-bound')
  const url = ctx.cfg.resourceUrl
  let proof = o.proof === null ? undefined : o.proof
  if (proof === undefined && o.proof !== null) {
    proof = await createDpopProof(o.key ?? s.dpop, { htm: 'POST', htu: htuOf(url), accessToken: s.accessToken, ...o.proofOverrides })
  }
  const headers: Record<string, string> = { authorization: `${o.scheme ?? 'DPoP'} ${s.accessToken}` }
  if (proof) headers.DPoP = proof
  return { r: await rpc(url, 'tools/list', {}, headers), proof }
}

/** With a live SSO cookie and NO usable passkey, an authorize with `extra` must not yield a code. */
async function ssoCookieCase(ctx: AttackContext, extra: Record<string, string>, expectError?: string): Promise<void> {
  const d = await ctx.discovery()
  const b = await openPasskeyBrowser({ headless: ctx.cfg.headless, passkeyFile: ctx.cfg.passkeyFile })
  try {
    // 1. A full, legitimate connect establishes the SSO cookie.
    const approver = await approverFor(ctx.cfg)
    const first = await freshRequestUri(ctx)
    await authorizeInBrowser(b.page, authorizeUrlFromPar(d.as, clientIdOf(ctx), first.requestUri), ctx.cfg.redirectUri, {
      timeoutMs: ctx.cfg.timeoutMs,
      whileWaiting: async () => { await approver.approve((c) => c.purpose === 'MCP_CLIENT_CONNECT', { timeoutMs: ctx.cfg.timeoutMs }) },
    })
    // 2. Remove every passkey: from here on a code can only come from the cookie.
    const cdp = await b.context.newCDPSession(b.page)
    await cdp.send('WebAuthn.clearCredentials', { authenticatorId: b.authenticatorId })
    const { r } = await par(ctx, { extra })
    expectStatus(r, 201, 'precondition: PAR for the cookie probe')
    const probe = authorizeUrlFromPar(d.as, clientIdOf(ctx), r.json.request_uri)
    let landed: string | undefined
    try {
      landed = await authorizeInBrowser(b.page, probe, ctx.cfg.redirectUri, { timeoutMs: 15_000 })
    } catch {
      landed = undefined // no redirect at all: the login page is waiting for a passkey. Correct.
    }
    if (landed) {
      const u = new URL(landed)
      if (u.searchParams.get('code')) fail(`SSO cookie yielded a code for ${JSON.stringify(extra)}`)
      if (expectError && u.searchParams.get('error') !== expectError) fail(`expected error=${expectError}, got ${u.searchParams.get('error')}`)
    } else if (expectError) {
      fail(`expected an immediate error=${expectError} redirect, the browser stayed on ${b.page.url()}`)
    }
  } finally {
    await b.close()
  }
}

const D1 = '#16590 D1 — customers-realm tenant on mcp-service + RFC 9728 metadata'
const D2 = '#16590 D2 — DPoP verifier + shared jti store (ADR-0383 P1/P4, ADR-0320 P2 #10928)'
const P3 = '#16590 P3 — customers-realm MCP clients on Keycloak 26.8.1 (ADR-0385 D7)'
const FLOW = '#16590 P3 — per-client fresh-passkey flow override (ADR-0385 D2.2/D7.5)'
const SCA = '#16590 P3 — MCP_CLIENT_CONNECT purpose + withheld-code authenticator (ADR-0385 D3/D7.6/D7.8)'

export const ATTACKS: AttackCase[] = [
  // ---------------------------------------------------------------- ACTIVE today
  {
    id: 'unauth-tools-list-empty', source: 'ADR-0385 Admission; ADR-0225',
    title: 'Unauthenticated tools/list lists no tools',
    expected: 'HTTP 401, or HTTP 200 with result.tools == [] (today\'s governed-agent behaviour)',
    status: 'ACTIVE',
    run: async ({ cfg }) => {
      const r = await rpc(cfg.resourceUrl, 'tools/list', {})
      if (r.status === 401) return pass('tools/list empty')
      expectStatus(r, 200, 'tools/list')
      const tools = r.json?.result?.tools
      if (!Array.isArray(tools)) fail(`no result.tools array: ${r.body.slice(0, 200)}`)
      if (tools.length !== 0) fail(`anonymous caller was shown ${tools.length} tools: ${tools.map((t: { name: string }) => t.name).join(',')}`)
      pass('tools/list empty')
    },
  },
  {
    id: 'unauth-read-tool-no-data', source: 'ADR-0385 Admission; ADR-0195',
    title: 'Unauthenticated tools/call of a read tool returns no data',
    expected: `HTTP 401, or a tool error whose only content is "${NO_DATA}"`,
    status: 'ACTIVE',
    run: async ({ cfg }) => expectNoDataToolResult(await rpc(cfg.resourceUrl, 'tools/call', { name: cfg.readTool, arguments: cfg.readToolArgs })),
  },
  {
    id: 'unauth-propose-payment-refused', source: 'ADR-0383 D6; ADR-0385 Admission',
    title: 'Unauthenticated propose_payment is refused before any proposal logic',
    expected: `HTTP 401, or a tool error whose only content is "${NO_DATA}"`,
    status: 'ACTIVE',
    run: async ({ cfg }) => expectNoDataToolResult(await rpc(cfg.resourceUrl, 'tools/call', { name: 'propose_payment', arguments: cfg.proposeArgs })),
  },
  {
    id: 'forged-token-401', source: 'ADR-0383 D1; ADR-0195',
    title: 'A token signed by an unknown key is rejected, not treated as anonymous',
    expected: 'HTTP 401 on tools/list (no JSON-RPC result at all)',
    status: 'ACTIVE',
    run: async ({ cfg }) => {
      const t = forgedJwt({ sub: 'agent:mcp-harness', consent_id: randomUUID(), aud: 'openbank-mcp-service', azp: 'openbank-mcp-harness', iss: 'https://forged.invalid/realms/openbank' })
      expectStatus(await rpc(cfg.resourceUrl, 'tools/list', {}, { authorization: `Bearer ${t}` }), 401, 'forged token')
    },
  },
  {
    id: 'openbank-app-token-401', source: 'ADR-0385 Admission',
    title: 'A token of the bank\'s own app client (openbank-app) is rejected at MCP',
    expected: 'HTTP 401 on tools/list and on tools/call',
    status: 'ACTIVE',
    run: async ({ cfg }) => {
      if (!cfg.foreignToken) throw new Skip('MCP_HARNESS_FOREIGN_TOKEN (an openbank-app access token) not set')
      const azp = decodeJwtPayload(cfg.foreignToken).azp
      if (azp !== 'openbank-app') throw new Skip(`MCP_HARNESS_FOREIGN_TOKEN has azp=${String(azp)}, not openbank-app — the case would test something else`)
      const h = { authorization: `Bearer ${cfg.foreignToken}` }
      expectStatus(await rpc(cfg.resourceUrl, 'tools/list', {}, h), 401, 'openbank-app token, tools/list')
      expectStatus(await rpc(cfg.resourceUrl, 'tools/call', { name: cfg.readTool, arguments: cfg.readToolArgs }, h), 401, 'openbank-app token, tools/call')
    },
  },

  // ---------------------------------------------------------------- PENDING: admission / RS
  {
    id: 'unauth-401-resource-metadata', source: 'ADR-0385 Admission; RFC 9728',
    title: 'Unauthenticated call gets 401 + WWW-Authenticate pointing at protected-resource metadata',
    expected: 'HTTP 401; WWW-Authenticate: Bearer/DPoP with resource_metadata=<url>; that document has resource == the MCP URL',
    status: 'PENDING', enabledBy: D1,
    run: async ({ cfg }) => {
      const r = await rpc(cfg.resourceUrl, 'tools/list', {})
      expectStatus(r, 401, 'unauthenticated tools/list')
      const m = /resource_metadata="([^"]+)"/.exec(r.headers.get('www-authenticate') ?? '')
      if (!m) fail(`no resource_metadata in WWW-Authenticate: ${r.headers.get('www-authenticate')}`)
      assertTarget(m![1]) // the server names this URL; it is a target like any other
      const prm = await send(m![1], {})
      expectStatus(prm, 200, 'protected-resource metadata')
      if (prm.json?.resource !== cfg.resourceUrl) fail(`metadata resource ${prm.json?.resource} != ${cfg.resourceUrl}`)
      pass('resource metadata')
    },
  },
  {
    id: 'other-audience-token-401', source: 'ADR-0385 Admission; ADR-0383 D1',
    title: 'A customers-realm token whose aud is not openbank-mcp-service is rejected',
    expected: 'HTTP 401',
    status: 'PENDING', enabledBy: D1,
    run: async ({ cfg }) => {
      const t = process.env.MCP_HARNESS_OTHER_AUD_TOKEN
      if (!t) throw new Skip('MCP_HARNESS_OTHER_AUD_TOKEN not set')
      const aud = decodeJwtPayload(t).aud
      if ((Array.isArray(aud) ? aud : [aud]).includes('openbank-mcp-service')) throw new Skip('token carries aud openbank-mcp-service')
      expectStatus(await rpc(cfg.resourceUrl, 'tools/list', {}, { authorization: `Bearer ${t}` }), 401, 'foreign-aud token')
    },
  },
  {
    id: 'tier-l-without-cnf-jkt-401', source: 'ADR-0385 D1 Tier L; Admission',
    title: 'A Tier L client token with no cnf.jkt is rejected',
    expected: 'HTTP 401',
    status: 'PENDING', enabledBy: `${D1}; ${D2}`,
    run: async ({ cfg }) => {
      const t = process.env.MCP_HARNESS_UNBOUND_TOKEN
      if (!t) throw new Skip('MCP_HARNESS_UNBOUND_TOKEN (Tier L azp, no cnf) not set')
      if ((decodeJwtPayload(t).cnf as { jkt?: string } | undefined)?.jkt) throw new Skip('token is DPoP-bound')
      expectStatus(await rpc(cfg.resourceUrl, 'tools/list', {}, { authorization: `Bearer ${t}` }), 401, 'unbound Tier L token')
    },
  },
  {
    id: 'bearer-on-jkt-grant-401', source: 'ADR-0385 Admission; ADR-0383 D2',
    title: 'A DPoP-bound token presented as Bearer (no proof) is rejected',
    expected: 'HTTP 401',
    status: 'PENDING', enabledBy: D2,
    run: async (ctx) => expectStatus((await dpopCall(ctx, { scheme: 'Bearer', proof: null })).r, 401, 'bound token as Bearer'),
  },
  {
    id: 'dpop-proof-missing-401', source: 'ADR-0383 Delivery check: DPoP binding',
    title: 'DPoP scheme with no DPoP proof header',
    expected: 'HTTP 401',
    status: 'PENDING', enabledBy: D2,
    run: async (ctx) => expectStatus((await dpopCall(ctx, { proof: null })).r, 401, 'missing proof'),
  },
  {
    id: 'dpop-proof-other-key-401', source: 'ADR-0383 Delivery check: DPoP binding',
    title: 'A valid proof made with a key other than cnf.jkt',
    expected: 'HTTP 401',
    status: 'PENDING', enabledBy: D2,
    run: async (ctx) => expectStatus((await dpopCall(ctx, { key: await generateDpopKey() })).r, 401, 'proof for another key'),
  },
  {
    id: 'dpop-bad-signature-401', source: 'ADR-0383 D2',
    title: 'A proof advertising the bound key but signed by another key (signature does not verify)',
    expected: 'HTTP 401',
    status: 'PENDING', enabledBy: D2,
    run: async (ctx) => expectStatus((await dpopCall(ctx, { proofOverrides: { signWith: await generateDpopKey() } })).r, 401, 'bad signature'),
  },
  {
    id: 'dpop-jti-replay-401', source: 'ADR-0383 Delivery check: DPoP binding',
    title: 'The same proof (same jti) presented twice',
    expected: 'first call HTTP 200, the replay HTTP 401',
    status: 'PENDING', enabledBy: D2,
    run: async (ctx) => {
      const first = await dpopCall(ctx, {})
      expectStatus(first.r, 200, 'precondition: the fresh proof is accepted')
      expectStatus((await dpopCall(ctx, { proof: first.proof })).r, 401, 'replayed proof')
    },
  },
  {
    id: 'dpop-htu-mismatch-401', source: 'ADR-0385 Delivery check; ADR-0383 D2',
    title: 'A proof whose htu names another URL',
    expected: 'HTTP 401',
    status: 'PENDING', enabledBy: D2,
    run: async (ctx) => expectStatus((await dpopCall(ctx, { proofOverrides: { htu: 'https://attacker.invalid/mcp' } })).r, 401, 'htu mismatch'),
  },
  {
    id: 'dpop-htm-mismatch-401', source: 'ADR-0383 D2',
    title: 'A proof whose htm is GET for a POST',
    expected: 'HTTP 401',
    status: 'PENDING', enabledBy: D2,
    run: async (ctx) => expectStatus((await dpopCall(ctx, { proofOverrides: { htm: 'GET' } })).r, 401, 'htm mismatch'),
  },
  {
    id: 'dpop-stale-iat-401', source: 'ADR-0383 D2',
    title: 'A proof whose iat is older than 300 s',
    expected: 'HTTP 401',
    status: 'PENDING', enabledBy: D2,
    run: async (ctx) => expectStatus((await dpopCall(ctx, { proofOverrides: { iat: Math.floor(Date.now() / 1000) - 301 } })).r, 401, 'stale proof'),
  },
  {
    id: 'dpop-ath-mismatch-401', source: 'ADR-0383 D2',
    title: 'A proof whose ath hashes a different access token',
    expected: 'HTTP 401',
    status: 'PENDING', enabledBy: D2,
    run: async (ctx) => expectStatus((await dpopCall(ctx, { proofOverrides: { accessToken: 'not-the-presented-token' } })).r, 401, 'ath mismatch'),
  },

  // ---------------------------------------------------------------- PENDING: PAR / authorize
  {
    id: 'pkce-absent-rejected', source: 'ADR-0385 D2.1; PAR and authorize',
    title: 'PAR without code_challenge',
    expected: 'HTTP 400 error=invalid_request',
    status: 'PENDING', enabledBy: P3,
    run: async (ctx) => expectOAuthError((await par(ctx, { codeChallenge: undefined, codeChallengeMethod: undefined })).r, 'invalid_request'),
  },
  {
    id: 'pkce-plain-rejected', source: 'ADR-0385 D2.1; PAR and authorize',
    title: 'PAR with code_challenge_method=plain',
    expected: 'HTTP 400 error=invalid_request',
    status: 'PENDING', enabledBy: P3,
    run: async (ctx) => expectOAuthError((await par(ctx, { codeChallenge: pkcePair().verifier, codeChallengeMethod: 'plain' })).r, 'invalid_request'),
  },
  {
    id: 'par-required', source: 'ADR-0385 D2.1',
    title: 'A front-channel authorize without request_uri',
    expected: 'refused (4xx, or redirect with error and no code)',
    status: 'PENDING', enabledBy: P3,
    run: async (ctx) => {
      const d = await ctx.discovery()
      expectAuthorizeRefused(await send(authorizeUrlFromParams(d.as, baseParams(ctx, d)), {}))
    },
  },
  {
    id: 'resource-confusion-invalid-target', source: 'ADR-0385 Admission; RFC 8707',
    title: 'PAR naming a resource other than the MCP resource',
    expected: 'HTTP 400 error=invalid_target',
    status: 'PENDING', enabledBy: P3,
    run: async (ctx) => expectOAuthError((await par(ctx, { resource: 'https://attacker.invalid/mcp' })).r, 'invalid_target'),
  },
  {
    id: 'request-uri-reuse-rejected', source: 'ADR-0385 D2.1; PAR and authorize',
    title: 'A request_uri used twice',
    expected: 'first authorize serves the login page (HTTP 200), the second is refused',
    status: 'PENDING', enabledBy: P3,
    run: async (ctx) => {
      const { d, requestUri } = await freshRequestUri(ctx)
      const url = authorizeUrlFromPar(d.as, clientIdOf(ctx), requestUri)
      expectStatus(await send(url, {}), 200, 'precondition: first use reaches the login page')
      expectAuthorizeRefused(await send(url, {}))
    },
  },
  {
    id: 'request-uri-other-client-rejected', source: 'ADR-0385 D2.1; PAR and authorize',
    title: 'A request_uri presented with another client_id',
    expected: 'refused (4xx, or redirect with error and no code)',
    status: 'PENDING', enabledBy: P3,
    run: async (ctx) => {
      if (!ctx.cfg.otherClientId) throw new Skip('MCP_HARNESS_OTHER_CLIENT_ID not set')
      const { d, requestUri } = await freshRequestUri(ctx)
      expectAuthorizeRefused(await send(authorizeUrlFromPar(d.as, ctx.cfg.otherClientId, requestUri), {}))
    },
  },
  {
    id: 'request-uri-differing-params-rejected', source: 'ADR-0385 D2.1; PAR and authorize',
    title: 'A request_uri accompanied by a differing redirect_uri and scope',
    expected: 'refused (4xx, or redirect with error and no code)',
    status: 'PENDING', enabledBy: P3,
    run: async (ctx) => {
      const { d, requestUri } = await freshRequestUri(ctx)
      expectAuthorizeRefused(await send(authorizeUrlFromPar(d.as, clientIdOf(ctx), requestUri, {
        redirect_uri: 'http://127.0.0.1:1/attacker', scope: 'openid offline_access account.initiate-payment',
      }), {}))
    },
  },
  {
    id: 'request-uri-expired-rejected', source: 'ADR-0385 D2.1; PAR and authorize',
    title: 'A request_uri used after 60 s',
    expected: 'refused (4xx, or redirect with error and no code); expires_in ≤ 60 at PAR',
    status: 'PENDING', enabledBy: P3,
    run: async (ctx) => {
      const { d, r } = await par(ctx)
      expectStatus(r, 201, 'precondition: PAR')
      if (!(r.json.expires_in <= 60)) fail(`request_uri expires_in ${r.json.expires_in} > 60`)
      await new Promise((ok) => setTimeout(ok, 61_000))
      expectAuthorizeRefused(await send(authorizeUrlFromPar(d.as, clientIdOf(ctx), r.json.request_uri), {}))
    },
  },
  {
    id: 'iss-in-authorization-response', source: 'ADR-0385 D1 Discovery; RFC 9207 (mix-up)',
    title: 'The AS advertises and sends iss; the client refuses a mismatched iss',
    expected: 'metadata authorization_response_iss_parameter_supported=true; a callback with iss≠issuer is rejected client-side',
    status: 'PENDING', enabledBy: P3,
    run: async (ctx) => {
      const d = await ctx.discovery()
      if (d.as.authorization_response_iss_parameter_supported !== true) fail('authorization_response_iss_parameter_supported is not true')
      let refused = false
      try {
        validateAuthorizationResponse(`${ctx.cfg.redirectUri}?code=x&state=s&iss=${encodeURIComponent('https://attacker.invalid')}`, { state: 's', issuer: d.as.issuer, issRequired: true })
      } catch { refused = true }
      if (!refused) fail('client accepted a foreign iss')
      pass('iss enforced')
    },
  },
  {
    id: 'code-injection-rejected', source: 'ADR-0385 PAR and authorize; OAuth 2.1 §7.5',
    title: 'A real code redeemed with a different PKCE verifier (injected code)',
    expected: 'HTTP 400 error=invalid_grant',
    status: 'PENDING', enabledBy: `${P3}; ${SCA}`,
    needs: 'a live authorization code from the journey; body lands with the withheld-code authenticator so the code is obtainable',
  },
  {
    id: 'refresh-reuse-revokes-family', source: 'ADR-0385 Admission; ADR-0383 D2',
    title: 'Reusing a rotated refresh token revokes the whole family',
    expected: 'rotation HTTP 200; reuse of the old token HTTP 400 invalid_grant; the NEW token then also HTTP 400 invalid_grant',
    status: 'PENDING', enabledBy: P3,
    run: async (ctx) => {
      const s = await ctx.session()
      if (!s.refreshToken) throw new Skip('session has no refresh token')
      const d = await ctx.discovery()
      const a = { clientId: s.clientId, clientSecret: ctx.cfg.clientSecret }
      const rotated = await refreshGrant(d.as, { refreshToken: s.refreshToken, resource: s.resource, auth: a, dpop: s.dpop })
      expectStatus(rotated, 200, 'precondition: first rotation')
      expectOAuthError(await refreshGrant(d.as, { refreshToken: s.refreshToken, resource: s.resource, auth: a, dpop: s.dpop }), 'invalid_grant')
      expectOAuthError(await refreshGrant(d.as, { refreshToken: rotated.json.refresh_token, resource: s.resource, auth: a, dpop: s.dpop }), 'invalid_grant')
    },
  },
  {
    id: 'tier-h-refresh-without-client-auth', source: 'ADR-0385 Admission (Tier H)',
    title: 'Tier H refresh without client authentication',
    expected: 'HTTP 401 error=invalid_client',
    status: 'PENDING', enabledBy: P3,
    run: async (ctx) => {
      if (ctx.cfg.tier !== 'H') throw new Skip('MCP_HARNESS_TIER is not H')
      const s = await ctx.session()
      if (!s.refreshToken) throw new Skip('session has no refresh token')
      const d = await ctx.discovery()
      expectOAuthError(await refreshGrant(d.as, { refreshToken: s.refreshToken, resource: s.resource, auth: { clientId: s.clientId } }), 'invalid_client', 401)
    },
  },

  // ---------------------------------------------------------------- PENDING: live SSO cookie
  {
    id: 'sso-prompt-none-login-required', source: 'ADR-0385 D2.2; PAR and authorize',
    title: 'prompt=none with a live SSO cookie',
    expected: 'redirect with error=login_required and no code',
    status: 'PENDING', enabledBy: FLOW,
    run: (ctx) => ssoCookieCase(ctx, { prompt: 'none' }, 'login_required'),
  },
  {
    id: 'sso-prompt-create-refused', source: 'ADR-0385 D2.2/D2.3',
    title: 'prompt=create (registration) with a live SSO cookie',
    expected: 'no code (refused or waiting for a passkey)',
    status: 'PENDING', enabledBy: FLOW,
    run: (ctx) => ssoCookieCase(ctx, { prompt: 'create' }),
  },
  {
    id: 'sso-kc-action-no-effect', source: 'ADR-0385 D2.2',
    title: 'kc_action with a live SSO cookie',
    expected: 'no code without a fresh passkey assertion',
    status: 'PENDING', enabledBy: FLOW,
    run: (ctx) => ssoCookieCase(ctx, { kc_action: 'UPDATE_PROFILE' }),
  },
  {
    id: 'sso-login-hint-no-skip', source: 'ADR-0385 D2.2',
    title: 'login_hint / kc_idp_hint with a live SSO cookie',
    expected: 'no code without a fresh passkey assertion',
    status: 'PENDING', enabledBy: FLOW,
    run: (ctx) => ssoCookieCase(ctx, { login_hint: 'victim@example.invalid', kc_idp_hint: 'x' }),
  },
  {
    id: 'sso-max-age-omitted-still-fresh', source: 'ADR-0385 D2.2',
    title: 'No max_age with a live SSO cookie',
    expected: 'no code without a fresh passkey assertion',
    status: 'PENDING', enabledBy: FLOW,
    run: (ctx) => ssoCookieCase(ctx, {}),
  },

  // ---------------------------------------------------------------- PENDING: SCA linking
  {
    id: 'sca-initiate-requires-linking', source: 'ADR-0385 SCA linking',
    title: 'MCP_CLIENT_CONNECT initiate without approvalRequestId/payloadSha256 — and with them',
    expected: 'without linking HTTP 400; with linking HTTP 201 (the twin proves the purpose exists, so the 400 is not a parse error)',
    status: 'PENDING', enabledBy: SCA,
    run: async ({ cfg }) => {
      const tok = process.env.MCP_HARNESS_SCA_INITIATOR_TOKEN
      if (!cfg.scaUrl || !cfg.partyId || !tok) throw new Skip('MCP_HARNESS_SCA_URL / _PARTY_ID / _SCA_INITIATOR_TOKEN not set')
      const post = (dl: unknown) => send(`${cfg.scaUrl!.replace(/\/$/, '')}/api/v1/sca/challenges`, {
        method: 'POST',
        headers: { authorization: `Bearer ${tok}`, 'content-type': 'application/json', 'Idempotency-Key': randomUUID() },
        body: JSON.stringify({ partyId: cfg.partyId, purpose: 'MCP_CLIENT_CONNECT', preferredMethod: null, dynamicLinkingData: dl, redirectUrl: null }),
      })
      expectStatus(await post(null), 400, 'initiate without linking')
      expectStatus(await post({ approvalRequestId: randomUUID(), payloadSha256: 'a'.repeat(64) }), 201, 'initiate with linking')
    },
  },
  {
    id: 'approval-a-cannot-release-b', source: 'ADR-0385 SCA linking',
    title: 'Approving the challenge of authorization request A does not release a code for B',
    expected: 'A gets a code; B gets none',
    status: 'PENDING', enabledBy: SCA, needs: 'two concurrent browser sessions + the withheld-code authenticator',
  },
  {
    id: 'concurrent-flows-two-challenges', source: 'ADR-0385 SCA linking',
    title: 'Two concurrent connect flows for one party',
    expected: 'two distinct pending MCP_CLIENT_CONNECT challenges',
    status: 'PENDING', enabledBy: SCA, needs: 'the withheld-code authenticator initiating the challenge',
  },
  {
    id: 'concurrent-polls-one-code', source: 'ADR-0385 SCA linking',
    title: 'Two concurrent polls on one auth session',
    expected: 'exactly one code',
    status: 'PENDING', enabledBy: SCA, needs: 'the authenticator\'s poll endpoint (its shape is decided by the slice)',
  },
  {
    id: 'app-denial-no-code', source: 'ADR-0385 SCA linking',
    title: 'The customer denies in the app',
    expected: 'redirect with error=access_denied and no code',
    status: 'PENDING', enabledBy: SCA, needs: 'SoftwareScaDevice.decide(DENIED) wired into a journey variant once the authenticator exists',
  },
  {
    id: 'consent-rejects-mcp-client-connect', source: 'ADR-0385 SCA linking',
    title: 'consent-service refuses an MCP_CLIENT_CONNECT challenge as PSD2 consent evidence',
    expected: 'consent authorisation refused (4xx), consent stays unauthorised',
    status: 'PENDING', enabledBy: `${SCA}; consent-service refusal (#16590)`, needs: 'an approved MCP_CLIENT_CONNECT challenge (needs the purpose)',
  },
  // ---------------------------------------------------------------- PENDING: no body yet
  {
    id: 'cross-customer-isolation-at-mcp', source: 'ADR-0383 Delivery check: Cross-customer isolation',
    title: 'Customer A\'s grant cannot read customer B\'s account through MCP',
    expected: 'tool error / deny for B\'s resource; nothing of B in the response',
    status: 'PENDING', enabledBy: '#16590 D3/D4 — grant lookup per (party, azp, jkt) + downstream party ownership (P5)',
    needs: 'two seeded customers with journeys, and the D4 exchange in mcp-service',
  },
  {
    id: 'proposal-executes-only-after-consumed-sca', source: 'ADR-0383 Delivery check: Proposal approval',
    title: 'A proposal created via MCP executes only after a consumed SCA challenge; a second approval is refused',
    expected: 'no execution before consume; second approval 409/refused',
    status: 'PENDING', enabledBy: '#16590 D6 — proposal store + customer approval surface',
    needs: 'the proposal store and its customer-edge approval routes',
  },
  {
    id: 'client-secret-after-exception-expiry', source: 'ADR-0385 PAR and authorize',
    title: 'A Tier H client secret used after its exception expiry',
    expected: 'HTTP 401 error=invalid_client',
    status: 'PENDING', enabledBy: P3, needs: 'an allowlist declaration with an expired secret exception, on a local Keycloak',
  },
  {
    id: 'registration-unreachable-from-mcp-authorize', source: 'ADR-0385 PAR and authorize; D2.3',
    title: 'The registration endpoint cannot be reached from an MCP authorize',
    expected: 'no registration form reachable; no code',
    status: 'PENDING', enabledBy: FLOW, needs: 'the per-client flow override, to know which page links must be absent',
  },
  {
    id: 'grants-survive-credential-rotation', source: 'ADR-0385 Admission (Tier H)',
    title: 'Rotating the client key or secret keeps existing grants refreshable',
    expected: 'refresh HTTP 200 after rotation',
    status: 'PENDING', enabledBy: P3, needs: 'admin access to rotate the client credential on a local Keycloak',
  },
  {
    id: 'young-passkey-or-device-refused', source: 'ADR-0385 PAR and authorize; D2.3',
    title: 'A passkey or SCA device enrolled less than 72 h ago cannot authorize a connect',
    expected: 'connect refused, no code',
    status: 'PENDING', enabledBy: `${FLOW}; ${SCA}`, needs: 'a way to seed enrolment age (clock or fixture) on a local stack',
  },
  {
    id: 'auth-session-older-than-300s-no-code', source: 'ADR-0385 SCA linking',
    title: 'An auth session older than 300 s',
    expected: 'no code',
    status: 'PENDING', enabledBy: SCA, needs: 'the withheld-code authenticator',
  },
  {
    id: 'no-code-before-consume', source: 'ADR-0385 SCA linking',
    title: 'No code is issued before the SCA challenge is consumed',
    expected: 'no redirect with code while the challenge is pending',
    status: 'PENDING', enabledBy: SCA, needs: 'the withheld-code authenticator',
  },
  {
    id: 'consume-ok-issuance-fails-no-code', source: 'ADR-0385 SCA linking',
    title: 'A consume that succeeds while code issuance fails',
    expected: 'no code, and a retry is refused',
    status: 'PENDING', enabledBy: SCA, needs: 'a fault-injection hook in the withheld-code authenticator',
  },
  {
    id: 'general-consumer-cannot-spend-mcp-connect', source: 'ADR-0385 SCA linking',
    title: 'A ConsumerScope.General consumer spending an MCP_CLIENT_CONNECT challenge',
    expected: 'consume refused (409/403)',
    status: 'PENDING', enabledBy: `${SCA}; sca-service ReservedNamespace entry`, needs: 'the purpose and a General-scope M2M token',
  },
  {
    id: 'fourth-pending-authorization-refused', source: 'ADR-0385 Throttling',
    title: 'The 4th pending authorization for a party',
    expected: 'refused and audited',
    status: 'PENDING', enabledBy: '#16590 D5 / ADR-0385 D4 consent-flood caps', needs: 'the pending-authorization counter',
  },
  {
    id: 'per-client-budget-429', source: 'ADR-0385 Throttling',
    title: 'Per-client budget exceeded across parties',
    expected: 'HTTP 429 with Retry-After for that client\'s parties',
    status: 'PENDING', enabledBy: '#16590 D5 — Valkey buckets (P4)', needs: 'several seeded parties on one client and a lowered budget',
  },
  {
    id: 'suspend-revokes-client', source: 'ADR-0385 Throttling',
    title: 'Emergency suspend of a client',
    expected: 'Keycloak tokens revoked, credential disabled, next MCP call denied (401)',
    status: 'PENDING', enabledBy: 'ADR-0384 D4 kill switch (#16607)', needs: 'the suspend operation',
  },
]

export function loadSession(file: string | undefined): Promise<Session & { dpop?: DpopKey }> {
  if (!file) throw new Skip('MCP_HARNESS_SESSION_FILE not set (run the journey first)')
  const s = JSON.parse(readFileSync(file, 'utf8')) as Session
  return (s.dpopPrivateJwk ? importDpopKey(s.dpopPrivateJwk) : Promise.resolve(undefined)).then((dpop) => ({ ...s, dpop }))
}

export function contextFor(cfg: HarnessConfig): AttackContext {
  let d: Promise<Discovery> | undefined
  let s: Promise<Session & { dpop?: DpopKey }> | undefined
  return {
    cfg,
    discovery: () => (d ??= discover(cfg.resourceUrl, cfg.authServerUrl)),
    session: () => (s ??= Promise.resolve().then(() => loadSession(cfg.sessionFile))),
  }
}

export interface CaseResult { id: string; status: Status; outcome: Outcome; detail: string }

export async function runAttacks(cases: AttackCase[], ctx: AttackContext, o: { includePending: boolean }): Promise<CaseResult[]> {
  const out: CaseResult[] = []
  for (const c of cases) {
    if (c.status === 'PENDING' && (!o.includePending || !c.run)) {
      out.push({ id: c.id, status: c.status, outcome: 'PENDING', detail: c.needs ? `${c.enabledBy}; body needs ${c.needs}` : c.enabledBy ?? '' })
      continue
    }
    try {
      await c.run!(ctx)
      out.push({ id: c.id, status: c.status, outcome: c.status === 'ACTIVE' ? 'PASS' : 'PENDING-WOULD-PASS', detail: '' })
    } catch (e) {
      if (e instanceof Skip) out.push({ id: c.id, status: c.status, outcome: c.status === 'ACTIVE' ? 'SKIP' : 'PENDING', detail: e.message })
      else out.push({ id: c.id, status: c.status, outcome: c.status === 'ACTIVE' ? 'FAIL' : 'PENDING-FAILS', detail: (e as Error).message })
    }
  }
  return out
}

/** Exit code: 1 if any ACTIVE case failed, 3 if any ACTIVE case was skipped, else 0. PENDING never fails a run. */
export const exitCodeFor = (rs: CaseResult[]): number =>
  rs.some((r) => r.outcome === 'FAIL') ? 1 : rs.some((r) => r.outcome === 'SKIP') ? 3 : 0
