// SPDX-License-Identifier: Apache-2.0
// Reviewed repository content, published independently from the Admin UI image.
import { parse as parseYaml } from 'yaml'
import { createEvidenceSnapshotReader, type EvidenceSnapshotReaderOptions } from './publishedSnapshot.ts'
import { parseBcpPolicy } from '../bcp/continuityModel.ts'
import { BpmnProcessSchema } from '../docs/bpmn/schema.ts'

export interface AdminUiContentSnapshot {
  schemaVersion: 1
  sourceSha: string
  collectedAt: string
  content: {
    catalog: Record<string, unknown>
    serviceGraph: Record<string, unknown>
    governance: Record<string, unknown>
    securityGraph: Record<string, unknown>
    /** Optional for compatibility with schemaVersion 1 snapshots published before this field. */
    clusterTopology?: Record<string, unknown>
    bcpPolicyMarkdown: string
    complianceControlsYaml: string
    bpmnManifests: Array<{ slug: string; yaml: string }>
    libsDocs: Array<{ path: string; markdown: string }>
  }
}

export const ADMIN_UI_CONTENT_MAX_BYTES = 1024 * 1024
const SOURCE_SHA = /^[0-9a-f]{40}$/
const ISO_UTC = /^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d\.\d{3}Z$/
const MAX_FILE_BYTES = 256 * 1024
const CONTENT_KEYS = [
  'catalog', 'serviceGraph', 'governance', 'securityGraph', 'bcpPolicyMarkdown',
  'complianceControlsYaml', 'bpmnManifests', 'libsDocs',
] as const
const CONTENT_KEYS_WITH_CLUSTER = [...CONTENT_KEYS, 'clusterTopology'] as const

function record(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
}

function hasExactKeys(value: Record<string, unknown>, keys: readonly string[]): boolean {
  return keys.every(key => Object.hasOwn(value, key)) && Object.keys(value).every(key => keys.includes(key))
}

function validTimestamp(value: unknown, nowMs: number): value is string {
  return typeof value === 'string' && ISO_UTC.test(value)
    && Number.isFinite(Date.parse(value)) && Date.parse(value) <= nowMs + 5 * 60_000
}

function validEmbeddedTimestamp(value: unknown): boolean {
  return value === null || typeof value === 'string'
    && Number.isFinite(Date.parse(value)) && Date.parse(value) <= Date.now() + 5 * 60_000
}

function nonEmptyString(value: unknown): value is string {
  return typeof value === 'string' && value.trim().length > 0
}

function validDetailRoute(value: unknown): boolean {
  if (typeof value !== 'string' || /[\\\u0000-\u001f]/.test(value)) return false
  if (value.startsWith('#')) return /^#[A-Za-z][A-Za-z0-9_-]*$/.test(value)
  return value.startsWith('/') && !value.startsWith('//')
}

function nonNegativeInt(value: unknown): value is number {
  return Number.isSafeInteger(value) && (value as number) >= 0
}

function hasBaseGeneratedShape(value: unknown, schema: string): value is Record<string, unknown> {
  return record(value) && value.schema === schema && nonEmptyString(value.source)
    && validEmbeddedTimestamp(value.collectedAt) && record(value.totals)
}

