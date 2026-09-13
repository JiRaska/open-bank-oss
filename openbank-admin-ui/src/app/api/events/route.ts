// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { NextResponse } from 'next/server'
import { promises as fs } from 'fs'
import path from 'path'

export const dynamic = 'force-dynamic'

// Serves the code-derived Kafka topic table (ADR-0029 D3 pattern). The build
// bakes events.json via scripts/generate-events.mjs, which derives it from
// docs/asyncapi/openbank-events.yaml cross-referenced with each service's own
// application.yaml — replacing the hand-maintained TypeScript array in
// src/app/docs/api/page.tsx that drifted the same way the AsyncAPI document
// itself drifted (#4761: 15 of ~23 topic names were fiction). READ-ONLY
// consumer (rule #3): never recomputed at runtime. If the snapshot is absent
// the route 200s with an empty, honest envelope (available:false).

interface EventTopic {
  channel: string
  topic: string
  description: string | null
  publishers: string[]
  consumers: string[]
  color: string
}

interface Events {
  schema: string
  source: string
  collectedAt: string | null
  topics: EventTopic[]
  unmatched: string[]
  available?: boolean
}

function eventsFile(): string {
  return process.env.OPENBANK_EVENTS ?? path.resolve(process.cwd(), 'events.json')
}

const UNAVAILABLE: Events = {
  schema: 'openbank.events/v1',
  source: 'no snapshot bundled',
  collectedAt: null,
  topics: [],
  unmatched: [],
  available: false,
}

export async function GET() {
  try {
    const raw = await fs.readFile(eventsFile(), 'utf-8')
    const parsed = JSON.parse(raw) as Events
    if (Array.isArray(parsed?.topics)) {
      return NextResponse.json({ ...parsed, available: true }, { headers: { 'Cache-Control': 'no-store' } })
    }
    return NextResponse.json(UNAVAILABLE, { headers: { 'Cache-Control': 'no-store' } })
  } catch {
    return NextResponse.json(UNAVAILABLE, { headers: { 'Cache-Control': 'no-store' } })
  }
}
