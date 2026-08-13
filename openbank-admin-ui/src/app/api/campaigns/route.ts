// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Read-only campaign console (#2895). Authoring — the wizard, templates, the four-eyes
// submit — is ADR-0221 and deliberately NOT here: `submit` → `activate`-by-a-different-approver
// is designed for two people at a screen, and half of it behind a button is worse than none.

import { NextResponse } from 'next/server'
import { auth } from '@/auth'
import { serverSvcUrl } from '@/lib/services/bff'

export const dynamic = 'force-dynamic'

const CLICKHOUSE_URL = process.env.CLICKHOUSE_URL || 'http://localhost:8123'
const CLICKHOUSE_USER = process.env.CLICKHOUSE_USER
const CLICKHOUSE_PASSWORD = process.env.CLICKHOUSE_PASSWORD

interface CampaignEngagementRow {
  campaignId: string
  impressions: number
  clicks: number
  dismissals: number
  firstObservedAt: string
  lastObservedAt: string
}

async function readCampaignEngagement(): Promise<{
  state: 'ok' | 'unavailable'
  items: CampaignEngagementRow[]
}> {
  const headers: Record<string, string> = { 'content-type': 'text/plain' }
  if (CLICKHOUSE_USER) headers['X-ClickHouse-User'] = CLICKHOUSE_USER
  if (CLICKHOUSE_PASSWORD) headers['X-ClickHouse-Key'] = CLICKHOUSE_PASSWORD
  try {
    const response = await fetch(`${CLICKHOUSE_URL}/?default_format=JSON`, {
      method: 'POST',
      headers,
      body: `
        SELECT campaign_id,
               sum(impressions) AS impressions,
               sum(clicks) AS clicks,
               sum(dismissals) AS dismissals,
               min(first_observed_at) AS first_observed_at,
               max(last_observed_at) AS last_observed_at
        FROM openbank_analytics.gold_campaign_engagement
        GROUP BY campaign_id
        ORDER BY last_observed_at DESC`,
      cache: 'no-store',
      signal: AbortSignal.timeout(4000),
    })
    if (!response.ok) return { state: 'unavailable', items: [] }
    const body = await response.json() as { data?: Record<string, unknown>[] }
    return {
      state: 'ok',
      items: (body.data ?? []).map(row => ({
        campaignId: String(row.campaign_id ?? ''),
        impressions: Number(row.impressions ?? 0),
        clicks: Number(row.clicks ?? 0),
        dismissals: Number(row.dismissals ?? 0),
        firstObservedAt: String(row.first_observed_at ?? ''),
        lastObservedAt: String(row.last_observed_at ?? ''),
      })).filter(row => row.campaignId.length > 0),
    }
  } catch {
    return { state: 'unavailable', items: [] }
  }
}

/**
 * Create a draft (ADR-0221 D1 step 6).
 *
 * The body is passed through as the service defines it, but nothing about the author is: the
 * campaign's `createdBy` is taken from the token server-side, so the maker recorded on a campaign
 * is the person who actually created it and not a name the request chose.
 */
export async function POST(req: Request) {
  const session = await auth()
  if (!session?.user?.accessToken) {
    return NextResponse.json({ error: 'unauthenticated' }, { status: 401 })
  }
  try {
    const res = await fetch(serverSvcUrl('campaign-service', 'campaign', 8128, '/api/v1/campaigns'), {
      method: 'POST',
      headers: {
        authorization: `Bearer ${session.user.accessToken}`,
        'content-type': 'application/json',
      },
      body: JSON.stringify(await req.json()),
      signal: AbortSignal.timeout(8000),
      cache: 'no-store',
    })
    const text = await res.text()
    const payload = text ? JSON.parse(text) : {}
    if (!res.ok) {
      // The service rejects a malformed step by construction — an unknown template, an undeclared
      // variable, a missing one. That message is what tells an author what to change, so it travels
      // rather than being flattened into "create failed".
      return NextResponse.json(
        { state: res.status === 401 || res.status === 403 ? 'forbidden' : 'rejected', ...payload },
        { status: 200 },
      )
    }
    return NextResponse.json({ state: 'ok', campaign: payload })
  } catch {
    return NextResponse.json({ state: 'unreachable' }, { status: 200 })
  }
}

export async function GET() {
  const session = await auth()
  if (!session?.user?.accessToken) {
    return NextResponse.json({ error: 'unauthenticated' }, { status: 401 })
  }
  const headers = { authorization: `Bearer ${session.user.accessToken}` }
  try {
    const res = await fetch(serverSvcUrl('campaign-service', 'campaign', 8128, '/api/v1/campaigns'), {
      headers,
      signal: AbortSignal.timeout(4000),
      cache: 'no-store',
    })
    if (!res.ok) {
      // A refused or failed read must not render as "no campaigns" — that reads as a quiet
      // estate, which is the wrong conclusion to draw from an authorization error.
      return NextResponse.json(
        { items: [], state: res.status === 401 || res.status === 403
            ? 'unauthorized'
            // 404 from the BFF proxy means the service key resolved to nothing — "not deployed",
            // NOT "deployed but silent". Collapsing the two sends whoever debugs it to look at a
            // healthy pod, which is exactly what happened when campaign-service was missing from
            // SERVICE_MAP (#2997).
            : res.status === 404
              ? 'not_deployed'
              : 'unreachable' },
        { status: 200 },
      )
    }
    // Reach and delivery (#3296). Fetched alongside the list rather than folded into it: the
    // endpoint is NEWER than the deployed service in any environment that has not rolled forward
    // yet, so a 404 here has to degrade to "no reach data" and leave the list intact. Coupling the
    // two would take the whole page down for a feature that is additive.
    const [summary, engagement] = await Promise.all([
      fetch(
        serverSvcUrl('campaign-service', 'campaign', 8128, '/api/v1/campaigns/summary'),
        { headers, signal: AbortSignal.timeout(4000), cache: 'no-store' },
      ).then(r => (r.ok ? r.json() : null)).catch(() => null),
      readCampaignEngagement(),
    ])

    return NextResponse.json({ items: await res.json(), state: 'ok', summary, engagement })
  } catch {
    return NextResponse.json({ items: [], state: 'unreachable' }, { status: 200 })
  }
}