function validCatalog(value: unknown): value is Record<string, unknown> {
  if (!hasBaseGeneratedShape(value, 'openbank.catalog/v1') || !Array.isArray(value.services)
    || value.services.length === 0 || value.services.length > 500) return false
  const totals = value.totals as Record<string, unknown>
  if (!['modules', 'services', 'withOpenapi', 'moneyPath', 'withGaps'].every(key => nonNegativeInt(totals[key]))) return false
  if (totals.modules !== value.services.length) return false
  const names = new Set<string>()
  let serviceCount = 0
  let withOpenapi = 0
  let moneyPath = 0
  let withGaps = 0
  for (const row of value.services) {
    if (!record(row) || !nonEmptyString(row.name) || !nonEmptyString(row.short) || names.has(row.name)
      || !['ui', 'library', 'service', 'component'].includes(String(row.kind))
      || typeof row.runnable !== 'boolean'
      || !(row.port === null || (Number.isInteger(row.port) && (row.port as number) >= 1 && (row.port as number) <= 65535))
      || !(row.mgmtPort === null || (Number.isInteger(row.mgmtPort) && (row.mgmtPort as number) >= 1 && (row.mgmtPort as number) <= 65535))
      || !(row.releaseVersion === null || nonEmptyString(row.releaseVersion))
      || !(row.apiVersion === null || nonEmptyString(row.apiVersion))
      || !(row.apiTitle === null || nonEmptyString(row.apiTitle))
      || typeof row.hasOpenapi !== 'boolean' || typeof row.moneyPath !== 'boolean'
      || !Array.isArray(row.gaps) || !row.gaps.every(nonEmptyString)) return false
    names.add(row.name)
    if (row.kind === 'service') serviceCount++
    if (row.hasOpenapi) withOpenapi++
    if (row.moneyPath) moneyPath++
    if (row.gaps.length > 0) withGaps++
  }
  return totals.services === serviceCount && totals.withOpenapi === withOpenapi
    && totals.moneyPath === moneyPath && totals.withGaps === withGaps
}

function validServiceGraph(value: unknown): value is Record<string, unknown> {
  if (!hasBaseGeneratedShape(value, 'openbank.service-graph/v1') || !Array.isArray(value.nodes)
    || value.nodes.length === 0 || value.nodes.length > 1_000 || !Array.isArray(value.edges)
    || value.edges.length > 20_000 || !Array.isArray(value.danglingTopics)
    || value.danglingTopics.length > 20_000) return false
  const totals = value.totals as Record<string, unknown>
  const nodes = value.nodes as unknown[]
  const edges = value.edges as unknown[]
  const danglingTopics = value.danglingTopics as unknown[]
  const countKeys = ['nodes', 'restEdges', 'kafkaEdges', 'danglingTopics', 'infraNodes', 'externalNodes', 'infraEdges', 'externalEdges']
  if (!countKeys.every(key => nonNegativeInt(totals[key])) || totals.nodes !== nodes.length
    || totals.danglingTopics !== danglingTopics.length
    || (totals.restEdges as number) + (totals.kafkaEdges as number) !== edges.length) return false
  const names = new Set<string>()
  for (const node of nodes) {
    if (!record(node) || !nonEmptyString(node.name) || names.has(node.name)
      || !nonEmptyString(node.short) || !nonNegativeInt(node.dependsOn) || !nonNegativeInt(node.dependedOnBy)) return false
    names.add(node.name)
  }
  for (const edge of edges) {
    if (!record(edge) || !nonEmptyString(edge.from) || !nonEmptyString(edge.to) || !nonEmptyString(edge.via)
      || !['rest', 'kafka'].includes(String(edge.type)) || !names.has(edge.from) || !names.has(edge.to)) return false
  }
  for (const topic of danglingTopics) {
    if (!record(topic) || !nonEmptyString(topic.topic) || !Array.isArray(topic.producedBy) || !Array.isArray(topic.consumedBy)
      || !topic.producedBy.every(nonEmptyString) || !topic.consumedBy.every(nonEmptyString)
      || (topic.producedBy.length === 0) === (topic.consumedBy.length === 0)) return false
  }
  const optionalArrays = ['infraNodes', 'externalNodes', 'infraEdges', 'externalEdges']
  for (const key of optionalArrays) {
    if (value[key] !== undefined && !Array.isArray(value[key])) return false
    if (Array.isArray(value[key]) && value[key].length > 20_000) return false
  }
  const infraNodes = (value.infraNodes ?? []) as unknown[]
  const externalNodes = (value.externalNodes ?? []) as unknown[]
  const infraEdges = (value.infraEdges ?? []) as unknown[]
  const externalEdges = (value.externalEdges ?? []) as unknown[]
  if (totals.infraNodes !== infraNodes.length || totals.externalNodes !== externalNodes.length
    || totals.infraEdges !== infraEdges.length || totals.externalEdges !== externalEdges.length) return false
  for (const node of infraNodes) {
    if (!record(node) || !/^infra:[a-z0-9-]+$/.test(String(node.id)) || node.kind !== 'infra'
      || !nonEmptyString(node.tech) || !nonEmptyString(node.label)) return false
  }
  for (const node of externalNodes) {
    if (!record(node) || !/^ext:[a-z0-9-]+$/.test(String(node.id)) || node.kind !== 'external'
      || !nonEmptyString(node.vendor) || !nonEmptyString(node.label)) return false
  }
  for (const edge of infraEdges) {
    if (!record(edge) || !nonEmptyString(edge.from) || !names.has(edge.from) || typeof edge.to !== 'string' || !/^infra:[a-z0-9-]+$/.test(edge.to)
      || !['db', 'broker', 'auth', 'authz'].includes(String(edge.type))) return false
  }
  for (const edge of externalEdges) {
    if (!record(edge) || !nonEmptyString(edge.from) || !names.has(edge.from) || typeof edge.to !== 'string' || !/^ext:[a-z0-9-]+$/.test(edge.to)
      || !['push', 'webhook', 'registry', 'api', 'llm'].includes(String(edge.type)) || typeof edge.enabled !== 'boolean') return false
  }
  return true
}

