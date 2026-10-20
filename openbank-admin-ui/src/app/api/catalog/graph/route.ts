// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { NextResponse } from 'next/server'
import { promises as fs } from 'fs'
import path from 'path'
import { resolveAdminUiContent } from '@/lib/evidence/contentSource'

export const dynamic = 'force-dynamic'

// Serves the code-derived inter-service dependency graph (ADR-0029 D1). Prefer
// the validated independent reviewed-source snapshot, with service-graph.json
// as an explicitly labeled stale image fallback. READ-ONLY consumer (rule #3);
// degrades to available:false if neither source exists.

interface Edge { from: string; to: string; via: string; type: 'rest' | 'kafka' }
interface Node { name: string; short: string; dependsOn: number; dependedOnBy: number }
// Additive data-flow tiers (generate-service-graph.mjs). Optional so an older
// snapshot without them still parses; consumers default each to [].
interface InfraNode { id: string; kind: 'infra'; tech: string; label: string }
interface ExternalNode { id: string; kind: 'external'; vendor: string; label: string }
interface InfraEdge { from: string; to: string; type: 'db' | 'broker' | 'auth' | 'authz' }
interface ExternalEdge { from: string; to: string; type: 'push' | 'webhook' | 'registry' | 'api' | 'llm'; enabled: boolean }
interface ServiceGraph {
  schema: string
  source: string
  collectedAt: string | null
  totals: Record<string, number>
  nodes: Node[]
  edges: Edge[]
  danglingTopics: { topic: string; producedBy: string[]; consumedBy: string[] }[]
  infraNodes?: InfraNode[]
  externalNodes?: ExternalNode[]
  infraEdges?: InfraEdge[]
  externalEdges?: ExternalEdge[]
  available?: boolean
}

function graphFile(): string {
  return process.env.OPENBANK_SERVICE_GRAPH ?? path.resolve(process.cwd(), 'service-graph.json')
}

const UNAVAILABLE: ServiceGraph = {
  schema: 'openbank.service-graph/v1',
  source: 'no snapshot bundled',
  collectedAt: null,
  totals: {},
  nodes: [],
  edges: [],
  danglingTopics: [],
  infraNodes: [],
  externalNodes: [],
  infraEdges: [],
  externalEdges: [],
  available: false,
}

export async function GET() {
  const resolved = await resolveAdminUiContent(
    snapshot => snapshot.content.serviceGraph as unknown as ServiceGraph,
    async () => {
      try { return JSON.parse(await fs.readFile(graphFile(), 'utf-8')) as ServiceGraph } catch { return null }
    },
  )
  const graph = resolved.value
  const available = Boolean(graph && Array.isArray(graph.nodes) && Array.isArray(graph.edges))
  return NextResponse.json(
    { ...(available ? graph : UNAVAILABLE), available, contentSource: resolved.contentSource },
    { headers: { 'Cache-Control': 'no-store' } },
  )
}
