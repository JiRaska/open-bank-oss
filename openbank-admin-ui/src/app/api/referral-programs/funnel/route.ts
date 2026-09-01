// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import { NextResponse } from 'next/server'
import { auth } from '@/auth'

export const dynamic = 'force-dynamic'

const CLICKHOUSE_URL = process.env.CLICKHOUSE_URL || 'http://localhost:8123'
const CLICKHOUSE_USER = process.env.CLICKHOUSE_USER
const CLICKHOUSE_PASSWORD = process.env.CLICKHOUSE_PASSWORD

export async function GET() {
  const session = await auth()
  if (!session?.user?.accessToken) return NextResponse.json({ error: 'unauthenticated' }, { status: 401 })
  const headers: Record<string, string> = { 'content-type': 'text/plain' }
  if (CLICKHOUSE_USER) headers['X-ClickHouse-User'] = CLICKHOUSE_USER
  if (CLICKHOUSE_PASSWORD) headers['X-ClickHouse-Key'] = CLICKHOUSE_PASSWORD
  try {
    const response = await fetch(`${CLICKHOUSE_URL}/?default_format=JSON`, {
      method: 'POST',
      headers,
      body: `SELECT program_id, qualified_invites, reward_requests, rewarded_invites,
                    failed_rewards, reversed_rewards, requested_reward_amount, currency,
                    first_observed_at, last_observed_at
             FROM openbank_analytics.gold_referral_funnel
             ORDER BY last_observed_at DESC`,
      cache: 'no-store',
      signal: AbortSignal.timeout(4000),
    })
    if (!response.ok) return NextResponse.json({ items: [], state: 'unavailable' })
    const body = await response.json() as { data?: Record<string, unknown>[] }
    return NextResponse.json({
      state: 'ok',
      items: (body.data ?? []).map(row => ({
        programId: String(row.program_id ?? ''),
        qualifiedInvites: Number(row.qualified_invites ?? 0),
        rewardRequests: Number(row.reward_requests ?? 0),
        rewardedInvites: Number(row.rewarded_invites ?? 0),
        failedRewards: Number(row.failed_rewards ?? 0),
        reversedRewards: Number(row.reversed_rewards ?? 0),
        requestedRewardAmount: Number(row.requested_reward_amount ?? 0),
        currency: String(row.currency ?? ''),
        firstObservedAt: String(row.first_observed_at ?? ''),
        lastObservedAt: String(row.last_observed_at ?? ''),
      })).filter(row => row.programId.length > 0),
    })
  } catch {
    return NextResponse.json({ items: [], state: 'unavailable' })
  }
}
