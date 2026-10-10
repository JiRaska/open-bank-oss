// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// A Kubernetes/EKS/Loki version typed into UI source goes stale the day the infra moves (the
// cloud-architecture page said 1.35 while the cluster ran 1.36). Versions come from
// platform-versions.json via src/lib/platform-versions.ts, which is generated from the infra
// sources. This scan fails on a version literal sitting next to one of those product names.
import { readdirSync, readFileSync, statSync } from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'

const SRC = path.resolve(__dirname, '..')
const PRODUCT = '(?:k8s|EKS|Kubernetes|Loki|Postgres(?:QL)?|Kafka|Valkey|Redis|Apicurio|Strimzi)'
const VERSION = '\\d+\\.\\d{1,2}(?:\\.\\d+)?'
// product word then a x.y version on the same line, or the reverse.
const HARDCODED = new RegExp(
  `\\b${PRODUCT}\\b[^\\n]{0,40}?\\bv?${VERSION}\\b|\\bv?${VERSION}\\b[^\\n]{0,20}?\\b${PRODUCT}\\b`,
  'i',
)

function walk(dir: string, out: string[] = []): string[] {
  for (const name of readdirSync(dir)) {
    const p = path.join(dir, name)
    if (statSync(p).isDirectory()) walk(p, out)
    else if (/\.tsx?$/.test(name) && !p.includes(`${path.sep}test${path.sep}`)) out.push(p)
  }
  return out
}

// Frozen live counts: "18 pods", "DaemonSet (18 podů)", "2 uzly Ready", "3 pody Running".
const FROZEN_COUNT = /\b\d+\s+(?:pods?|podů|pody|uzl\w*|nodes?)\b(?:\s+(?:Ready|Running))?/i
// `process.env.X_VERSION ?? '1.2'` : an env override with a typed default is a hardcoded version.
const ENV_DEFAULT = /process\.env\.\w*VERSION\w*\s*(?:\?\?|\|\|)\s*['"`]\d/

function offenders(): string[] {
  const hits: string[] = []
  for (const file of walk(SRC)) {
    readFileSync(file, 'utf8').split('\n').forEach((line, i) => {
      if (/^\s*(\/\/|\*|\/\*)/.test(line)) return
      // 'AsyncAPI 3.0' is a spec version, not a platform version.
      const scan = line.replace(/AsyncAPI \d+\.\d+/gi, 'AsyncAPI')
      if (HARDCODED.test(scan) || ENV_DEFAULT.test(scan) || FROZEN_COUNT.test(scan)) hits.push(`${path.relative(SRC, file)}:${i + 1}: ${line.trim().slice(0, 140)}`)
    })
  }
  return hits
}

describe('no hardcoded platform versions in UI source', () => {
  it('flags the known stale literals (negative control)', () => {
    expect(HARDCODED.test("title: 'EKS control plane (1.35)'")).toBe(true)
    expect(HARDCODED.test("subtitle: 'k8s 1.35 · Karpenter'")).toBe(true)
    expect(HARDCODED.test("store: t('Loki 3.6 · S3', 'Loki 3.6 · S3')")).toBe(true)
    expect(HARDCODED.test("version: '16.4', // PostgreSQL 16.4")).toBe(true)
    expect(HARDCODED.test("managedBy: 'Strimzi 1.2.0 (in-cluster operator)'")).toBe(true)
    expect(ENV_DEFAULT.test("version: process.env.KAFKA_VERSION ?? '4.2.0',")).toBe(true)
    expect(ENV_DEFAULT.test("version: process.env.REDIS_VERSION ?? '7.4.9'")).toBe(true)
    expect(ENV_DEFAULT.test('const v = process.env.NODE_ENV ?? "x"')).toBe(false)
    expect(FROZEN_COUNT.test('DaemonSet (18 podů) sbírá logy')).toBe(true)
    expect(FROZEN_COUNT.test('3 pods Running in ns cert-manager')).toBe(true)
    expect(FROZEN_COUNT.test('Živě: ${n} podů Running')).toBe(false)
    expect(HARDCODED.test('const label = `EKS ${pv.kubernetesVersion}`')).toBe(false)
  })

  it('has no Kubernetes/EKS/Loki version literal in src', () => {
    expect(offenders()).toEqual([])
  })

  it('the lifecycle route has no default Kubernetes version or embedded table', () => {
    const route = readFileSync(path.join(SRC, 'app/api/finops/lifecycle/route.ts'), 'utf8')
    expect(route).not.toMatch(/KUBERNETES_VERSION\s*\?\?/)
    expect(route).not.toMatch(/EMBEDDED_LIFECYCLE/)
  })
})