function validGovernance(value: unknown): value is Record<string, unknown> {
  if (!hasBaseGeneratedShape(value, 'openbank.governance/v1') || !Array.isArray(value.services)
    || value.services.length === 0 || value.services.length > 500 || !Array.isArray(value.gaps)
    || value.gaps.length > 20_000 || !value.gaps.every(nonEmptyString)) return false
  const totals = value.totals as Record<string, unknown>
  if (!['modules', 'withLineage', 'evidenceExported', 'unverifiedDatabaseNames', 'withGaps'].every(key => nonNegativeInt(totals[key]))
    || totals.modules !== value.services.length || totals.withGaps !== value.gaps.length) return false
  const names = new Set<string>()
  for (const row of value.services) {
    if (!record(row) || !nonEmptyString(row.serviceName) || names.has(row.serviceName)
      || !['dataDomain', 'primaryDatastore', 'databaseName', 'dataLineageRole', 'flywayDeclaredVersion']
        .every(key => row[key] === undefined || row[key] === null || typeof row[key] === 'string')
      || ![row.ownsNoDatabase, row.evidenceExported].every(item => item === undefined || typeof item === 'boolean')
      || !(row.databaseNameEvidence === null || ['derived', 'declared-only'].includes(String(row.databaseNameEvidence)))) return false
    if (row.lineage !== undefined && !record(row.lineage)) return false
    if (row.databaseLineage !== undefined && !record(row.databaseLineage)) return false
    names.add(row.serviceName)
  }
  const services = value.services as unknown[]
  return totals.withLineage === services.filter(row => record(row) && Boolean(row.lineage)).length
    && totals.evidenceExported === services.filter(row => record(row) && row.evidenceExported === true).length
    && totals.unverifiedDatabaseNames === services.filter(row => record(row) && row.databaseNameEvidence === 'declared-only').length
}

