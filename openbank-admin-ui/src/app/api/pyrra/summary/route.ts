// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import { NextResponse } from 'next/server'

export const dynamic = 'force-dynamic'

const CUSTOMER_JOURNEY_SLOS = [
  'openbank-transaction-availability',
  'openbank-ledger-availability',
  'openbank-sepa-instant-availability',
  'openbank-domestic-payment-availability',
  'openbank-settlement-availability',
  'openbank-fraud-availability',
] as const

type Objective = {
  labels?: { __name__?: string }
  target?: number
  window?: string
  description?: string
}

type Status = {
  availability?: { percentage?: number; total?: number; errors?: number }
  budget?: { remaining?: number }
}

function pyrraBase(): string {
  if (process.env.SERVICES_HOST === 'container') return 'http://pyrra-api:9099/tools/pyrra'
  return process.env.PYRRA_URL ?? 'http://localhost:9099/tools/pyrra'
}

async function rpc<T>(method: 'List' | 'GetStatus', body: object, signal: AbortSignal): Promise<T> {
  const response = await fetch(`${pyrraBase()}/objectives.v1alpha1.ObjectiveService/${method}`, {
    method: 'POST',
    signal,
    headers: {
      Accept: 'application/json',
      'Content-Type': 'application/json',
      'Connect-Protocol-Version': '1',
    },
    body: JSON.stringify(body),
  })
  if (!response.ok) throw new Error(`pyrra responded ${response.status}`)
  return response.json() as Promise<T>
}

function finite(value: unknown): value is number {
  return typeof value === 'number' && Number.isFinite(value)
}

export async function GET() {
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), 8000)

  try {
    const list = await rpc<{ objectives?: Objective[] }>('List', {}, controller.signal)
    const objectives = Array.isArray(list.objectives) ? list.objectives : []
    const byName = new Map(objectives.flatMap(objective => {
      const name = objective.labels?.__name__
      return typeof name === 'string' ? [[name, objective] as const] : []
    }))

    const selected = CUSTOMER_JOURNEY_SLOS.flatMap(name => {
      const objective = byName.get(name)
      return objective && finite(objective.target) ? [{ name, objective }] : []
    })

    const rows = await Promise.all(selected.map(async ({ name, objective }) => {
      try {
        const result = await rpc<{ status?: Status[] }>('GetStatus', {
          expr: `{__name__="${name}"}`,
        }, controller.signal)
        const status = Array.isArray(result.status) && result.status.length === 1 ? result.status[0] : null
        const remaining = status?.budget?.remaining
        const availability = status?.availability?.percentage
        return {
          name,
          target: objective.target,
          window: objective.window ?? null,
          description: objective.description ?? null,
          budgetRemaining: finite(remaining) ? remaining : null,
          availability: finite(availability) ? availability : null,
          requestCount: finite(status?.availability?.total) ? status.availability.total : null,
        }
      } catch {
        return {
          name,
          target: objective.target,
          window: objective.window ?? null,
          description: objective.description ?? null,
          budgetRemaining: null,
          availability: null,
          requestCount: null,
        }
      }
    }))

    return NextResponse.json({
      available: true,
      configured: objectives.length,
      monitored: rows.length,
      objectives: rows,
    }, { headers: { 'Cache-Control': 'no-store' } })
  } catch (error) {
    return NextResponse.json({
      available: false,
      error: error instanceof DOMException && error.name === 'AbortError' ? 'pyrra_timeout' : 'pyrra_unreachable',
      configured: 0,
      monitored: 0,
      objectives: [],
    }, { status: 502, headers: { 'Cache-Control': 'no-store' } })
  } finally {
    clearTimeout(timer)
  }
}
