// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

export const b64url = (data: Uint8Array | ArrayBuffer | string): string =>
  Buffer.from(typeof data === 'string' ? Buffer.from(data, 'utf8') : new Uint8Array(data as ArrayBuffer))
    .toString('base64url')

export const fromB64url = (s: string): Buffer => Buffer.from(s, 'base64url')

/** Decodes a JWT payload WITHOUT verifying it — for asserting claims the server issued us. */
export function decodeJwtPayload(jwt: string): Record<string, unknown> {
  const part = jwt.split('.')[1]
  if (!part) throw new Error('not a JWT')
  return JSON.parse(fromB64url(part).toString('utf8'))
}