function validSecurityGraph(value: unknown): value is Record<string, unknown> {
  if (!record(value) || value.schema !== 'openbank.security-posture/v1' || !nonEmptyString(value.source)
    || !validEmbeddedTimestamp(value.collectedAt) || typeof value.available !== 'boolean'
    || !record(value.istio) || !record(value.network) || !record(value.supplyChain)) return false
  const istio = value.istio
  if (typeof istio.available !== 'boolean' || typeof istio.deployed !== 'boolean' || !nonEmptyString(istio.note)) return false
  const network = value.network
  if (typeof network.available !== 'boolean') return false
  if (network.available) {
    if (typeof network.defaultDeny !== 'boolean' || typeof network.internetEgress !== 'string'
      || !['open', 'opt-in'].includes(network.internetEgress)
      || !record(network.coverage) || !nonNegativeInt(network.coverage.total) || !nonNegativeInt(network.coverage.covered)
      || network.coverage.covered > network.coverage.total || !Array.isArray(network.coverage.gaps)
      || !network.coverage.gaps.every(gap => record(gap) && nonEmptyString(gap.namespace) && nonEmptyString(gap.service))
      || !Array.isArray(network.egressTargets) || !Array.isArray(network.ingressRules) || !Array.isArray(network.egressRestrictedApps)
      || !network.egressRestrictedApps.every(nonEmptyString)) return false
    for (const target of network.egressTargets) {
      if (!record(target) || !nonEmptyString(target.target) || !Array.isArray(target.ports)
        || !target.ports.every(port => typeof port === 'string' || nonNegativeInt(port))) return false
    }
    for (const rule of network.ingressRules) {
      if (!record(rule) || !(rule.policy === null || nonEmptyString(rule.policy)) || !nonEmptyString(rule.namespace)
        || !Array.isArray(rule.ports) || !rule.ports.every(port => typeof port === 'string' || nonNegativeInt(port))) return false
    }
  }
  const supply = value.supplyChain
  if (typeof supply.available !== 'boolean') return false
  if (supply.available && (supply.engine !== 'Kyverno' || !['Enforce', 'Audit', 'unknown'].includes(String(supply.mode))
    || typeof supply.enforced !== 'boolean' || !(supply.policy === null || nonEmptyString(supply.policy))
    || !(supply.imagePattern === null || nonEmptyString(supply.imagePattern))
    || !(supply.rekor === null || nonEmptyString(supply.rekor)))) return false
  return value.available === Boolean(network.available || supply.available)
}

function validClusterTopology(value: unknown, nowMs = Date.now()): value is Record<string, unknown> {
  if (!record(value) || value.schema !== 'openbank.cluster-topology/v1' || !nonEmptyString(value.source)
    || !(value.generatedAt === null || (typeof value.generatedAt === 'string'
      && Number.isFinite(Date.parse(value.generatedAt)) && Date.parse(value.generatedAt) <= nowMs + 5 * 60_000))
    || !record(value.counts) || !Array.isArray(value.groups) || value.groups.length === 0 || value.groups.length > 64
    || !Array.isArray(value.namespaces) || value.namespaces.length === 0 || value.namespaces.length > 500
    || !Array.isArray(value.securityLayers) || value.securityLayers.length === 0 || value.securityLayers.length > 64
    || !record(value.imageAnatomy) || !Array.isArray(value.imageAnatomy.steps) || value.imageAnatomy.steps.length === 0
    || value.imageAnatomy.steps.length > 64 || !Array.isArray(value.planVsReality) || value.planVsReality.length > 128) return false
  const countKeys = ['namespaces', 'networkPolicies', 'externalSecrets', 'clusterPolicies']
  const counts = value.counts as Record<string, unknown>
  if (!countKeys.every(key => nonNegativeInt(counts[key]))) return false
  const optionalImageString = (item: unknown) => item === null || nonEmptyString(item)
  if (!(value.imageAnatomy.multiStage === null || value.imageAnatomy.multiStage === undefined || typeof value.imageAnatomy.multiStage === 'boolean')
    || !(value.imageAnatomy.buildBase === undefined || optionalImageString(value.imageAnatomy.buildBase))
    || !(value.imageAnatomy.runtimeBase === undefined || optionalImageString(value.imageAnatomy.runtimeBase))) return false
  const groupIds = new Set<string>()
  for (const group of value.groups) {
    if (!record(group) || !nonEmptyString(group.id) || groupIds.has(group.id)
      || !nonEmptyString(group.label) || !nonEmptyString(group.labelEn) || !nonEmptyString(group.color)
      || !nonEmptyString(group.icon) || !nonEmptyString(group.blurb)) return false
    groupIds.add(group.id)
  }
  const namespaceNames = new Set<string>()
  for (const namespace of value.namespaces) {
    if (!record(namespace) || !nonEmptyString(namespace.name) || namespaceNames.has(namespace.name)
      || typeof namespace.group !== 'string' || !groupIds.has(namespace.group) || !nonEmptyString(namespace.role)
      || typeof namespace.declared !== 'boolean') return false
    namespaceNames.add(namespace.name)
  }
  const validStatus = (status: unknown) => typeof status === 'string' && ['live', 'partial', 'planned'].includes(status)
  const layerIds = new Set<string>()
  for (const layer of value.securityLayers) {
    if (!record(layer) || !nonEmptyString(layer.id) || !nonEmptyString(layer.label) || !validStatus(layer.status)
      || layerIds.has(layer.id) || !nonEmptyString(layer.icon) || !nonEmptyString(layer.analogy) || !nonEmptyString(layer.summary)
      || !Array.isArray(layer.controls) || !layer.controls.every(nonEmptyString)
      || !Array.isArray(layer.adr) || !layer.adr.every(nonEmptyString)
      || !(layer.detailRoute === undefined || validDetailRoute(layer.detailRoute))) return false
    layerIds.add(layer.id)
  }
  const stepIds = new Set<string>()
  for (const step of value.imageAnatomy.steps) {
    if (!record(step) || !nonEmptyString(step.id) || !nonEmptyString(step.label) || !validStatus(step.status)
      || stepIds.has(step.id) || !nonEmptyString(step.detail)
      || !Array.isArray(step.adr) || !step.adr.every(nonEmptyString)) return false
    stepIds.add(step.id)
  }
  const planItems = new Set<string>()
  for (const row of value.planVsReality) {
    if (!record(row) || !nonEmptyString(row.item) || planItems.has(row.item)
      || !nonEmptyString(row.plan) || !nonEmptyString(row.reality) || !validStatus(row.status)) return false
    planItems.add(row.item)
  }
  return counts.namespaces === value.namespaces.length
}

