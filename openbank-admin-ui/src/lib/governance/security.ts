// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Server-side loader for the zero-trust security posture. The build derives
// security-graph.json from the real, gitops-wired platform manifests
// (gitops/components/*/network-policies.yaml, kyverno verify-images-policy.yaml)
// via scripts/generate-security-graph.mjs — governance-as-code (ADR-0029),
// never a hand-drawn claim. `istio` is unconditionally unavailable: no service
// mesh runs in the sandbox (ADR-0098; #1666/#1667/#1710), so there is no live
// signal to derive — see the generator's module comment. READ-ONLY consumer
// (CLAUDE rule #3). Mirrors the service-graph.json sourcing (api/catalog/graph):
// $OPENBANK_SECURITY_GRAPH or <cwd>/security-graph.json; degrades to null when
// no snapshot is bundled.

import { promises as fs } from 'fs'
import path from 'path'

export interface NetworkCoverageGap {
  namespace: string
  service: string
}

export interface SecurityPosture {
  schema: string
  source: string
  collectedAt: string | null
  istio: {
    available: boolean
    deployed?: boolean
    note?: string
  }
  network: {
    available: boolean
    defaultDeny?: boolean
    coverage?: { total: number; covered: number; gaps: NetworkCoverageGap[] }
    egressTargets?: { target: string; ports: number[] }[]
    ingressRules?: { policy: string | null; namespace?: string; ports: number[] }[]
    internetEgress?: 'opt-in' | 'open'
    egressRestrictedApps?: string[]
  }
  supplyChain: {
    available: boolean
    engine?: string
    policy?: string | null
    mode?: string
    imagePattern?: string | null
    rekor?: string | null
    enforced?: boolean
  }
  available: boolean
}

function postureFile(): string {
  return process.env.OPENBANK_SECURITY_GRAPH ?? path.resolve(process.cwd(), 'security-graph.json')
}

export async function loadSecurityPosture(): Promise<SecurityPosture | null> {
  try {
    const raw = await fs.readFile(postureFile(), 'utf-8')
    const parsed = JSON.parse(raw) as SecurityPosture
    if (parsed?.istio || parsed?.network) return parsed
    return null
  } catch {
    return null
  }
}
