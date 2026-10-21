// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Guards the derived platform versions the UI displays. The parser is run against the REAL infra
// files and compared with an INDEPENDENT read of the same source, so a parser that returns
// empty/garbage (or a default) fails here instead of shipping a plausible-looking stale version.
import { mkdtempSync, mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import path from 'node:path'
import { describe, expect, it } from 'vitest'
import {
  SOURCES,
  derivePlatformVersions,
  deriveGitopsInventory,
  deriveIac,
  parseKubernetesVersion,
  parseLoki,
  parseNodeGroup,
} from '../../scripts/lib/platform-versions.mjs'

const repo = path.resolve(__dirname, '..', '..', '..')
const read = (rel: string) => readFileSync(path.join(repo, rel), 'utf8')

describe('platform versions derivation', { timeout: 30_000 }, () => {
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
    expect(read(c.kafka!.source).split('\n').some(line => line.trim() === `version: ${c.kafka!.version}`)).toBe(true)
    expect(read(c.strimziOperator!.source)).toContain(`targetRevision: ${c.strimziOperator!.version}`)
  })

  it('throws on unparseable input instead of defaulting', () => {
    expect(() => parseKubernetesVersion('variable "other" {}')).toThrow()
    expect(() => parseKubernetesVersion('variable "kubernetes_version" {\n type = string\n}')).toThrow()
    expect(() => parseNodeGroup('node_min_size = 1')).toThrow()
    expect(() => parseLoki('spec:\n  source:\n    chart: loki\n')).toThrow()
  })
})

function inventoryFixture(run: (root: string, write: (file: string, text: string) => void) => void) {
  const root = mkdtempSync(path.join(tmpdir(), 'platform-inventory-'))
  const write = (file: string, text: string) => {
    mkdirSync(path.dirname(path.join(root, file)), { recursive: true })
    writeFileSync(path.join(root, file), text)
  }
  try { run(root, write) } finally { rmSync(root, { recursive: true, force: true }) }
}