function validComplianceYaml(raw: string): boolean {
  try {
    const parsed = parseYaml(raw) as unknown
    if (!record(parsed) || parsed.schema !== 'openbank.compliance-controls/v1'
      || !Array.isArray(parsed.frameworks) || !Array.isArray(parsed.controls)
      || parsed.frameworks.length === 0 || parsed.controls.length === 0 || parsed.controls.length > 200) return false
    const frameworkIds = new Set<string>()
    for (const framework of parsed.frameworks) {
      if (!record(framework) || typeof framework.id !== 'string' || !framework.id.trim()
        || typeof framework.name !== 'string' || !framework.name.trim() || frameworkIds.has(framework.id)) return false
      frameworkIds.add(framework.id)
    }
    const controlIds = new Set<string>()
    for (const control of parsed.controls) {
      if (!record(control) || typeof control.id !== 'string' || !control.id.trim() || controlIds.has(control.id)
        || typeof control.title !== 'string' || !control.title.trim()
        || typeof control.category !== 'string' || !control.category.trim()
        || typeof control.evidence !== 'string' || !control.evidence.trim()
        || !['enforced', 'partial', 'audit', 'planned', 'unknown'].includes(String(control.status))
        || (control.declaredStatus !== undefined && !['enforced', 'partial', 'audit', 'planned', 'unknown'].includes(String(control.declaredStatus)))
        || (control.evidenceSource !== undefined && typeof control.evidenceSource !== 'string')
        || !Array.isArray(control.references) || !control.references.every(nonEmptyString)
        || (control.derivedFrom !== undefined && (typeof control.derivedFrom !== 'string'
          || !/^[A-Za-z][A-Za-z0-9]*(?:\.[A-Za-z][A-Za-z0-9]*)*$/.test(control.derivedFrom)))
        || !Array.isArray(control.frameworks) || control.frameworks.length === 0
        || !control.frameworks.every(id => typeof id === 'string' && frameworkIds.has(id))) return false
      controlIds.add(control.id)
    }
    return true
  } catch { return false }
}

function validBpmn(items: unknown): items is AdminUiContentSnapshot['content']['bpmnManifests'] {
  if (!Array.isArray(items) || items.length === 0 || items.length > 64) return false
  const slugs = new Set<string>()
  for (const item of items) {
    if (!record(item) || typeof item.slug !== 'string' || !/^[a-z0-9]+(?:-[a-z0-9]+)*$/.test(item.slug)
      || slugs.has(item.slug) || typeof item.yaml !== 'string' || Buffer.byteLength(item.yaml, 'utf8') > MAX_FILE_BYTES) return false
    try {
      const parsed = parseYaml(item.yaml) as Record<string, unknown>
      if (!record(parsed) || !BpmnProcessSchema.safeParse({ ...parsed, slug: item.slug }).success) return false
    } catch { return false }
    slugs.add(item.slug)
  }
  return true
}

