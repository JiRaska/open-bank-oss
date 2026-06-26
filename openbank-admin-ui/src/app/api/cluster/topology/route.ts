// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

// Serves the cluster-topology dossier artefact (ADR-0081). The build bakes cluster-topology.json
// via scripts/generate-cluster-topology.mjs — a pure GitOps + Dockerfile repo-walk (no creds) that
// DERIVES the namespace set, the NetworkPolicy/ExternalSecret/ClusterPolicy counts, and the image
// anatomy, and joins them with the curated namespace roles / security layers. Read-only consumer.

import { NextResponse } from 'next/server'
import { promises as fs } from 'fs'
import path from 'path'

export const dynamic = 'force-dynamic'

function topologyFile(): string {
  return process.env.OPENBANK_CLUSTER_TOPOLOGY ?? path.resolve(process.cwd(), 'cluster-topology.json')
}

export async function GET() {
  try {
    const raw = await fs.readFile(topologyFile(), 'utf-8')
    return NextResponse.json(JSON.parse(raw))
  } catch {
    // Honest empty — the page degrades through the graceful-state rule rather than faking a posture.
    return NextResponse.json(
      { schema: 'openbank.cluster-topology/v1', source: 'unavailable', generatedAt: null, counts: {}, groups: [], namespaces: [], securityLayers: [], imageAnatomy: { steps: [] }, planVsReality: [] },
      { status: 200 },
    )
  }
}
