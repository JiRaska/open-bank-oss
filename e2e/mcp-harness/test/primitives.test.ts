// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
import assert from 'node:assert/strict'
import { createHash, createPublicKey, verify } from 'node:crypto'
import { test } from 'node:test'
import { createDpopProof, generateDpopKey, htuOf } from '../src/dpop.ts'
import { validateAuthorizationResponse } from '../src/oauth.ts'
import { pkcePair, s256 } from '../src/pkce.ts'
import { linkingPayload, SoftwareScaDevice } from '../src/sca-device.ts'

test('PKCE S256 is base64url(SHA-256(verifier)) and the verifier is 43 chars', () => {
  const p = pkcePair()
  assert.equal(p.verifier.length, 43)
  assert.equal(p.challenge, createHash('sha256').update(p.verifier).digest('base64url'))
  assert.equal(s256(p.verifier), p.challenge)
})

test('DPoP proof is a verifiable ES256 dpop+jwt with htm/htu/ath', async () => {
  const k = await generateDpopKey()
  const proof = await createDpopProof(k, { htm: 'POST', htu: htuOf('https://mcp.example/mcp?x=1#f'), accessToken: 'at' })
  const [h, p, s] = proof.split('.')
  const header = JSON.parse(Buffer.from(h, 'base64url').toString())
  const payload = JSON.parse(Buffer.from(p, 'base64url').toString())
  assert.equal(header.typ, 'dpop+jwt')
  assert.equal(header.jwk.d, undefined, 'the proof header must carry the public key only')
  assert.equal(payload.htu, 'https://mcp.example/mcp')
  assert.equal(payload.ath, createHash('sha256').update('at').digest('base64url'))
  assert.ok(verify('sha256', Buffer.from(`${h}.${p}`), { key: createPublicKey({ key: header.jwk, format: 'jwk' }), dsaEncoding: 'ieee-p1363' }, Buffer.from(s, 'base64url')))
  // a proof signed by another key must NOT verify against the advertised one (attack primitive)
  const other = await generateDpopKey()
  const forged = (await createDpopProof(k, { htm: 'POST', htu: 'https://x/', signWith: other })).split('.')
  assert.equal(verify('sha256', Buffer.from(`${forged[0]}.${forged[1]}`), { key: createPublicKey({ key: k.publicJwk, format: 'jwk' }), dsaEncoding: 'ieee-p1363' }, Buffer.from(forged[2], 'base64url')), false)
})

test('authorization response: state and RFC 9207 iss are enforced', () => {
  const ok = 'http://127.0.0.1:1/cb?code=c&state=s&iss=https%3A%2F%2Fas'
  assert.equal(validateAuthorizationResponse(ok, { state: 's', issuer: 'https://as', issRequired: true }), 'c')
  assert.throws(() => validateAuthorizationResponse(ok, { state: 'other', issuer: 'https://as', issRequired: true }), /state/)
  assert.throws(() => validateAuthorizationResponse(ok, { state: 's', issuer: 'https://evil', issRequired: true }), /iss mismatch/)
  assert.throws(() => validateAuthorizationResponse('http://127.0.0.1:1/cb?code=c&state=s', { state: 's', issuer: 'https://as', issRequired: true }), /iss missing/)
})

test('SCA linking payloads match sca-service DeviceApproval.kt', () => {
  const id = '6f1c0d9e-0000-4000-8000-000000000001'
  assert.equal(linkingPayload({ id, purpose: 'PAYMENT', amount: '10.5', currency: 'CZK', creditorIban: 'CZ65', reference: 'r' }, 'APPROVED'), `${id}|APPROVED|10.5|CZK|CZ65|r`)
  assert.equal(linkingPayload({ id, purpose: 'CONSENT_GRANT' }, 'DENIED'), `${id}|DENIED||||`)
  assert.equal(linkingPayload({ id, purpose: 'APPROVAL', approvalRequestId: 'ar', payloadSha256: 'ABC', amount: '1000', currency: 'czk', creditorIban: 'cz65 0800' }, 'APPROVED'), `${id}|APPROVED|APPROVAL|ar|abc|1000.00|CZK|CZ650800`)
  assert.equal(linkingPayload({ id, purpose: 'MCP_CLIENT_CONNECT', approvalRequestId: 'par-1', payloadSha256: 'FF' }, 'APPROVED'), `${id}|APPROVED|MCP_CLIENT_CONNECT|par-1|ff`)
})

test('software SCA device signs DER ECDSA that verifies against its enrolled SPKI', () => {
  const d = new SoftwareScaDevice('http://sca.test', 'p', 't')
  const sig = d.sign('payload')
  const key = createPublicKey({ key: Buffer.from(d.publicKeySpkiB64, 'base64'), format: 'der', type: 'spki' })
  assert.ok(verify('sha256', Buffer.from('payload'), key, Buffer.from(sig, 'base64')))
  assert.equal(verify('sha256', Buffer.from('payload2'), key, Buffer.from(sig, 'base64')), false)
})
