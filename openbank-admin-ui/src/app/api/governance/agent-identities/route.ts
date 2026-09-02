// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// The enforced agent registry, projected to just what the approval queue needs to attribute
// a proposal to a charter (issue #5904). Reads openbank-libs/governance/agents.yaml through
// the shared loader — no second source of truth for "what does agents.yaml actually say".
//
// `available: false` is a first-class answer, not an error: agents.yaml is bundled into the
// image by Dockerfile COPY, and an image built without it must make the queue say "identity
// unverifiable" rather than silently report every proposer as unchartered.

import { NextResponse } from 'next/server'
import { loadAgentCharters } from '@/lib/governance/agentCharters'
import type { AgentIdentityRegistry } from '@/lib/governance/agentIdentity'

export const dynamic = 'force-dynamic'

export async function GET() {
  try {
    const { available, agents } = await loadAgentCharters()
    const body: AgentIdentityRegistry = {
      available,
      agents: agents.map(a => ({
        id: a.id,
        plane: a.plane,
        charter: a.charter,
        requiresHuman: a.requiresHuman,
      })),
    }
    return NextResponse.json(body)
  } catch {
    return NextResponse.json({ available: false, agents: [] } satisfies AgentIdentityRegistry)
  }
}
