// SPDX-License-Identifier: Apache-2.0

export interface ContextNode {
  key: string
  namespace: 'COMPLAINT' | 'INCIDENT'
  type: string
  sourceSystem: string
  sourceRef: string
  label: string
  classification: 'INTERNAL' | 'CONFIDENTIAL' | 'RESTRICTED'
  validFrom: string
  validTo: string | null
  recordedAt: string
  sourceVersion: number
}

export interface ContextEdge {
  id: string
  namespace: 'COMPLAINT' | 'INCIDENT'
  from: string
  to: string
  relation: string
  evidenceRef: string
  validFrom: string
  validTo: string | null
  recordedAt: string
  sourceVersion: number
}

export interface ContextNeighborhood {
  root: string
  nodes: ContextNode[]
  edges: ContextEdge[]
  truncated: boolean
}

const classifications = new Set(['INTERNAL', 'CONFIDENTIAL', 'RESTRICTED'])

export function parseContextNeighborhood(value: unknown): ContextNeighborhood {
  if (!value || typeof value !== 'object') throw new Error('invalid context graph')
  const graph = value as Partial<ContextNeighborhood>
  if (typeof graph.root !== 'string' || typeof graph.truncated !== 'boolean'
    || !Array.isArray(graph.nodes) || !Array.isArray(graph.edges)) throw new Error('invalid context graph')
  if (!graph.nodes.every(node => node && typeof node.key === 'string' && typeof node.type === 'string'
    && typeof node.label === 'string' && typeof node.sourceSystem === 'string'
    && classifications.has(node.classification))) throw new Error('invalid context nodes')
  const keys = new Set(graph.nodes.map(node => node.key))
  if (!graph.edges.every(edge => edge && typeof edge.id === 'string' && typeof edge.relation === 'string'
    && keys.has(edge.from) && keys.has(edge.to))) throw new Error('invalid context edges')
  return graph as ContextNeighborhood
}
