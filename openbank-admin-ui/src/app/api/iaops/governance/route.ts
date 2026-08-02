// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { NextResponse } from 'next/server'
import { loadAgentCharters } from '@/lib/governance/agentCharters'
import { loadAiGovernanceSnapshot } from '@/lib/governance/aiGovernanceSnapshot'

export const dynamic = 'force-dynamic'

export async function GET() {
  const [registry, snapshot] = await Promise.all([
    loadAgentCharters(),
    Promise.resolve(loadAiGovernanceSnapshot()),
  ])

  // Posture: read live from agents.yaml defaults where possible.
  const defaults = registry.defaults
  const enforced = typeof defaults.enforced === 'string' ? defaults.enforced : 'advisory'
  const policyDecision = typeof defaults.policy_decision === 'string' ? defaults.policy_decision : 'deny'

  return NextResponse.json({
    ...snapshot,
    enforcement: enforced,            // enforced (block) since #743 — deny-by-default at the gate
    policyDefault: policyDecision,    // deny
    chartersAvailable: registry.available,
    agentCount: registry.agents.length,
    agents: registry.agents,
    toolTiers: registry.toolTiers,
    runtime: registry.runtime,
    modelGateway: registry.modelGateway,
  }, { headers: { 'Cache-Control': 'no-store' } })
}
