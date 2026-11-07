// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
//
// Pure derivation of the platform versions the admin UI displays (EKS minor, bootstrap node
// group shape, Loki chart + pinned app version, EKS support lifecycle). Every value is PARSED
// from the file that decides it; a value that cannot be parsed THROWS. There is deliberately no
// default anywhere in this file: a guessed version is exactly the defect this replaces.
import { readFileSync, readdirSync, statSync, existsSync } from 'node:fs'
import path from 'node:path'
import { parse, parseAllDocuments } from 'yaml'

export const SOURCES = {
  variables: 'openbank-infra/aws/envs/sandbox-substrate/variables.tf',
  main: 'openbank-infra/aws/envs/sandbox-substrate/main.tf',
  loki: 'openbank-infra/gitops/apps/loki.yaml',
  lifecycle: 'openbank-infra/aws/finops/eks-version-lifecycle.json',
  gitops: 'openbank-infra/gitops',
  kafka: 'openbank-infra/gitops/components/kafka/kafka.yaml',
  strimzi: 'openbank-infra/gitops/apps/strimzi-operator.yaml',
}

const strip = text => text.replace(/^\s*(#|\/\/).*$/gm, '')

function only(text, re, what, file) {
  const matches = [...strip(text).matchAll(re)]
  if (matches.length !== 1) {
    throw new Error(`${file}: expected exactly one ${what}, found ${matches.length}`)
  }
  return matches[0]
}

export function parseKubernetesVersion(variablesTf) {
  const m = only(
    variablesTf,
    /variable\s+"kubernetes_version"\s*\{[^}]*?default\s*=\s*"(\d+\.\d+)"/g,
    'kubernetes_version default',
    SOURCES.variables,
  )
  return m[1]
}

export function parseNodeGroup(mainTf) {
  const types = only(mainTf, /node_instance_types\s*=\s*\[\s*"([a-z0-9.]+)"\s*\]/g, 'node_instance_types', SOURCES.main)[1]
  const int = key => Number(only(mainTf, new RegExp(`${key}\\s*=\\s*(\\d+)`, 'g'), key, SOURCES.main)[1])
  return {
    instanceType: types,
    desiredSize: int('node_desired_size'),
    minSize: int('node_min_size'),
    maxSize: int('node_max_size'),
  }
}

export function parseLoki(lokiYaml) {
  const app = parse(lokiYaml)
  const src = app?.spec?.source
  const chartVersion = src?.targetRevision
  const appVersion = src?.helm?.valuesObject?.loki?.image?.tag
  if (src?.chart !== 'loki' || !/^\d+\.\d+\.\d+$/.test(String(chartVersion ?? ''))) {
    throw new Error(`${SOURCES.loki}: spec.source.targetRevision is not a loki chart semver`)
  }
  if (!/^\d+\.\d+\.\d+$/.test(String(appVersion ?? ''))) {
    throw new Error(`${SOURCES.loki}: loki.image.tag (pinned app version) is not a semver`)
  }
  return { chartVersion: String(chartVersion), appVersion: String(appVersion) }
}

export function parseLifecycle(json, kubernetesVersion) {
  const lifecycle = JSON.parse(json)
  if (!lifecycle?.versions || !lifecycle.versions[kubernetesVersion]) {
    throw new Error(`${SOURCES.lifecycle}: no lifecycle row for EKS ${kubernetesVersion}`)
  }
  if (!lifecycle._meta?.last_refreshed) throw new Error(`${SOURCES.lifecycle}: _meta.last_refreshed missing`)
  return lifecycle
}

// One read per file per process: four derivations walk the same ~400 manifests.
const textCache = new Map()
const readCached = file => {
  if (!textCache.has(file)) textCache.set(file, readFileSync(file, 'utf8'))
  return textCache.get(file)
}

function walkYaml(dir, out = []) {
  for (const name of readdirSync(dir)) {
    const p = path.join(dir, name)
    if (statSync(p).isDirectory()) walkYaml(p, out)
    else if (/\.ya?ml$/.test(name)) out.push(p)
  }
  return out
}

// Image pins found across GitOps manifests. `null` when the image is not pinned anywhere
// (rendered "unknown (not pinned in gitops)", never a default).
export function collectImagePins(repoRoot, re) {
  const pins = {}
  for (const file of walkYaml(path.join(repoRoot, SOURCES.gitops))) {
    const rel = path.relative(repoRoot, file)
    const seen = new Set()
    for (const m of readCached(file).matchAll(re)) seen.add(m[1])
    for (const v of seen) (pins[v] ??= []).push(rel)
  }
  const versions = Object.keys(pins)
  if (versions.length === 0) return null
  // The version most manifests agree on is the fleet version; stragglers stay visible.
  versions.sort((a, b) => pins[b].length - pins[a].length || (a < b ? 1 : -1))
  return {
    version: versions[0],
    source: pins[versions[0]][0],
    pinnedIn: pins[versions[0]].length,
    otherPins: Object.fromEntries(versions.slice(1).map(v => [v, pins[v]])),
  }
}

export function parseKafkaVersion(kafkaYaml) {
  const docs = kafkaYaml.split(/^---\s*$/m).map(d => parse(d)).filter(Boolean)
  const kafka = docs.find(d => d.kind === 'Kafka')
  const v = kafka?.spec?.kafka?.version
  if (v === undefined) return null
  if (!/^\d+\.\d+\.\d+$/.test(String(v))) throw new Error(`${SOURCES.kafka}: spec.kafka.version is not a semver`)
  return String(v)
}

export function parseStrimziChart(yamlText) {
  const src = parse(yamlText)?.spec?.source
  if (src?.chart !== 'strimzi-kafka-operator') return null
  const v = String(src.targetRevision ?? '')
  if (!/^\d+\.\d+\.\d+$/.test(v)) throw new Error(`${SOURCES.strimzi}: targetRevision is not a semver`)
  return v
}

// Terraform declaration types per inventory key. Include environments that declare them
// directly or reference their module; never claim evaluated instances or running resources.
const IAC_TYPES = {
  route53: ['aws_route53_zone'],
  cloudfront: ['aws_cloudfront_distribution'],
  acm: ['aws_acm_certificate'],
  alb: ['aws_lb', 'aws_alb'],
  cloudtrail: ['aws_cloudtrail'],
  awsConfig: ['aws_config_configuration_recorder'],
  objectLock: ['aws_s3_bucket_object_lock_configuration'],
  helmReleases: ['helm_release'],
}

function tfSources(dir, repoRoot) {
  return readdirSync(dir).filter(f => f.endsWith('.tf')).sort().map(f => ({
    sourceFile: path.relative(repoRoot, path.join(dir, f)),
    text: strip(readFileSync(path.join(dir, f), 'utf8')),
  }))
}

// Mask comments, strings and heredocs before structural brace matching. This is a
// declaration reader, not HCL evaluation; count/for_each instances remain unknown.
function hclMask(text) {
  return text.replace(/"(?:\\.|[^"\\])*"|\/\*[\s\S]*?\*\/|\/\/[^\n]*|#[^\n]*|<<-?(\w+)[^\n]*\n[\s\S]*?^\s*\1\s*$/gm,
    value => value.startsWith('"') ? `"${' '.repeat(value.length - 2)}"` : value.replace(/[^\n]/g, ' '))
}

