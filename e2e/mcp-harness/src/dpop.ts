// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
//
// RFC 9449 DPoP proofs (ES256) built on node:crypto WebCrypto — no JOSE dependency. The attack
// suite needs to build deliberately WRONG proofs (other key, replayed jti, wrong htu, stale iat),
// which is why every claim is overridable.
import { createHash, randomUUID, webcrypto } from 'node:crypto'
import { b64url } from './b64.ts'

const { subtle } = webcrypto

export interface DpopKey {
  privateKey: webcrypto.CryptoKey
  publicJwk: webcrypto.JsonWebKey
  /** RFC 7638 thumbprint of the public key — the value a bound token carries as `cnf.jkt`. */
  jkt: string
}

export async function generateDpopKey(): Promise<DpopKey> {
  const pair = await subtle.generateKey({ name: 'ECDSA', namedCurve: 'P-256' }, true, ['sign', 'verify'])
  return fromKeyPair(pair.privateKey, await subtle.exportKey('jwk', pair.publicKey))
}

export async function exportDpopKey(key: DpopKey): Promise<webcrypto.JsonWebKey> {
  return subtle.exportKey('jwk', key.privateKey)
}

export async function importDpopKey(privateJwk: webcrypto.JsonWebKey): Promise<DpopKey> {
  const privateKey = await subtle.importKey('jwk', privateJwk, { name: 'ECDSA', namedCurve: 'P-256' }, true, ['sign'])
  const { kty, crv, x, y } = privateJwk
  return fromKeyPair(privateKey, { kty, crv, x, y })
}

function fromKeyPair(privateKey: webcrypto.CryptoKey, jwk: webcrypto.JsonWebKey): DpopKey {
  const publicJwk = { kty: jwk.kty, crv: jwk.crv, x: jwk.x, y: jwk.y }
  return { privateKey, publicJwk, jkt: jwkThumbprint(publicJwk) }
}

/** RFC 7638: SHA-256 over the lexicographically ordered required members of an EC JWK. */
export function jwkThumbprint(jwk: webcrypto.JsonWebKey): string {
  const canonical = JSON.stringify({ crv: jwk.crv, kty: jwk.kty, x: jwk.x, y: jwk.y })
  return b64url(createHash('sha256').update(canonical).digest())
}

/** RFC 9449 §4.2: `htu` is the request URI without query and fragment. */
export function htuOf(url: string): string {
  const u = new URL(url)
  return `${u.origin}${u.pathname}`
}

export interface ProofOptions {
  htm: string
  htu: string
  accessToken?: string
  nonce?: string
  jti?: string
  iat?: number
  /** Attack input only: sign with this key while advertising another key's JWK. */
  signWith?: DpopKey
}

export async function createDpopProof(key: DpopKey, o: ProofOptions): Promise<string> {
  const header = { typ: 'dpop+jwt', alg: 'ES256', jwk: key.publicJwk }
  const payload: Record<string, unknown> = {
    jti: o.jti ?? randomUUID(),
    htm: o.htm,
    htu: o.htu,
    iat: o.iat ?? Math.floor(Date.now() / 1000),
  }
  if (o.accessToken) payload.ath = b64url(createHash('sha256').update(o.accessToken).digest())
  if (o.nonce) payload.nonce = o.nonce
  const input = `${b64url(JSON.stringify(header))}.${b64url(JSON.stringify(payload))}`
  const sig = await subtle.sign({ name: 'ECDSA', hash: 'SHA-256' }, (o.signWith ?? key).privateKey, Buffer.from(input))
  return `${input}.${b64url(sig)}`
}
