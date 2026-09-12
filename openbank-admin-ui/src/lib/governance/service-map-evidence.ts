// SPDX-License-Identifier: Apache-2.0

import type { GovernanceManifestEntry } from '@/lib/governance/manifest'

export type MapHealthEntry = { port: number; status: 'UP' | 'DOWN' | 'UNKNOWN' }
export type MapEdge = { from: string; to: string; via: string; type: 'rest' | 'kafka' }
export type MapNode = { name: string; dependsOn?: number; dependedOnBy?: number }
export type MapInfraNode = { id: string; kind: 'infra'; tech: string; label: string }
export type MapExternalNode = { id: string; kind: 'external'; vendor: string; label: string }
export type MapInfraEdge = { from: string; to: string; type: 'db' | 'broker' | 'auth' | 'authz' }
export type MapExternalEdge = { from: string; to: string; type: 'push' | 'webhook' | 'registry' | 'api' | 'llm'; enabled: boolean }
export type ServiceMapGraph = {
  edges: MapEdge[]; nodes: MapNode[]; infraNodes: MapInfraNode[]; externalNodes: MapExternalNode[]
  infraEdges: MapInfraEdge[]; externalEdges: MapExternalEdge[]
}

function record(value: unknown): Record<string, unknown> | null {
  return typeof value === 'object' && value !== null && !Array.isArray(value) ? value as Record<string, unknown> : null
}
const text = (value: unknown): value is string => typeof value === 'string' && value.trim().length > 0
const count = (value: unknown): value is number => Number.isInteger(value) && (value as number) >= 0
const port = (value: unknown): value is number => Number.isInteger(value) && (value as number) > 0 && (value as number) <= 65_535

function arrayOf<T>(value: unknown, parser: (item: unknown) => T | null): T[] | null {
  if (!Array.isArray(value) || value.length > 500) return null
  const parsed = value.map(parser)
  return parsed.some(item => item === null) ? null : parsed as T[]
}

export function parseMapHealth(value: unknown): MapHealthEntry[] | null {
  const envelope = record(value)
  const services = envelope && arrayOf(envelope.services, raw => {
    const item = record(raw)
    return item && port(item.port) && ['UP', 'DOWN', 'UNKNOWN'].includes(String(item.status))
      ? { port: item.port, status: item.status as MapHealthEntry['status'] }
      : null
  })
  if (!services || new Set(services.map(item => item.port)).size !== services.length) return null
  return services
}

export function parseMapGovernance(value: unknown): { available: boolean; byService: Record<string, GovernanceManifestEntry> } | null {
  const envelope = record(value)
  if (!envelope || typeof envelope.available !== 'boolean') return null
  const byService = record(envelope.byService)
  if (!byService || Object.keys(byService).length > 200) return null
  for (const [key, raw] of Object.entries(byService)) {
    const item = record(raw)
    if (!item || item.serviceName !== key || !text(item.serviceName) || !text(item.primaryDatastore) ||
      !(item.databaseName === null || text(item.databaseName)) || !text(item.dataLineageRole) ||
      !(item.flywayDeclaredVersion === null || text(item.flywayDeclaredVersion)) ||
      !(item.flywayCurrentVersion === null || text(item.flywayCurrentVersion)) ||
      ![true, false, 'unknown'].includes(item.flywayDrift as boolean | string)) return null
  }
  if (!envelope.available && Object.keys(byService).length > 0) return null
  return { available: envelope.available, byService: byService as Record<string, GovernanceManifestEntry> }
}

export function parseServiceMapGraph(value: unknown): { available: boolean; graph: ServiceMapGraph } | null {
  const envelope = record(value)
  if (!envelope || typeof envelope.available !== 'boolean') return null
  const edges = arrayOf(envelope.edges, raw => {
    const item = record(raw)
    return item && text(item.from) && text(item.to) && text(item.via) && ['rest', 'kafka'].includes(String(item.type)) ? item as MapEdge : null
  })
  const nodes = arrayOf(envelope.nodes, raw => {
    const item = record(raw)
    return item && text(item.name) && (item.dependsOn === undefined || count(item.dependsOn)) &&
      (item.dependedOnBy === undefined || count(item.dependedOnBy)) ? item as MapNode : null
  })
  const infraNodes = arrayOf(envelope.infraNodes ?? [], raw => {
    const item = record(raw); return item && item.kind === 'infra' && text(item.id) && text(item.tech) && text(item.label) ? item as MapInfraNode : null
  })
  const externalNodes = arrayOf(envelope.externalNodes ?? [], raw => {
    const item = record(raw); return item && item.kind === 'external' && text(item.id) && text(item.vendor) && text(item.label) ? item as MapExternalNode : null
  })
  const infraEdges = arrayOf(envelope.infraEdges ?? [], raw => {
    const item = record(raw); return item && text(item.from) && text(item.to) && ['db', 'broker', 'auth', 'authz'].includes(String(item.type)) ? item as MapInfraEdge : null
  })
  const externalEdges = arrayOf(envelope.externalEdges ?? [], raw => {
    const item = record(raw); return item && text(item.from) && text(item.to) && ['push', 'webhook', 'registry', 'api', 'llm'].includes(String(item.type)) && typeof item.enabled === 'boolean' ? item as MapExternalEdge : null
  })
  if (!edges || !nodes || !infraNodes || !externalNodes || !infraEdges || !externalEdges) return null
  if (!envelope.available && [edges, nodes, infraNodes, externalNodes, infraEdges, externalEdges].some(list => list.length)) return null
  const nodeIds = [...nodes.map(node => node.name), ...infraNodes.map(node => node.id), ...externalNodes.map(node => node.id)]
  if (new Set(nodeIds).size !== nodeIds.length) return null
  return { available: envelope.available, graph: { edges, nodes, infraNodes, externalNodes, infraEdges, externalEdges } }
}