describe('declared platform inventory', () => {
  it('preserves all legacy arrays and records chart, path, multi-source and unmapped applications safely', () => {
    inventoryFixture((root, write) => {
      const prefix = 'openbank-infra/gitops/'
      write(`${prefix}components/config/settings.yaml`, 'kind: ConfigMap\n')
      const application = (name: string, source: string) => `kind: Application\nmetadata:\n  name: ${name}\nspec:\n  destination:\n    namespace: platform-test\n    server: https://do-not-copy.invalid\n${source}`
      write(`${prefix}apps/chart.yaml`, application('chart-controller', '  source:\n    chart: sample-chart\n    repoURL: https://do-not-copy.invalid\n    targetRevision: secret-do-not-copy\n'))
      write(`${prefix}apps/config.yaml`, application('config-only', '  source:\n    path: openbank-infra/gitops/components/config\n'))
      write(`${prefix}apps/multi.yaml`, application('multiple', '  sources:\n    - chart: sample-controller\n    - path: openbank-infra/gitops/components/config\n    - ref: values\n      repoURL: https://do-not-copy.invalid\n'))
      write(`${prefix}apps/unknown.yaml`, application('unknown-source', ''))
      write(`${prefix}apps/non-application.yaml`, 'kind: ConfigMap\nmetadata:\n  name: not-an-app\n')
      write(`${prefix}apps/empty.yaml`, '')
      write(`${prefix}apps/multidoc.yaml`, `${application('first', '')}---\n${application('second', '')}`)
      const result = deriveGitopsInventory(root)
      expect(result.apps).toEqual(['chart', 'config', 'empty', 'multi', 'multidoc', 'non-application', 'unknown'])
      expect(result.components).toEqual(['config'])
      expect(result.applications).toHaveLength(8)
      expect(result.applications.filter((a: { sourceFile: string }) => a.sourceFile.endsWith('/empty.yaml'))).toHaveLength(1)
      expect(result.applications.filter((a: { sourceFile: string }) => a.sourceFile.endsWith('/multidoc.yaml')).map((a: { name: string }) => a.name)).toEqual(['first', 'second'])
      expect(result.applications.find((a: { name: string }) => a.name === 'chart-controller').sources).toEqual([{ kind: 'helm-chart', chart: 'sample-chart' }])
      const config = result.applications.find((a: { name: string }) => a.name === 'config-only')
      expect(config.sources).toEqual([{ kind: 'manifest-path', path: `${prefix}components/config` }])
      expect(config.workloadMapping).toBe('unknown')
      expect(result.applications.find((a: { name: string }) => a.name === 'multiple').sources).toEqual([
        { kind: 'helm-chart', chart: 'sample-controller' }, { kind: 'manifest-path', path: `${prefix}components/config` }, { kind: 'unknown' },
      ])
      expect(result.applications.find((a: { name: string }) => a.name === 'unknown-source').sources).toEqual([{ kind: 'unknown' }])
      expect(result.applications.find((a: { kind: string }) => a.kind === 'unknown').name).toBeNull()
      for (const item of result.applications) {
        expect(item.workloadMapping).toBe('unknown')
        expect(item.sourceFile).toMatch(/^openbank-infra\/gitops\/apps\//)
      }
      expect(JSON.stringify(result)).not.toContain('do-not-copy')
    })
  })

  it('fails on malformed YAML rather than silently losing a declared application', () => {
    inventoryFixture((root, write) => {
      write('openbank-infra/gitops/components/config/settings.yaml', 'kind: ConfigMap')
      write('openbank-infra/gitops/apps/broken.yaml', 'kind: Application\nspec: [broken')
      expect(() => deriveGitopsInventory(root)).toThrow('broken.yaml')
    })
  })

  it('includes only Helm declaration addresses from environments and referenced modules, never values or unused modules', () => {
    inventoryFixture((root, write) => {
      const prefix = 'openbank-infra/aws/'
      write(`${prefix}envs/sandbox/main.tf`, `resource "helm_release" "cnpg" {\n  version = "do-not-copy"\n}\nmodule "used" {\n  source = "../../modules/used"\n}\nresource "aws_route53_zone" "zone" {}\n`)
      write(`${prefix}modules/used/main.tf`, 'resource "helm_release" "operator" {}\n')
      write(`${prefix}modules/unused/main.tf`, 'resource "helm_release" "unused" {}\n')
      const result = deriveIac(root)
      expect(result.route53).toEqual(['sandbox'])
      expect(result.helmReleases).toEqual(['sandbox'])
      expect(result.helmReleaseDeclarations).toEqual([
        { environment: 'sandbox', sourceFile: `${prefix}envs/sandbox/main.tf`, address: 'helm_release.cnpg', instanceExpansion: 'unknown' },
        { environment: 'sandbox', sourceFile: `${prefix}modules/used/main.tf`, address: 'module.used.helm_release.operator', instanceExpansion: 'unknown' },
      ])
      expect(JSON.stringify(result)).not.toContain('do-not-copy')
      expect(JSON.stringify(result)).not.toContain('unused')
    })
  })
})

it('preserves repeated module call identities, nested calls and bounded cycle gaps without evaluating instances', () => {
  inventoryFixture((root, write) => {
    const prefix = 'openbank-infra/aws/'
    write(`${prefix}envs/sandbox/main.tf`, 'module "first" { config = { source = "../not-a-module" }\n source = "../../modules/shared" }\nmodule "second" { source = "../../modules/shared" }\n')
    write(`${prefix}modules/shared/main.tf`, '/* resource "helm_release" "comment" {} */\nresource "helm_release" "operator" { for_each = {}\n values = [<<-VALUES\nresource "helm_release" "heredoc" {}\nVALUES\n] }\nmodule "nested" { source = "../nested" }\n')
    write(`${prefix}modules/nested/main.tf`, 'resource "helm_release" "helper" { count = 0 }\nmodule "back" { source = "../shared" }\n')
    const result = deriveIac(root)
    expect(result.helmReleaseDeclarations.map((item: { address: string }) => item.address)).toEqual([
      'module.first.helm_release.operator', 'module.first.module.nested.helm_release.helper',
      'module.second.helm_release.operator', 'module.second.module.nested.helm_release.helper',
    ])
    expect(result.helmReleaseDeclarations.every((item: { instanceExpansion: string }) => item.instanceExpansion === 'unknown')).toBe(true)
    expect(result.moduleTraversalGaps).toEqual([
      { environment: 'sandbox', modulePath: 'module.first.module.nested.module.back', reason: 'cycle' },
      { environment: 'sandbox', modulePath: 'module.second.module.nested.module.back', reason: 'cycle' },
    ])
  })
})

it('rejects stray or unclosed Terraform braces instead of silently omitting declarations', () => {
  for (const malformed of ['}\nresource "helm_release" "hidden" {}', '{\nresource "helm_release" "hidden" {}']) {
    inventoryFixture((root, write) => {
      write('openbank-infra/aws/envs/sandbox/main.tf', malformed)
      expect(() => deriveIac(root)).toThrow('Unbalanced Terraform declaration braces')
    })
  }
})


it('ignores quoted comments between resource declaration tokens', () => {
  inventoryFixture((root, write) => {
    write('openbank-infra/aws/envs/sandbox/main.tf', 'resource /* "comment_label" */ "helm_release" "actual" {}\n')
    expect(deriveIac(root).helmReleaseDeclarations.map((item: { address: string }) => item.address))
      .toEqual(['helm_release.actual'])
  })
})

it('preserves module identity when a quoted comment precedes its label', () => {
  inventoryFixture((root, write) => {
    write('openbank-infra/aws/envs/sandbox/main.tf', 'module /* "comment_label" */ "actual" { source = "../../modules/shared" }\n')
    write('openbank-infra/aws/modules/shared/main.tf', 'resource "helm_release" "operator" {}\n')
    expect(deriveIac(root).helmReleaseDeclarations.map((item: { address: string }) => item.address))
      .toEqual(['module.actual.helm_release.operator'])
  })
})
