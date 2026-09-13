// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// One Communication Studio persona (ADR-0285 D7, phase 1 — read-only).
//
// GET only. ADR-0285 D1: the core is never writable through any API, and the editable style and
// playbook layers are served by openbank-communication-service from phase 2 — not from here.

import { NextResponse } from 'next/server'
import { loadPersona } from '@/lib/governance/prompts'

export const dynamic = 'force-dynamic'

export async function GET(_request: Request, context: { params: Promise<{ personaId: string }> }) {
  const { personaId } = await context.params
  const persona = await loadPersona(personaId)
  if (!persona) {
    return NextResponse.json({ error: 'persona not found' }, { status: 404, headers: { 'Cache-Control': 'no-store' } })
  }
  return NextResponse.json(persona, { headers: { 'Cache-Control': 'no-store' } })
}
