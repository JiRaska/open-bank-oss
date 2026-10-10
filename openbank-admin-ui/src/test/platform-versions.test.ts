// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Guards the derived platform versions the UI displays. The parser is run against the REAL infra
// files and compared with an INDEPENDENT read of the same source, so a parser that returns
// empty/garbage (or a default) fails here instead of shipping a plausible-looking stale version.
import { readFileSync } from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'
import {
  SOURCES,
  derivePlatformVersions,
  parseKubernetesVersion,
  parseLoki,
  parseNodeGroup,
} from '../../scripts/lib/platform-versions.mjs'

const repo = path.resolve(__dirname, '..', '..', '..')
const read = (rel: string) => readFileSync(path.join(repo, rel), 'utf8')

describe('platform versions derivation', () => {
  it('EKS version equals the kubernetes_version default in variables.tf', () => {
    const independent = /variable "kubernetes_version"[\s\S]*?default\s*=\s*"([^"]+)"/.exec(read(SOURCES.variables))?.[1]
    expect(independent).toMatch(/^\d+\.\d+$/)
    const derived = derivePlatformVersions(repo)
    expect(derived.kubernetesVersion).toBe(independent)
    expect(derived.eksLifecycle.versions[derived.kubernetesVersion]).toBeDefined()
  })

  it('node group and Loki are non-empty and match their sources', () => {
    const d = derivePlatformVersions(repo)
    expect(d.nodeGroup.instanceType).toMatch(/^[a-z0-9]+\.[a-z0-9]+$/)
    expect(d.nodeGroup.minSize).toBeLessThanOrEqual(d.nodeGroup.desiredSize)
    expect(d.nodeGroup.desiredSize).toBeLessThanOrEqual(d.nodeGroup.maxSize)
    expect(read(SOURCES.main)).toContain(`"${d.nodeGroup.instanceType}"`)
    expect(read(SOURCES.loki)).toContain(`targetRevision: ${d.loki.chartVersion}`)
    expect(read(SOURCES.loki)).toContain(`tag: "${d.loki.appVersion}"`)
  })

  it('component pins match the gitops manifests they cite', () => {
    const c = derivePlatformVersions(repo).components
    expect(read(c.postgres!.source)).toContain(`cloudnative-pg/postgresql:${c.postgres!.version}`)
    expect(read(c.valkey!.source)).toContain(`valkey/valkey:${c.valkey!.version}`)
    expect(read(c.apicurio!.source)).toContain(`:${c.apicurio!.version}`)
    expect(read(c.kafka!.source)).toMatch(new RegExp(`version:\\s*${c.kafka!.version.replace(/\./g, '\\.')}`))
    expect(read(c.strimziOperator!.source)).toContain(`targetRevision: ${c.strimziOperator!.version}`)
  })

  it('throws on unparseable input instead of defaulting', () => {
    expect(() => parseKubernetesVersion('variable "other" {}')).toThrow()
    expect(() => parseKubernetesVersion('variable "kubernetes_version" {\n type = string\n}')).toThrow()
    expect(() => parseNodeGroup('node_min_size = 1')).toThrow()
    expect(() => parseLoki('spec:\n  source:\n    chart: loki\n')).toThrow()
  })
})
