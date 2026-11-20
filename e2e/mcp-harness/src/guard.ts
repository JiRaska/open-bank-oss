// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
//
// Target guard — a SAFETY NET against pointing the harness at a production bank by accident, not
// a security boundary. The harness forges tokens, replays proofs and approves SCA challenges.
// A target host is allowed when it is:
//   - loopback, `*.localhost` or `*.test`; or
//   - a labelled sandbox host: its LEFTMOST label is sandbox|staging|dev|qa AND it ends with one
//     of the suffixes in MCP_HARNESS_ALLOWED_SUFFIXES (comma-separated; default empty, because no
//     sandbox-labelled open-bank.tech host exists on main); or
//   - exactly the host named by MCP_HARNESS_I_UNDERSTAND_NON_LOCAL.
// URLs are parsed, never substring-matched; userinfo is always refused. Every redirect hop is
// re-checked (guardedFetch). The SCA device allows only the first two kinds — no override.
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

export function allowedSuffixes(env: NodeJS.ProcessEnv): string[] {
  return (env.MCP_HARNESS_ALLOWED_SUFFIXES ?? '').split(',').map((x) => x.trim().toLowerCase().replace(/^\./, '')).filter((x) => x.includes('.'))
}

/** Leftmost label is sandbox|staging|dev|qa AND the host sits under an allow-listed suffix. */
export function isLabelledSandbox(host: string, env: NodeJS.ProcessEnv): boolean {
  if (/^[\d.]+$/.test(host) || host.includes(':')) return false
  const [first, ...rest] = host.split('.')
  if (!TEST_LABELS.has(first) || rest.length < 2) return false
  return allowedSuffixes(env).some((sfx) => host.endsWith(`.${sfx}`))
}

export function assertTarget(url: string, env: NodeJS.ProcessEnv = process.env): void {
  const host = hostOf(parse(url))
  if (isLocalOrTestHost(host) || isLabelledSandbox(host, env)) return
  const override = env.MCP_HARNESS_I_UNDERSTAND_NON_LOCAL?.trim().toLowerCase()
  if (override && override === host) return
  throw new TargetRefused(`target host ${host} is not loopback, *.localhost, *.test or a sandbox-labelled host under MCP_HARNESS_ALLOWED_SUFFIXES; set MCP_HARNESS_I_UNDERSTAND_NON_LOCAL=${host} to accept it`)
}

/** The SCA device self-approves: loopback / *.localhost / *.test / sandbox label only. No override. */
export function assertScaTarget(url: string, env: NodeJS.ProcessEnv = process.env): void {
  const host = hostOf(parse(url))
  if (isLocalOrTestHost(host) || isLabelledSandbox(host, env)) return
  throw new TargetRefused(`the software SCA device never self-approves against ${host} (no override exists)`)
}

const MAX_HOPS = 5

/**
 * fetch with `redirect: 'manual'`, re-running assertTarget on the request URL and on every
 * Location before following it. Credentials never travel across a hop: a redirect is followed
 * WITHOUT the original headers, and only for GET/HEAD (a redirected POST is an error).
 */
export function guardedFetch(base: typeof fetch = fetch, env: NodeJS.ProcessEnv = process.env): typeof fetch {
  return async (input, init = {}) => {
    let url = input instanceof Request ? input.url : input.toString()
    assertTarget(url, env)
    let res = await base(input, { ...init, redirect: 'manual' })
    for (let hop = 0; res.status >= 300 && res.status < 400 && res.headers.get('location'); hop++) {
      const next = new URL(res.headers.get('location')!, url).toString()
      assertTarget(next, env)
      const method = (init.method ?? 'GET').toUpperCase()
      if (method !== 'GET' && method !== 'HEAD') throw new TargetRefused(`refusing to follow a ${res.status} for ${method} ${url}`)
      if (hop >= MAX_HOPS) throw new TargetRefused(`too many redirects from ${url}`)
      url = next
      res = await base(url, { method, redirect: 'manual' })
    }
    return res
  }
}