function braceDepth(text) {
  return [...text].reduce((depth, char) => depth + (char === '{' ? 1 : char === '}' ? -1 : 0), 0)
}

function declarationBlocks(text) {
  const masked = hclMask(text)
  let balance = 0
  for (const char of masked) {
    balance += char === '{' ? 1 : char === '}' ? -1 : 0
    if (balance < 0) throw new Error('Unbalanced Terraform declaration braces')
  }
  if (balance !== 0) throw new Error('Unbalanced Terraform declaration braces')
  const blocks = []
  for (const match of masked.matchAll(/\b(module|resource)\s+"[^"\n]*"(?:\s+"[^"\n]*")?\s*\{/g)) {
    if (braceDepth(masked.slice(0, match.index)) !== 0) continue
    const start = match.index + match[0].length
    let end = start
    let depth = 1
    while (end < masked.length && depth) {
      if (masked[end] === '{') depth++
      if (masked[end] === '}') depth--
      end++
    }
    if (depth) throw new Error('Unclosed Terraform declaration block')
    const header = masked.slice(match.index, start)
    const labels = [...header.matchAll(/"[^"\n]*"/g)].map(item =>
      text.slice(match.index + item.index + 1, match.index + item.index + item[0].length - 1))
    blocks.push({ kind: match[1], labels, body: text.slice(start, end - 1) })
  }
  return blocks
}

