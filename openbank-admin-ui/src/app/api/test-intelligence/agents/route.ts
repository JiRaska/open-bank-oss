// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import { NextResponse } from 'next/server'
import { auth } from '@/auth'
import type { TestAgentFinding } from '@/lib/types/test-intelligence'
import type { TestIntelligenceReport } from '@/lib/types/test-intelligence'
import { promises as fs } from 'fs'
import path from 'path'

export const dynamic = 'force-dynamic'

function flakyHunterBase(): string {
  if (process.env.SERVICES_HOST === 'container') return 'http://flaky-test-hunter.flaky-test-hunter.svc:8148'
  return process.env.FLAKY_TEST_HUNTER_URL ?? 'http://localhost:8148'
}

export async function GET(): Promise<NextResponse> {
  const accessToken = (await auth())?.user?.accessToken
  if (!accessToken) return NextResponse.json({ findings: [], available: false, reason: 'unauthorized' }, { status: 401 })
  try {
    const response = await fetch(`${flakyHunterBase()}/api/v1/flaky-test-hunter/findings`, {
      headers: { Authorization: `Bearer ${accessToken}` }, signal: AbortSignal.timeout(10_000),
    })
    if (!response.ok) return NextResponse.json({ findings: [], available: false })
    return NextResponse.json({ findings: await response.json() as TestAgentFinding[], available: true })
  } catch {
    return NextResponse.json({ findings: [], available: false })
  }
}

export async function POST(): Promise<NextResponse> {
  const session = await auth()
  const accessToken = session?.user?.accessToken
  if (!accessToken) return NextResponse.json({ findings: [], available: false, reason: 'unauthorized' }, { status: 401 })
  if (!session.user.roles?.includes('ROLE_ADMIN')) {
    return NextResponse.json({ findings: [], available: false, reason: 'forbidden' }, { status: 403 })
  }
  try {
    const file = process.env.OPENBANK_TEST_INTELLIGENCE ?? path.resolve(process.cwd(), 'test-intelligence.json')
    const report = JSON.parse(await fs.readFile(file, 'utf8')) as TestIntelligenceReport
    const payload = {
      snapshotId: `${report.collectedAt}:schema-${report.schemaVersion}`,
      collectedAt: report.collectedAt,
      components: report.components.map(component => ({
        component: component.component,
        moneyPath: component.moneyPath,
        evidence: component.evidence.map(item => ({ kind: item.kind, state: item.state })),
        declaredInfrastructure: component.testInfrastructure?.declared ?? [],
        observedInfrastructureStarts: component.testInfrastructure?.observed.filter(item => item.lifecycle === 'started').length ?? 0,
      })),
    }
    const response = await fetch(`${flakyHunterBase()}/api/v1/flaky-test-hunter/evidence/analyze`, {
      method: 'POST', headers: { Authorization: `Bearer ${accessToken}`, 'Content-Type': 'application/json' },
      body: JSON.stringify(payload), signal: AbortSignal.timeout(30_000),
    })
    if (!response.ok) return NextResponse.json({ findings: [], available: false }, { status: 502 })
    return NextResponse.json({ findings: await response.json() as TestAgentFinding[], available: true })
  } catch {
    return NextResponse.json({ findings: [], available: false }, { status: 502 })
  }
}
