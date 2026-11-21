// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
//
// The MCP side, through the official SDK client. Authorization is injected by a fetch wrapper so
// every request carries a FRESH DPoP proof bound to its own method, URL and access token
// (RFC 9449 §7), and a resource-server nonce challenge (ADR-0383 D2) is answered once.
import { Client } from '@modelcontextprotocol/sdk/client/index.js'
import { StreamableHTTPClientTransport } from '@modelcontextprotocol/sdk/client/streamableHttp.js'
import type { DpopKey } from './dpop.ts'
import { createDpopProof, htuOf } from './dpop.ts'
import { assertTarget, guardedFetch, TargetRefused } from './guard.ts'

export interface Credential {
  accessToken: string
  /** `DPoP` when the token is sender-constrained, `Bearer` otherwise (Tier H only, ADR-0385). */
  scheme: 'DPoP' | 'Bearer'
  dpop?: DpopKey
}

export async function authHeaders(cred: Credential, method: string, url: string, nonce?: string): Promise<Record<string, string>> {
  const h: Record<string, string> = { authorization: `${cred.scheme} ${cred.accessToken}` }
  if (cred.scheme === 'DPoP') {
    if (!cred.dpop) throw new Error('DPoP scheme without a DPoP key')
    h.DPoP = await createDpopProof(cred.dpop, { htm: method, htu: htuOf(url), accessToken: cred.accessToken, nonce })
  }
  return h
}

/**
 * Adds the credential and a fresh DPoP proof. The target is checked BEFORE any header is built,
 * and a redirect is an error: a token or proof must never be replayed to another URL.
 */
export function authenticatedFetch(cred: Credential, base: typeof fetch = fetch): typeof fetch {
  let nonce: string | undefined
  const go = async (input: string | URL | Request, init: RequestInit = {}): Promise<Response> => {
    const url = input instanceof Request ? input.url : input.toString()
    assertTarget(url)
    const method = (init.method ?? (input instanceof Request ? input.method : 'GET')).toUpperCase()
    const headers = new Headers(init.headers)
    for (const [k, v] of Object.entries(await authHeaders(cred, method, url, nonce))) headers.set(k, v)
    const res = await base(input, { ...init, headers, redirect: 'manual' })
    if (res.status >= 300 && res.status < 400) {
      await res.body?.cancel()
      throw new TargetRefused(`authenticated request to ${url} answered ${res.status}; redirects are not followed with credentials`)
    }
    return res
  }
  return async (input, init) => {
    const res = await go(input, init)
    const demanded = res.headers.get('dpop-nonce')
    if (res.status === 401 && cred.scheme === 'DPoP' && demanded && demanded !== nonce) {
      nonce = demanded
      return go(input, init)
    }
    if (demanded) nonce = demanded
    return res
  }
}

export async function connectMcp(resourceUrl: string, cred?: Credential): Promise<Client> {
  const transport = new StreamableHTTPClientTransport(new URL(resourceUrl), { fetch: cred ? authenticatedFetch(cred) : guardedFetch() })
  const client = new Client({ name: 'openbank-mcp-harness', version: '0.0.0' })
  await client.connect(transport)
  return client
}