function localModuleSource(body) {
  const masked = hclMask(body)
  const matches = [...masked.matchAll(/\bsource\s*=\s*"[^"\n]*"/g)]
    .filter(match => braceDepth(masked.slice(0, match.index)) === 0)
  if (matches.length !== 1) return null
  const match = matches[0]
  const literal = body.slice(match.index, match.index + match[0].length).match(/"([^"\n]*)"$/)?.[1]
  return literal && /^\.\.?\//.test(literal) && !literal.includes('${') ? literal : null
}

export function isRepositoryPath(repoRoot, candidate) {
  const relative = path.relative(path.resolve(repoRoot), path.resolve(candidate))
  return relative !== '..' && !relative.startsWith(`..${path.sep}`) && !path.isAbsolute(relative)
}

export function deriveIac(repoRoot) {
  const aws = path.join(repoRoot, 'openbank-infra', 'aws')
  const out = Object.fromEntries(Object.keys(IAC_TYPES).map(k => [k, []]))
  const helmReleaseDeclarations = []
  const moduleTraversalGaps = []
  for (const environment of readdirSync(path.join(aws, 'envs')).sort()) {
    const rootDir = path.join(aws, 'envs', environment)
    if (!statSync(rootDir).isDirectory()) continue
    const found = new Set()
    const visit = (dir, modulePath, ancestors) => {
      if (ancestors.has(dir)) {
        moduleTraversalGaps.push({ environment, modulePath, reason: 'cycle' })
        return
      }
      if (!isRepositoryPath(repoRoot, dir) || !existsSync(dir) || !statSync(dir).isDirectory()) {
        moduleTraversalGaps.push({ environment, modulePath, reason: 'unresolved-local-module' })
        return
      }
      const next = new Set([...ancestors, dir])
      for (const source of tfSources(dir, repoRoot)) {
        for (const block of declarationBlocks(source.text)) {
          if (block.kind === 'resource') {
            const [type, name] = block.labels
            for (const [key, types] of Object.entries(IAC_TYPES)) if (types.includes(type)) found.add(key)
            if (type === 'helm_release') {
              helmReleaseDeclarations.push({
                environment, sourceFile: source.sourceFile,
                address: `${modulePath ? `${modulePath}.` : ''}helm_release.${name}`,
                instanceExpansion: 'unknown',
              })
            }
          } else {
            const callPath = `${modulePath ? `${modulePath}.` : ''}module.${block.labels[0]}`
            const relative = localModuleSource(block.body)
            if (relative) visit(path.resolve(dir, relative), callPath, next)
            else moduleTraversalGaps.push({ environment, modulePath: callPath, reason: 'external-or-dynamic-source' })
          }
        }
      }
    }
    visit(path.resolve(rootDir), '', new Set())
    for (const key of found) out[key].push(environment)
  }
  return { ...out, helmReleaseDeclarations, moduleTraversalGaps }
}

function applicationSources(application) {
  const spec = application?.spec ?? {}
  const sources = [...(spec.source ? [spec.source] : []), ...(Array.isArray(spec.sources) ? spec.sources : [])]
  return (sources.length ? sources : [null]).map(source => {
    if (typeof source?.chart === 'string' && source.chart && !source.path) return { kind: 'helm-chart', chart: source.chart }
    if (typeof source?.path === 'string' && source.path && !source.chart
      && !path.posix.isAbsolute(source.path) && !source.path.split('/').includes('..')) {
      return { kind: 'manifest-path', path: source.path }
    }
    return { kind: 'unknown' }
  })
}

