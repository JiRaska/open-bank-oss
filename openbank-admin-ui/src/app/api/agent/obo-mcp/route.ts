// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// ADR-0224 D4 (embedded-console profile): the BFF relays MCP JSON-RPC for a signed-in operator
// under an OBO-exchanged, audience-restricted token — the exchanged token NEVER leaves the
// server side. The browser sees only this route; mcp-service sees a HUMAN principal whose realm
// roles were bounded at issuance (resolver: mcp.obo.enabled, ADR-0224 phase 1b).
//
// The whole chain is inert by default: this route 404s unless OBO_MCP_ENABLED=true, the realm
// permission lives in #2762, and mcp-service's resolver flag defaults off — three independent
// flips must all be turned on deliberately.
//
// The exchanged token is cached server-side keyed by a HASH of the operator's subject token
// (the subject token itself is never a map key), evicted at expiry minus a 30 s skew — one
// exchange per operator session per 5-minute token lifetime, not one per JSON-RPC call.

import { createHash } from 'crypto'
import { NextRequest, NextResponse } from 'next/server'
import { auth } from '@/auth'

export const dynamic = 'force-dynamic'

const KC_INTERNAL = process.env.KEYCLOAK_URL || 'http://keycloak:8080'
const KC_REALM = process.env.KEYCLOAK_REALM || 'openbank'
const CLIENT_ID = process.env.KEYCLOAK_CLIENT_ID || 'openbank-admin-ui'
const CLIENT_SECRET = process.env.KEYCLOAK_CLIENT_SECRET || ''
const MCP_AUDIENCE = process.env.OBO_MCP_AUDIENCE || 'openbank-mcp-service'
const ENABLED = process.env.OBO_MCP_ENABLED === 'true'
const EXPIRY_SKEW_MS = 30_000
const CACHE_MAX_ENTRIES = 1000

function mcpUrl(): string {
  if (process.env.SERVICES_HOST === 'container') {
    return 'http://openbank-mcp-service:8085/mcp'
  }
  return process.env.MCP_SERVICE_URL ?? 'http://localhost:8085/mcp'
}

const oboCache = new Map<string, { token: string; expiresAt: number }>()

function tokenExpMs(token: string): number {
  const payload = JSON.parse(Buffer.from(token.split('.')[1], 'base64url').toString())
  return (payload.exp ?? 0) * 1000
}

async function oboToken(subjectToken: string): Promise<string> {
  const key = createHash('sha256').update(subjectToken).digest('hex')
  const cached = oboCache.get(key)
  if (cached && cached.expiresAt - EXPIRY_SKEW_MS > Date.now()) return cached.token

  const res = await fetch(`${KC_INTERNAL}/realms/${KC_REALM}/protocol/openid-connect/token`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({
      grant_type: 'urn:ietf:params:oauth:grant-type:token-exchange',
      client_id: CLIENT_ID,
      client_secret: CLIENT_SECRET,
      subject_token: subjectToken,
      subject_token_type: 'urn:ietf:params:oauth:token-type:access_token',
      requested_token_type: 'urn:ietf:params:oauth:token-type:access_token',
      audience: MCP_AUDIENCE,
    }),
    cache: 'no-store',
  })
  if (!res.ok) throw new Error(`token exchange failed: ${res.status}`)
  const data = (await res.json()) as { access_token: string }
  if (oboCache.size >= CACHE_MAX_ENTRIES) {
    const now = Date.now()
    for (const [k, v] of oboCache) if (v.expiresAt <= now) oboCache.delete(k)
  }
  oboCache.set(key, { token: data.access_token, expiresAt: tokenExpMs(data.access_token) })
  return data.access_token
}

export async function POST(req: NextRequest) {
  if (!ENABLED) {
    return NextResponse.json({ error: 'not_found' }, { status: 404 })
  }
  try {
    const body = await req.json()
    // Same session gate as every BFF relay (ADR-0056): no operator session, no relay.
    const accessToken = (await auth())?.user?.accessToken
    if (!accessToken) {
      return NextResponse.json(
        { jsonrpc: '2.0', id: null, error: { code: -32001, message: 'unauthenticated' } },
        { status: 401 },
      )
    }
    const obo = await oboToken(accessToken)
    const ctrl = new AbortController()
    const timer = setTimeout(() => ctrl.abort(), 10000)
    const res = await fetch(mcpUrl(), {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${obo}` },
      body: JSON.stringify(body),
      signal: ctrl.signal,
      cache: 'no-store',
    })
    clearTimeout(timer)
    const data = await res.json()
    return NextResponse.json(data, { status: res.status })
  } catch (e) {
    return NextResponse.json(
      { jsonrpc: '2.0', id: null, error: { code: -32603, message: e instanceof Error ? e.message : 'mcp_unreachable' } },
      { status: 502 },
    )
  }
}
