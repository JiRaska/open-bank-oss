// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
//
// A stub MCP resource server + authorization server used ONLY to prove the attack suite can fail
// (known-positive). `correct` mirrors today's openbank-mcp-service for the ACTIVE cases and the
// ADR-0383/0385 target for the PENDING ones; every other flaw switches exactly one defence off.
import { createHash, createPublicKey, verify } from 'node:crypto'
import type { AddressInfo } from 'node:net'
import { createServer } from 'node:http'
import type { IncomingMessage, Server } from 'node:http'

export type Flaw =
  | 'leak-tools-list' | 'leak-read-data' | 'leak-propose' | 'accept-any-token'
  | 'dpop-accept-bearer' | 'dpop-no-replay-store' | 'dpop-ignore-htu' | 'dpop-ignore-key'
  | 'dpop-proof-optional' | 'dpop-ignore-signature' | 'dpop-ignore-htm' | 'dpop-ignore-ath' | 'dpop-ignore-iat'
  | 'as-accept-plain-pkce' | 'as-accept-missing-pkce' | 'as-reusable-request-uri' | 'as-accept-any-resource'
  | 'as-ignore-client-binding' | 'as-ignore-extra-params' | 'as-allow-front-channel' | 'as-no-iss-support'
  /** not a defence: the protected-resource metadata names a non-local authorization server */
  | 'prm-foreign-as'

export interface Stub {
  url: string
  mcpUrl: string
  /** tokens the stub honours: token → bound jkt (undefined = plain bearer) */
  trust(token: string, jkt?: string): void
  close(): Promise<void>
}

const TOOLS = [{ name: 'list_accounts', description: 'x', inputSchema: { type: 'object' } }, { name: 'propose_payment', description: 'x', inputSchema: { type: 'object' } }]

const body = (req: IncomingMessage): Promise<string> => new Promise((ok) => { let s = ''; req.on('data', (c) => { s += c }); req.on('end', () => ok(s)) })