function validLibDocs(items: unknown): items is AdminUiContentSnapshot['content']['libsDocs'] {
  if (!Array.isArray(items) || items.length === 0 || items.length > 128) return false
  const paths = new Set<string>()
  for (const item of items) {
    if (!record(item) || typeof item.path !== 'string'
      || !/^openbank-libs\/docs\/[A-Za-z0-9][A-Za-z0-9._-]*\.md$/.test(item.path)
      || item.path.split('/').includes('..') || paths.has(item.path)
      || typeof item.markdown !== 'string' || Buffer.byteLength(item.markdown, 'utf8') > MAX_FILE_BYTES) return false
    paths.add(item.path)
  }
  return true
}

/** Fail-closed contract for source content; it does not imply that authored claims are true. */
export function isAdminUiContentSnapshot(value: unknown, nowMs = Date.now()): value is AdminUiContentSnapshot {
  if (!record(value) || !hasExactKeys(value, ['schemaVersion', 'sourceSha', 'collectedAt', 'content'])
    || value.schemaVersion !== 1 || typeof value.sourceSha !== 'string' || !SOURCE_SHA.test(value.sourceSha)
    || !validTimestamp(value.collectedAt, nowMs) || !record(value.content)
    || !(hasExactKeys(value.content, CONTENT_KEYS) || hasExactKeys(value.content, CONTENT_KEYS_WITH_CLUSTER))) return false

  const content = value.content
  if (!validCatalog(content.catalog)
    || !validServiceGraph(content.serviceGraph)
    || !validGovernance(content.governance)
    || !validSecurityGraph(content.securityGraph)
    || (content.clusterTopology !== undefined && !validClusterTopology(content.clusterTopology, nowMs))
    || typeof content.bcpPolicyMarkdown !== 'string' || Buffer.byteLength(content.bcpPolicyMarkdown, 'utf8') > MAX_FILE_BYTES
    || parseBcpPolicy(content.bcpPolicyMarkdown) === null
    || typeof content.complianceControlsYaml !== 'string' || Buffer.byteLength(content.complianceControlsYaml, 'utf8') > MAX_FILE_BYTES
    || !validComplianceYaml(content.complianceControlsYaml)
    || !validBpmn(content.bpmnManifests) || !validLibDocs(content.libsDocs)) return false

  try {
    return Buffer.byteLength(JSON.stringify(value), 'utf8') <= ADMIN_UI_CONTENT_MAX_BYTES
  } catch { return false }
}

export function adminUiContentCollectedAt(snapshot: AdminUiContentSnapshot): string {
  return snapshot.collectedAt
}

export function adminUiContentSnapshotBaseUrl(): string | undefined {
  if (process.env.OPENBANK_ADMIN_UI_CONTENT_SNAPSHOT_URL !== undefined) return process.env.OPENBANK_ADMIN_UI_CONTENT_SNAPSHOT_URL
  const base = process.env.OPENBANK_EVIDENCE_STORE_URL
  return base ? `${base.replace(/\/+$/, '')}/admin-ui/content` : undefined
}

export function createAdminUiContentSnapshotReader(
  options: Omit<EvidenceSnapshotReaderOptions<AdminUiContentSnapshot>, 'baseUrl' | 'validateSnapshot' | 'collectedAt' | 'maxBytes' | 'maxDataAgeMs'> = {},
) {
  return createEvidenceSnapshotReader({
    ...options,
    baseUrl: adminUiContentSnapshotBaseUrl,
    validateSnapshot: isAdminUiContentSnapshot,
    collectedAt: adminUiContentCollectedAt,
    maxBytes: ADMIN_UI_CONTENT_MAX_BYTES,
    maxDataAgeMs: 2 * 60 * 60 * 1000,
  })
}
