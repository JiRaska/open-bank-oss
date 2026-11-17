// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
//
// The in-app SCA leg, as a pluggable approver. `SoftwareScaDevice` is a test device against
// sca-service's REAL device API on main (ScaResource): enrol an ES256 key
// (POST /api/v1/sca/parties/{partyId}/devices), list pending challenges
// (GET .../challenges/pending), sign the dynamic-linking payload and record the decision
// (POST /api/v1/sca/challenges/{id}/decision). There is no test-only bypass endpoint, and this
// device does not need one: it signs exactly what DeviceApproval.kt verifies.
import { createSign, generateKeyPairSync, randomUUID } from 'node:crypto'
import type { KeyObject } from 'node:crypto'
import { send } from './http.ts'

export interface PendingChallenge {
  id: string
  purpose: string
  amount?: string | null
  currency?: string | null
  creditorIban?: string | null
  reference?: string | null
  approvalRequestId?: string | null
  payloadSha256?: string | null
}

export type Decision = 'APPROVED' | 'DENIED'

export interface ScaApprover {
  /** Approve the single challenge that matches; resolves once the decision is recorded. */
  approve(match: (c: PendingChallenge) => boolean, o: { timeoutMs: number }): Promise<PendingChallenge>
}

const canonicalAmount = (a: string): string => {
  const [i, f = ''] = a.trim().split('.')
  if (f.length > 2) throw new Error(`amount ${a} needs more than two fractional digits`)
  return `${BigInt(i)}.${f.padEnd(2, '0')}`
}

/**
 * Mirrors `ScaChallenge.dynamicLinkingPayload` / `approvalLinkingPayload`, and — for the purpose
 * ADR-0385 D3 introduces — `challengeId|decision|MCP_CLIENT_CONNECT|approvalRequestId|payloadSha256`.
 */
export function linkingPayload(c: PendingChallenge, d: Decision): string {
  if (c.purpose === 'APPROVAL' || c.purpose === 'MCP_CLIENT_CONNECT') {
    const s = [c.id, d, c.purpose, c.approvalRequestId ?? '', (c.payloadSha256 ?? '').toLowerCase()]
    if (c.purpose === 'APPROVAL' && c.amount) {
      s.push(canonicalAmount(c.amount), (c.currency ?? '').toUpperCase(), (c.creditorIban ?? '').replace(/ /g, '').toUpperCase())
    }
    return s.join('|')
  }
  return [c.id, d, c.amount ?? '', c.currency ?? '', c.creditorIban ?? '', c.reference ?? ''].join('|')
}

export class SoftwareScaDevice implements ScaApprover {
  readonly credentialId = `mcp-harness-${randomUUID()}`
  private readonly privateKey: KeyObject
  readonly publicKeySpkiB64: string
  private readonly baseUrl: string
  private readonly partyId: string
  private readonly token: string

  constructor(baseUrl: string, partyId: string, token: string) {
    this.baseUrl = baseUrl.replace(/\/$/, '')
    this.partyId = partyId
    this.token = token
    const { privateKey, publicKey } = generateKeyPairSync('ec', { namedCurve: 'P-256' })
    this.privateKey = privateKey
    this.publicKeySpkiB64 = publicKey.export({ type: 'spki', format: 'der' }).toString('base64')
  }

  private headers(): Record<string, string> {
    return { authorization: `Bearer ${this.token}`, 'content-type': 'application/json' }
  }

  async enroll(): Promise<void> {
    const r = await send(`${this.baseUrl}/api/v1/sca/parties/${this.partyId}/devices`, {
      method: 'POST',
      headers: this.headers(),
      body: JSON.stringify({ credentialId: this.credentialId, publicKey: this.publicKeySpkiB64, algorithm: 'ES256' }),
    })
    if (r.status !== 201) throw new Error(`device enrolment failed: HTTP ${r.status}`)
  }

  /** DER ECDSA over the payload — the form JcaDeviceAssertionVerifier's SHA256withECDSA consumes. */
  sign(payload: string): string {
    return createSign('SHA256').update(payload, 'utf8').sign(this.privateKey).toString('base64')
  }

  async pending(): Promise<PendingChallenge[]> {
    const r = await send(`${this.baseUrl}/api/v1/sca/parties/${this.partyId}/challenges/pending`, { headers: this.headers() })
    if (r.status !== 200) throw new Error(`pending challenges: HTTP ${r.status}`)
    return r.json as PendingChallenge[]
  }

  async decide(c: PendingChallenge, d: Decision): Promise<number> {
    const r = await send(`${this.baseUrl}/api/v1/sca/challenges/${c.id}/decision`, {
      method: 'POST',
      headers: this.headers(),
      body: JSON.stringify({ credentialId: this.credentialId, decision: d, signature: this.sign(linkingPayload(c, d)) }),
    })
    return r.status
  }

  async approve(match: (c: PendingChallenge) => boolean, o: { timeoutMs: number }): Promise<PendingChallenge> {
    const deadline = Date.now() + o.timeoutMs
    while (Date.now() < deadline) {
      const hit = (await this.pending()).filter(match)
      if (hit.length > 1) throw new Error(`${hit.length} pending challenges match — refusing to guess which to approve`)
      if (hit.length === 1) {
        const status = await this.decide(hit[0], 'APPROVED')
        if (status !== 200) throw new Error(`decision rejected: HTTP ${status}`)
        return hit[0]
      }
      await new Promise((r) => setTimeout(r, 1000))
    }
    throw new Error('no matching SCA challenge appeared')
  }
}

/** Fallback when no sca-service is configured: a human approves on a real phone. */
export class ManualApprover implements ScaApprover {
  async approve(_m: (c: PendingChallenge) => boolean, o: { timeoutMs: number }): Promise<PendingChallenge> {
    process.stderr.write(`approve the connect in the bank app within ${Math.round(o.timeoutMs / 1000)} s\n`)
    return { id: 'manual', purpose: 'unknown' }
  }
}
