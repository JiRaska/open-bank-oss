// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Serves the cluster-topology dossier artefact (ADR-0081). The build bakes cluster-topology.json
// via scripts/generate-cluster-topology.mjs — a pure GitOps + Dockerfile repo-walk (no creds) that
// DERIVES the namespace set, the NetworkPolicy/ExternalSecret/ClusterPolicy counts, and the image
// anatomy, and joins them with the curated namespace roles / security layers. Read-only consumer.

import { NextResponse } from 'next/server'
import { promises as fs } from 'fs'
import path from 'path'
import { resolveAdminUiContent } from '@/lib/evidence/contentSource'

export const dynamic = 'force-dynamic'

function topologyFile(): string {
  return process.env.OPENBANK_CLUSTER_TOPOLOGY ?? path.resolve(process.cwd(), 'cluster-topology.json')
}

export async function GET() {
  const resolved = await resolveAdminUiContent(
    snapshot => snapshot.content.clusterTopology,
    async () => {
      try { return JSON.parse(await fs.readFile(topologyFile(), 'utf-8')) as Record<string, unknown> }
      catch { return null }
    },
  )
  if (!resolved.value) {
    // Honest empty — the page degrades through the graceful-state rule rather than faking a posture.
    return NextResponse.json({
      schema: 'openbank.cluster-topology/v1', source: 'unavailable', generatedAt: null,
      counts: {}, groups: [], namespaces: [], securityLayers: [], imageAnatomy: { steps: [] }, planVsReality: [],
      contentSource: resolved.contentSource,
    })
  }
  return NextResponse.json({ ...resolved.value, contentSource: resolved.contentSource })
}
