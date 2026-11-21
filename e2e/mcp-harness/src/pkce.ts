// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
import { createHash, randomBytes } from 'node:crypto'
import { b64url } from './b64.ts'

/** RFC 7636 PKCE pair. S256 only — `plain` exists in this harness solely as an attack input. */
export function pkcePair(): { verifier: string; challenge: string } {
  const verifier = b64url(randomBytes(32))
  return { verifier, challenge: s256(verifier) }
}

export const s256 = (verifier: string): string => b64url(createHash('sha256').update(verifier).digest())

export const randomState = (): string => b64url(randomBytes(16))