export async function startStub(flaws: Flaw[] = []): Promise<Stub> {
  const has = (f: Flaw) => flaws.includes(f)
  const trusted = new Map<string, string | undefined>()
  const seenJti = new Set<string>()
  const requestUris = new Map<string, { used: boolean; clientId: string }>()
  let base = ''

  const authorized = (req: IncomingMessage): 'anon' | 'ok' | 'reject' => {
    const h = req.headers.authorization
    if (!h) return 'anon'
    const [scheme, token] = h.split(' ')
    if (!trusted.has(token)) return has('accept-any-token') ? 'ok' : 'reject'
    const jkt = trusted.get(token)
    if (!jkt) return 'ok'
    if (scheme !== 'DPoP') return has('dpop-accept-bearer') ? 'ok' : 'reject'
    const proof = req.headers.dpop as string | undefined
    if (!proof) return has('dpop-proof-optional') ? 'ok' : 'reject'
    try {
      const [h64, p64, s64] = proof.split('.')
      const header = JSON.parse(Buffer.from(h64, 'base64url').toString())
      const p = JSON.parse(Buffer.from(p64, 'base64url').toString())
      const key = createPublicKey({ key: header.jwk, format: 'jwk' })
      if (!has('dpop-ignore-signature') && !verify('sha256', Buffer.from(`${h64}.${p64}`), { key, dsaEncoding: 'ieee-p1363' }, Buffer.from(s64, 'base64url'))) return 'reject'
      const thumb = createHash('sha256').update(JSON.stringify({ crv: header.jwk.crv, kty: header.jwk.kty, x: header.jwk.x, y: header.jwk.y })).digest('base64url')
      if (thumb !== jkt && !has('dpop-ignore-key')) return 'reject'
      if (p.htm !== req.method && !has('dpop-ignore-htm')) return 'reject'
      if (p.htu !== `${base}${req.url}` && !has('dpop-ignore-htu')) return 'reject'
      if (p.ath !== createHash('sha256').update(token).digest('base64url') && !has('dpop-ignore-ath')) return 'reject'
      if (Math.abs(Date.now() / 1000 - p.iat) > 60 && !has('dpop-ignore-iat')) return 'reject'
      if (!has('dpop-no-replay-store')) { if (seenJti.has(p.jti)) return 'reject'; seenJti.add(p.jti) }
      return 'ok'
    } catch { return 'reject' }
  }

  const server: Server = createServer(async (req, res) => {
    const json = (status: number, v: unknown, headers: Record<string, string> = {}) => { res.writeHead(status, { 'content-type': 'application/json', ...headers }); res.end(JSON.stringify(v)) }
    const raw = await body(req)
    const u = new URL(req.url ?? '/', base)
    if (u.pathname === '/.well-known/oauth-authorization-server') {
      return json(200, { issuer: base, authorization_endpoint: `${base}/authorize`, token_endpoint: `${base}/token`, pushed_authorization_request_endpoint: `${base}/par`, require_pushed_authorization_requests: true, code_challenge_methods_supported: ['S256'], dpop_signing_alg_values_supported: ['ES256'], authorization_response_iss_parameter_supported: !has('as-no-iss-support'), response_types_supported: ['code'] })
    }
    if (u.pathname === '/.well-known/oauth-protected-resource/mcp') return json(200, { resource: `${base}/mcp`, authorization_servers: [has('prm-foreign-as') ? 'https://login.examplebank.com' : base] })
    if (u.pathname === '/par') {
      const f = new URLSearchParams(raw)
      if (f.get('resource') !== `${base}/mcp` && !has('as-accept-any-resource')) return json(400, { error: 'invalid_target' })
      if (!f.get('code_challenge') && !has('as-accept-missing-pkce')) return json(400, { error: 'invalid_request' })
      if (f.get('code_challenge') && f.get('code_challenge_method') !== 'S256' && !has('as-accept-plain-pkce')) return json(400, { error: 'invalid_request' })
      const id = `urn:ietf:params:oauth:request_uri:${Math.random().toString(36).slice(2)}`
      requestUris.set(id, { used: false, clientId: f.get('client_id') ?? '' })
      return json(201, { request_uri: id, expires_in: 60 })
    }
    if (u.pathname === '/authorize') {
      const ru = u.searchParams.get('request_uri')
      if (!ru && has('as-allow-front-channel') && u.searchParams.get('response_type') === 'code') { res.writeHead(200, { 'content-type': 'text/html' }); return res.end('<html>login</html>') }
      const entry = ru ? requestUris.get(ru) : undefined
      if (!entry) return json(400, { error: 'invalid_request' })
      if (entry.clientId !== u.searchParams.get('client_id') && !has('as-ignore-client-binding')) return json(400, { error: 'invalid_request' })
      if ((u.searchParams.has('redirect_uri') || u.searchParams.has('scope')) && !has('as-ignore-extra-params')) return json(400, { error: 'invalid_request' })
      if (entry.used && !has('as-reusable-request-uri')) return json(400, { error: 'invalid_request_uri' })
      entry.used = true
      res.writeHead(200, { 'content-type': 'text/html' }); return res.end('<html>login</html>')
    }
    if (u.pathname === '/mcp' && req.method === 'POST') {
      const a = authorized(req)
      if (a === 'reject') return json(401, { error: 'invalid_token' }, { 'www-authenticate': `Bearer resource_metadata="${base}/.well-known/oauth-protected-resource/mcp"` })
      const msg = JSON.parse(raw)
      const anon = a === 'anon'
      if (msg.method === 'tools/list') return json(200, { jsonrpc: '2.0', id: msg.id, result: { tools: anon && !has('leak-tools-list') ? [] : TOOLS } })
      if (msg.method === 'tools/call') {
        const leak = msg.params.name === 'propose_payment' ? has('leak-propose') : has('leak-read-data')
        if (anon && !leak) return json(200, { jsonrpc: '2.0', id: msg.id, result: { content: [{ type: 'text', text: 'Authorization unavailable' }], isError: true } })
        return json(200, { jsonrpc: '2.0', id: msg.id, result: { content: [{ type: 'text', text: '[{"iban":"CZ00 leaked"}]' }] } })
      }
      return json(200, { jsonrpc: '2.0', id: msg.id, result: {} })
    }
    json(404, { error: 'not_found' })
  })
  await new Promise<void>((ok) => server.listen(0, '127.0.0.1', ok))
  base = `http://127.0.0.1:${(server.address() as AddressInfo).port}`
  return {
    url: base,
    mcpUrl: `${base}/mcp`,
    trust: (t, jkt) => { trusted.set(t, jkt) },
    close: () => new Promise((ok) => server.close(() => ok())),
  }
}
