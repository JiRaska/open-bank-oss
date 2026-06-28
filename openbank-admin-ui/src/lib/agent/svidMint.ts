// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { readFileSync } from 'fs'
import crypto from 'crypto'

/**
 * ADR-0031 D3b (mint side): mint a short-TTL `pki-agent` client certificate (CN = agent id) from
 * the existing OpenBao and prove possession, so the agent identity on the `/mcp` call is a
 * cryptographic per-run credential instead of the forgeable `X-Agent-Id` header.
 *
 * Returns the headers to attach, or `null` when OpenBao is unreachable / not yet bootstrapped — the
 * caller then keeps the legacy `X-Agent-Id` header and agent-service's verifier falls back to the
 * D3a role binding (the verify side is additive, flag-gated). The cert PEM is base64-encoded because
 * an HTTP header cannot carry the PEM's newlines; agent-service base64-decodes it.
 */
export interface SvidHeaders {
  'X-Agent-Cert': string
  'X-Agent-PoP': string
  'X-Agent-PoP-Ts': string
  'X-Agent-PoP-Nonce': string
}

const BAO_ADDR = process.env.OPENBAO_ADDR ?? 'http://openbao.vault.svc:8200'
const BAO_ROLE = process.env.OPENBAO_MCP_ROLE ?? 'admin-ui-mcp'
const SA_TOKEN_PATH = '/var/run/secrets/kubernetes.io/serviceaccount/token'
const ISSUE_PATH = process.env.OPENBAO_PKI_ISSUE_PATH ?? 'pki-agent/issue/agent-run'

async function loginToken(): Promise<string> {
  const jwt = readFileSync(SA_TOKEN_PATH, 'utf-8').trim()
  const res = await fetch(`${BAO_ADDR}/v1/auth/kubernetes/login`, {
    method: 'POST',
    body: JSON.stringify({ role: BAO_ROLE, jwt }),
    signal: AbortSignal.timeout(5000),
    cache: 'no-store',
  })
  if (!res.ok) throw new Error(`openbao login ${res.status}`)
  return (await res.json()).auth.client_token
}

/** Sign the proof-of-possession over `<ts>.<nonce>` with the leaf's EC key (SHA256withECDSA, DER). */
export function signPoP(privateKeyPem: string, ts: string, nonce: string): string {
  return crypto.createSign('SHA256').update(`${ts}.${nonce}`).sign(privateKeyPem).toString('base64')
}

/** Build the four SVID headers from an issued cert + key (pure — unit-testable without OpenBao). */
export function buildSvidHeaders(certPem: string, privateKeyPem: string): SvidHeaders {
  const ts = Date.now().toString()
  const nonce = crypto.randomBytes(12).toString('hex')
  return {
    'X-Agent-Cert': Buffer.from(certPem, 'utf-8').toString('base64'),
    'X-Agent-PoP': signPoP(privateKeyPem, ts, nonce),
    'X-Agent-PoP-Ts': ts,
    'X-Agent-PoP-Nonce': nonce,
  }
}

export async function mintSvidHeaders(agentId: string): Promise<SvidHeaders | null> {
  try {
    const token = await loginToken()
    const res = await fetch(`${BAO_ADDR}/v1/${ISSUE_PATH}`, {
      method: 'POST',
      headers: { 'X-Vault-Token': token },
      body: JSON.stringify({ common_name: agentId, ttl: '300s' }),
      signal: AbortSignal.timeout(5000),
      cache: 'no-store',
    })
    if (!res.ok) throw new Error(`openbao issue ${res.status}`)
    const data = (await res.json()).data
    return buildSvidHeaders(data.certificate, data.private_key)
  } catch (e) {
    // Visible on OpenBao outage/mis-bootstrap so the silent fallback to the D3a header binding is
    // diagnosable; never logs the key or cert.
    console.warn(`[svidMint] mint failed, falling back to X-Agent-Id: ${e instanceof Error ? e.message : 'error'}`)
    return null
  }
}
