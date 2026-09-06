// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// ADR-0230: "mutations only as approval proposals, never direct writes."
//
// delegation-service exposes three bank-side mutations — POST /{id}/suspend, POST
// /{id}/reinstate, DELETE /{id}. delegation-service now owns a durable lifecycle proposal store,
// but its mutation edge is dark-launched and admin-ui intentionally federates only GET list/detail.
// The unified inbox (ADR-0227) can READ immutable evidence; it cannot decide or execute it.
//
// So the console ships read-only, and this guard is what makes that a checked invariant instead
// of a claim in a PR body: the day someone adds the "obvious" direct suspend route, this test
// goes red and points at ADR-0230 rather than the change shipping quietly.
//
// It scans the WHOLE BFF tree, not just src/app/api/delegations/**, because the thing being
// prevented is a mutation reaching delegation-service from anywhere in admin-ui.

import { describe, expect, it } from 'vitest'
import { readFileSync, readdirSync, statSync } from 'fs'
import { join, sep } from 'path'

const API_ROOT = join(process.cwd(), 'src/app/api')
const UPSTREAM = 'delegation-service'

/** The single non-GET upstream call this console is allowed to make. */
const READ_ONLY_PROBE = '/api/v1/delegations/check'

/** CRUD of reusable presets changes no grant and is deliberately outside ADR-0230's guard. */
const ROLE_PRESET_PATH = '/api/v1/delegation-role-presets'

/** Bank-side mutations on delegation-service that must never be reachable from admin-ui. */
const FORBIDDEN_PATH_FRAGMENTS = ['/suspend', '/reinstate']

/**
 * Strip comments before matching. Without this the guard matches the prose that EXPLAINS the
 * rule — every one of these route files names `/suspend` in a WHY comment saying it is
 * deliberately absent, so an uncommented scan reports the compliant tree as broken. Only
 * line-start `//` is stripped, never mid-line, so a `http://…` inside a template literal
 * survives intact (projection-health/route.ts has one).
 */
