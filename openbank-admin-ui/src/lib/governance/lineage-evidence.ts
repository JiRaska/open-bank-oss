// SPDX-License-Identifier: Apache-2.0

export type LineageRole = 'producer' | 'consumer' | 'both' | 'internal'
export type LineageDomain = 'core' | 'payments' | 'compliance' | 'identity' | 'open-banking' | 'platform'
export type LineageRelation = 'api' | 'topic' | 'datastore' | 'unknown'
export type LineageLink = { serviceName: string; relationType: LineageRelation; description?: string }
export type GovernanceLineageService = {
  serviceName: string
  dataDomain: LineageDomain | null
  dataLineageRole: LineageRole | null
  lineage?: {
    upstream?: LineageLink[]
    downstream?: LineageLink[]
    interfaces?: { apis?: string[]; topics?: string[]; datastores?: string[] }
  }
}

export type GovernanceLineageEnvelope =
  | { available: false; services: [] }
  | { available: true; services: GovernanceLineageService[] }

const DOMAINS = new Set<LineageDomain>(['core', 'payments', 'compliance', 'identity', 'open-banking', 'platform'])
const ROLES = new Set<LineageRole>(['producer', 'consumer', 'both', 'internal'])
const RELATIONS = new Set<LineageRelation>(['api', 'topic', 'datastore', 'unknown'])

function record(value: unknown): Record<string, unknown> | null {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
    ? value as Record<string, unknown>
    : null
}

function nonEmpty(value: unknown): value is string {
  return typeof value === 'string' && value.trim().length > 0 && value.length <= 500
}

function stringList(value: unknown): string[] | null {
  if (!Array.isArray(value) || value.length > 200 || !value.every(nonEmpty)) return null
  if (new Set(value).size !== value.length) return null
  return [...value]
}

function linkList(value: unknown): LineageLink[] | null {
  if (!Array.isArray(value) || value.length > 200) return null
  const links: LineageLink[] = []
  for (const raw of value) {
    const link = record(raw)
    if (!link || !nonEmpty(link.serviceName) || !RELATIONS.has(link.relationType as LineageRelation) ||
      (link.description !== undefined && !nonEmpty(link.description))) return null
    links.push({
      serviceName: link.serviceName as string,
      relationType: link.relationType as LineageRelation,
      ...(link.description === undefined ? {} : { description: link.description as string }),
    })
  }
  if (new Set(links.map(link => `${link.serviceName}\u0000${link.relationType}`)).size !== links.length) return null
  return links
}

function parseService(value: unknown): GovernanceLineageService | null {
  const service = record(value)
  if (!service || !nonEmpty(service.serviceName) ||
    (service.dataDomain !== null && !DOMAINS.has(service.dataDomain as LineageDomain)) ||
    (service.dataLineageRole !== null && !ROLES.has(service.dataLineageRole as LineageRole))) return null

  const parsed: GovernanceLineageService = {
    serviceName: service.serviceName as string,
    dataDomain: service.dataDomain as LineageDomain | null,
    dataLineageRole: service.dataLineageRole as LineageRole | null,
  }
  if (service.lineage === undefined) return parsed
  const lineage = record(service.lineage)
  if (!lineage) return null
  const upstream = lineage.upstream === undefined ? undefined : linkList(lineage.upstream)
  const downstream = lineage.downstream === undefined ? undefined : linkList(lineage.downstream)
  if (upstream === null || downstream === null) return null
  let interfaces: { interfaces: { apis?: string[]; topics?: string[]; datastores?: string[] } } | undefined
  if (lineage.interfaces !== undefined) {
    const rawInterfaces = record(lineage.interfaces)
    if (!rawInterfaces) return null
    const parsedInterfaces: { apis?: string[]; topics?: string[]; datastores?: string[] } = {}
    for (const key of ['apis', 'topics', 'datastores'] as const) {
      if (rawInterfaces[key] === undefined) continue
      const values = stringList(rawInterfaces[key])
      if (values === null) return null
      parsedInterfaces[key] = values
    }
    interfaces = { interfaces: parsedInterfaces }
  }
  parsed.lineage = {
    ...(upstream === undefined ? {} : { upstream }),
    ...(downstream === undefined ? {} : { downstream }),
    ...(interfaces ?? {}),
  }
  return parsed
}

export function parseGovernanceLineage(value: unknown): GovernanceLineageEnvelope | null {
  const envelope = record(value)
  if (!envelope || typeof envelope.available !== 'boolean' || !Array.isArray(envelope.services) || envelope.services.length > 200) return null
  if (!envelope.available) return envelope.services.length === 0 ? { available: false, services: [] } : null
  const services = envelope.services.map(parseService)
  if (services.some(service => service === null)) return null
  const valid = services as GovernanceLineageService[]
  if (new Set(valid.map(service => service.serviceName)).size !== valid.length) return null
  return { available: true, services: valid }
}
