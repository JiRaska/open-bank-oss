// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

export interface PublicUrlPolicy { production: boolean; buildPhase: boolean; allowInsecureLoopback: boolean }

export function requireSecurePublicUrl(name: string, value: string, policy: PublicUrlPolicy): string {
  if (!policy.production || policy.buildPhase) return value
  let parsed: URL
  try { parsed = new URL(value) } catch { throw new Error(`${name} must be an absolute URL`) }
  if (parsed.protocol === 'https:') return value
  const loopback = parsed.hostname === 'localhost' || parsed.hostname === '127.0.0.1' || parsed.hostname === '::1'
  if (loopback && policy.allowInsecureLoopback) return value
  throw new Error(`${name} must use https:// in production`)
}