export function deriveGitopsInventory(repoRoot) {
  const gitops = path.join(repoRoot, SOURCES.gitops)
  const names = dir => readdirSync(path.join(gitops, dir))
  const appFiles = names('apps').filter(f => /\.ya?ml$/.test(f)).sort()
  const apps = appFiles.map(f => f.replace(/\.ya?ml$/, '')).sort()
  const applications = appFiles.flatMap(file => {
    const sourceFile = path.posix.join(SOURCES.gitops, 'apps', file)
    const documents = parseAllDocuments(readCached(path.join(repoRoot, sourceFile)))
    return (documents.length ? documents : [null]).map(document => {
      if (document?.errors.length) throw new Error(`${sourceFile}: ${document.errors[0].message}`)
      const application = document?.toJSON()
      const known = application?.kind === 'Application'
      return {
        kind: known ? 'application' : 'unknown',
        name: known && typeof application.metadata?.name === 'string' ? application.metadata.name : null,
        namespace: known && typeof application.spec?.destination?.namespace === 'string' ? application.spec.destination.namespace : null,
        sourceFile,
        sources: known ? applicationSources(application) : [{ kind: 'unknown' }],
        workloadMapping: 'unknown',
      }
    })
  })
  const components = names('components').filter(f => statSync(path.join(gitops, 'components', f)).isDirectory()).sort()
  if (apps.length === 0 || components.length === 0) throw new Error(`${SOURCES.gitops}: no apps/components found`)
  const files = walkYaml(gitops).map(readCached)
  const has = re => files.some(t => re.test(t))
  return {
    apps,
    applications,
    components,
    clickhouse: has(/image:\s*\S*clickhouse\/clickhouse-server/),
    istio: has(/(?:image:\s*\S*istio\/|chart:\s*istiod)/),
    cilium: has(/(?:image:\s*\S*cilium\/cilium|chart:\s*cilium\s*$)/m),
  }
}

export function derivePlatformVersions(repoRoot) {
  const read = rel => readFileSync(path.join(repoRoot, rel), 'utf8')
  const kubernetesVersion = parseKubernetesVersion(read(SOURCES.variables))
  const nodeGroup = parseNodeGroup(read(SOURCES.main))
  const loki = parseLoki(read(SOURCES.loki))
  const lifecycle = parseLifecycle(read(SOURCES.lifecycle), kubernetesVersion)
  const kafkaVersion = parseKafkaVersion(read(SOURCES.kafka))
  const strimziVersion = parseStrimziChart(read(SOURCES.strimzi))
  const components = {
    postgres: collectImagePins(repoRoot, /ghcr\.io\/cloudnative-pg\/postgresql:(\d+\.\d+)\b/g),
    valkey: collectImagePins(repoRoot, /valkey\/valkey:(\d+\.\d+\.\d+)/g),
    apicurio: collectImagePins(repoRoot, /apicurio\/apicurio-registry[a-z-]*:(\d+\.\d+\.\d+)/g),
    kafka: kafkaVersion ? { version: kafkaVersion, source: SOURCES.kafka } : null,
    strimziOperator: strimziVersion ? { version: strimziVersion, source: SOURCES.strimzi } : null,
  }
  return {
    schema: 'openbank.platform-versions/v1',
    kubernetesVersion,
    nodeGroup,
    loki,
    eksLifecycle: lifecycle,
    inventory: { gitops: deriveGitopsInventory(repoRoot), iac: deriveIac(repoRoot) },
    components,
    sources: {
      kubernetesVersion: SOURCES.variables,
      nodeGroup: SOURCES.main,
      loki: SOURCES.loki,
      eksLifecycle: SOURCES.lifecycle,
    },
  }
}
