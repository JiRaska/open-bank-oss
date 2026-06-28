// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { describe, expect, it } from 'vitest'
import crypto from 'crypto'
import { buildSvidHeaders, signPoP } from '@/lib/agent/svidMint'

// ADR-0031 D3b (mint side): the BFF mints an OpenBao cert and proves possession with a
// SHA256withECDSA signature over "<ts>.<nonce>". These tests check the PoP is a valid ECDSA
// signature and the headers are shaped exactly as agent-service's AgentSvidVerifier expects
// (base64 cert, base64 DER signature, ts, nonce). Cross-runtime compatibility is by construction:
// crypto.sign produces DER ECDSA, which Java's SHA256withECDSA verifies.

const { privateKey, publicKey } = crypto.generateKeyPairSync('ec', { namedCurve: 'prime256v1' })
const privPem = privateKey.export({ type: 'pkcs8', format: 'pem' }) as string
const pubPem = publicKey.export({ type: 'spki', format: 'pem' }) as string

describe('svidMint', () => {
  it('signPoP produces a valid ECDSA signature over "<ts>.<nonce>"', () => {
    const sig = signPoP(privPem, '1735300000000', 'abc123')
    const ok = crypto.verify('SHA256', Buffer.from('1735300000000.abc123'), pubPem, Buffer.from(sig, 'base64'))
    expect(ok).toBe(true)
  })

  it('a PoP does not verify over a different ts/nonce', () => {
    const sig = signPoP(privPem, '1735300000000', 'abc123')
    const ok = crypto.verify('SHA256', Buffer.from('1735300000000.different'), pubPem, Buffer.from(sig, 'base64'))
    expect(ok).toBe(false)
  })

  it('buildSvidHeaders base64-encodes the cert and signs a verifiable PoP', () => {
    const certPem = '-----BEGIN CERTIFICATE-----\nMIIBexampleexample\n-----END CERTIFICATE-----\n'
    const h = buildSvidHeaders(certPem, privPem)
    expect(h['X-Agent-Cert']).toBe(Buffer.from(certPem, 'utf-8').toString('base64'))
    expect(h['X-Agent-PoP-Ts']).toMatch(/^\d+$/)
    expect(h['X-Agent-PoP-Nonce']).toMatch(/^[0-9a-f]{24}$/)
    const signed = `${h['X-Agent-PoP-Ts']}.${h['X-Agent-PoP-Nonce']}`
    const ok = crypto.verify('SHA256', Buffer.from(signed), pubPem, Buffer.from(h['X-Agent-PoP'], 'base64'))
    expect(ok).toBe(true)
  })
})
