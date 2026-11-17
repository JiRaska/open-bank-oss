// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
//
// Target guard. The harness sends forged tokens, replays proofs and approves SCA challenges, so it
// must never be pointed at a production bank by accident. A target host is allowed only when it is
// loopback, `*.localhost`, `*.test`, or carries a `sandbox|staging|dev|qa` label below its TLD, or
// when MCP_HARNESS_I_UNDERSTAND_NON_LOCAL names that exact host. URLs are parsed, never substring
// matched, and a URL carrying userinfo is refused outright (the `https://sandbox.x@prod.bank`
// trick). The SCA device has a stricter rule with NO override: it self-approves challenges.

const TEST_LABELS = new Set(['sandbox', 'staging', 'dev', 'qa'])

function parse(url: string): URL {
  let u: URL
  try { u = new URL(url) } catch { throw new TargetRefused(`not a URL: ${url}`) }
  if (u.username || u.password) throw new TargetRefused(`URL carries userinfo, refused: ${u.host}`)
  if (u.protocol !== 'http:' && u.protocol !== 'https:') throw new TargetRefused(`unsupported scheme ${u.protocol}`)
  return u
}

export class TargetRefused extends Error {}

const hostOf = (u: URL): string => u.hostname.toLowerCase().replace(/^\[|\]$/g, '').replace(/\.$/, '')

export function isLocalOrTestHost(host: string): boolean {
  if (host === 'localhost' || host === '::1') return true
  if (/^127\.\d{1,3}\.\d{1,3}\.\d{1,3}$/.test(host)) return true
  return host.endsWith('.localhost') || host.endsWith('.test')
}

/** A `sandbox|staging|dev|qa` label anywhere except the TLD (`.dev` the TLD does not count). */
export function hasTestLabel(host: string): boolean {
  if (/^[\d.]+$/.test(host) || host.includes(':')) return false
  const labels = host.split('.')
  return labels.slice(0, -1).some((l) => TEST_LABELS.has(l))
}

export function assertTarget(url: string, env: NodeJS.ProcessEnv = process.env): void {
  const host = hostOf(parse(url))
  if (isLocalOrTestHost(host) || hasTestLabel(host)) return
  const override = env.MCP_HARNESS_I_UNDERSTAND_NON_LOCAL?.trim().toLowerCase()
  if (override && override === host) return
  throw new TargetRefused(`target host ${host} is not loopback, *.localhost, *.test or a sandbox/staging/dev/qa host; set MCP_HARNESS_I_UNDERSTAND_NON_LOCAL=${host} to accept it`)
}

/** The SCA device self-approves: loopback / *.localhost / *.test / sandbox label only. No override. */
export function assertScaTarget(url: string): void {
  const host = hostOf(parse(url))
  if (isLocalOrTestHost(host) || hasTestLabel(host)) return
  throw new TargetRefused(`the software SCA device never self-approves against ${host} (no override exists)`)
}