export function stripComments(src: string): string {
  return src
    .replace(/\/\*[\s\S]*?\*\//g, ' ')
    .split('\n')
    .filter(line => !/^\s*\/\//.test(line))
    .join('\n')
}

export type Finding = { file: string; problem: string }

/**
 * The detector, exported so the falsifiability test below can run it against a known-positive.
 * Returns one finding per violation; an empty array means the tree is clean.
 */
export function findDelegationMutations(files: { file: string; source: string }[]): Finding[] {
  const findings: Finding[] = []

  for (const { file, source } of files) {
    const code = stripComments(source)
    if (!code.includes(UPSTREAM)) continue

    const upstreamPaths = [...code.matchAll(/\/api\/v1\/(?:delegations|delegation-role-presets)[^'"`\s)]*/g)].map(m => m[0])
    const methods = [...code.matchAll(/method:\s*['"]([A-Z]+)['"]/g)].map(m => m[1])

    for (const path of upstreamPaths) {
      for (const bad of FORBIDDEN_PATH_FRAGMENTS) {
        if (path.includes(bad)) {
          findings.push({ file, problem: `forwards to the bank-side mutation ${path}` })
        }
      }
    }

    for (const method of methods) {
      if (method === 'GET') continue
      if (method === 'POST' && upstreamPaths.some(p => p.startsWith(READ_ONLY_PROBE))) continue
      if (upstreamPaths.some(p => p.startsWith(ROLE_PRESET_PATH))) continue
      findings.push({ file, problem: `issues ${method} against ${UPSTREAM}` })
    }
  }

  return findings
}

function walk(dir: string, acc: string[] = []): string[] {
  for (const entry of readdirSync(dir)) {
    const full = join(dir, entry)
    if (statSync(full).isDirectory()) walk(full, acc)
    else if (entry === 'route.ts') acc.push(full)
  }
  return acc
}

function loadApiRoutes(): { file: string; source: string }[] {
  return walk(API_ROOT).map(abs => ({
    // POSIX-style repo-relative path so assertions read the same on any platform.
    file: `src/app/api/${abs.slice(API_ROOT.length + 1).split(sep).join('/')}`,
    source: readFileSync(abs, 'utf8'),
  }))
}

describe('delegation console is read-only (ADR-0230)', () => {
  it('no BFF route forwards a delegation mutation', () => {
    const findings = findDelegationMutations(loadApiRoutes())
    expect(findings, findings.map(f => `${f.file}: ${f.problem}`).join('\n')).toEqual([])
  })

  it('actually reaches the delegation routes it claims to scan', () => {
    // A guard whose glob silently matches nothing passes forever. Anchor it: the read routes
    // this PR ships must be in the scanned set and must mention the upstream.
    const scanned = loadApiRoutes()
    const delegationRoutes = scanned.filter(f => f.source.includes(UPSTREAM)).map(f => f.file)

    expect(delegationRoutes).toContain('src/app/api/delegations/[id]/route.ts')
    expect(delegationRoutes).toContain('src/app/api/delegations/approvals/[id]/route.ts')
    expect(delegationRoutes).toContain('src/app/api/delegations/party/[partyId]/route.ts')
    expect(delegationRoutes).toContain('src/app/api/delegations/check/route.ts')
  })

  it('the delegation read routes export no mutating HTTP handler', () => {
    const routes = loadApiRoutes().filter(f => f.file.startsWith('src/app/api/delegations/'))
    expect(routes.length).toBeGreaterThan(0)

    for (const { file, source } of routes) {
      const handlers = [...stripComments(source).matchAll(/export async function ([A-Z]+)\s*\(/g)].map(m => m[1])
      for (const h of handlers) {
        // POST is allowed only on the side-effect-free coverage probe.
        const allowed = h === 'GET' || (h === 'POST' && file === 'src/app/api/delegations/check/route.ts')
        expect(allowed, `${file} exports a ${h} handler`).toBe(true)
      }
    }
  })
})

describe('the guard itself fires (falsifiability)', () => {
  // Every bullet in this repo's CI-gate lore says the same thing: a check that has only ever
  // passed is unfalsified. These feed the detector the exact shapes it exists to reject.

  it('flags a direct suspend forward', () => {
    const findings = findDelegationMutations([{
      file: 'fake/suspend/route.ts',
      source: `
        const url = serverSvcUrl('delegation-service', 'delegation', 8126, \`/api/v1/delegations/\${id}/suspend\`)
        await fetch(url, { method: 'POST', body })
      `,
    }])
    // Two independent detectors fire here — the forbidden path AND the non-probe POST — and
    // that redundancy is deliberate: dropping either one still leaves the route flagged.
    expect(findings.length).toBeGreaterThanOrEqual(1)
    expect(findings.some(f => f.problem.includes('/suspend'))).toBe(true)
    expect(findings.some(f => f.problem.includes('POST'))).toBe(true)
  })

  it('flags a DELETE revoke even though its path looks like the detail read', () => {
    const findings = findDelegationMutations([{
      file: 'fake/revoke/route.ts',
      source: `
        const url = serverSvcUrl('delegation-service', 'delegation', 8126, \`/api/v1/delegations/\${id}\`)
        await fetch(url, { method: 'DELETE' })
      `,
    }])
    expect(findings).toHaveLength(1)
    expect(findings[0].problem).toContain('DELETE')
  })

  it('flags a reinstate forward', () => {
    const findings = findDelegationMutations([{
      file: 'fake/reinstate/route.ts',
      source: `
        await fetch(serverSvcUrl('delegation-service', 'delegation', 8126, '/api/v1/delegations/x/reinstate'), { method: 'POST' })
      `,
    }])
    expect(findings.length).toBeGreaterThanOrEqual(1)
  })

  it('does NOT flag the coverage probe — a POST that changes nothing', () => {
    const findings = findDelegationMutations([{
      file: 'fake/check/route.ts',
      source: `
        await fetch(serverSvcUrl('delegation-service', 'delegation', 8126, '/api/v1/delegations/check'), { method: 'POST' })
      `,
    }])
    expect(findings).toEqual([])
  })

  it('does NOT flag prose that merely names the forbidden routes', () => {
    // The precedence decision this repo insists on making explicitly: code-about-code.
    const findings = findDelegationMutations([{
      file: 'fake/commented/route.ts',
      source: `
        // We deliberately do not implement /api/v1/delegations/{id}/suspend or /reinstate here,
        // and never method: 'DELETE' against delegation-service — see ADR-0230.
        /* Nor /api/v1/delegations/x/suspend in a block comment. */
        await fetch(serverSvcUrl('delegation-service', 'delegation', 8126, '/api/v1/delegations/check'), { method: 'POST' })
      `,
    }])
    expect(findings).toEqual([])
  })
})
