// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Server component (ADR-0056). ADR-0383 customer MCP channel explainer (#16606). The status badges
// are derived from build-baked repository data (gate catalog + ADR corpus), never hand-written.
import { McpChannelExplainer } from '@/components/docs/McpChannelExplainer'
import { resolveControls } from '@/lib/docs/mcpChannelControls'
import { loadMcpChannelEvidence } from '@/lib/docs/mcpChannelEvidence'

export const dynamic = 'force-dynamic'

export default async function McpChannelPage() {
  const snapshot = await loadMcpChannelEvidence()
  return (
    <McpChannelExplainer
      resolved={resolveControls(snapshot)}
      adr={snapshot.adrs?.['0383'] ?? null}
      sourcesAvailable={{ gates: snapshot.gates !== null, adrs: snapshot.adrs !== null }}
    />
  )
}
