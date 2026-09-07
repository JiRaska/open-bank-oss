// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Communication Studio persona list (ADR-0285 D7, phase 1 — read-only).
//
// The prompt TEXT is deliberately not in this response: the list page shows sizes and coverage,
// and a list endpoint that shipped every system prompt would put the whole fleet's core prompts
// on the wire for a page that renders none of them. The detail route serves one persona's text.

import { NextResponse } from 'next/server'
import { loadPromptRegistry } from '@/lib/governance/prompts'

export const dynamic = 'force-dynamic'

export async function GET() {
  const projection = await loadPromptRegistry()
  return NextResponse.json({
    available: projection.available,
    schemaVersion: projection.schemaVersion,
    relatedAdrs: projection.relatedAdrs,
    personas: projection.personas.map(p => ({
      id: p.id,
      status: p.status,
      plane: p.plane,
      charter: p.charter,
      source: p.source,
      reason: p.reason,
      blockedBy: p.blockedBy,
      placeholders: p.placeholders,
      editableLayers: p.editableLayers,
      versionCount: p.versions.length,
      versionIds: p.versions.map(v => v.id),
      coreChars: p.versions.reduce((sum, v) => sum + v.chars, 0),
    })),
  }, { headers: { 'Cache-Control': 'no-store' } })
}
