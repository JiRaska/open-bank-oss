// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Campaign detail for the read-only console (#2895): the campaign, its enrolments, and its send
// log in one response, because the three are only meaningful together — an enrolment says a party
// is in the campaign, and only the send log says whether anything reached them and why not.

import { NextResponse } from 'next/server'
import { auth } from '@/auth'
import { serverSvcUrl } from '@/lib/services/bff'
import { resolvePartyNames } from '@/lib/campaigns/party-names'

export const dynamic = 'force-dynamic'

type PartState = 'ok' | 'unauthorized' | 'not_deployed' | 'unreachable' | 'not_configured' | 'not_ready'
type Part = { data: unknown; state: PartState }

const EMPTY_SENDS = { items: [], total: 0, page: 0, size: 0 }

async function read(headers: HeadersInit, path: string, fallback: unknown): Promise<Part> {
  try {
    const res = await fetch(serverSvcUrl('campaign-service', 'campaign', 8128, path), {
      headers,
      signal: AbortSignal.timeout(4000),
      cache: 'no-store',
    })
    if (!res.ok) {
      return {
        data: fallback,
        state: res.status === 401 || res.status === 403
            ? 'unauthorized'
            // 404 from the BFF proxy means the service key resolved to nothing — "not deployed",
            // NOT "deployed but silent". Collapsing the two sends whoever debugs it to look at a
            // healthy pod, which is exactly what happened when campaign-service was missing from
            // SERVICE_MAP (#2997).
            : res.status === 404
              ? 'not_deployed'
              : 'unreachable',
      }
    }
    return { data: await res.json(), state: 'ok' }
  } catch {
    return { data: fallback, state: 'unreachable' }
  }
}

/**
 * The send log answers with a bare array plus pagination headers, so it needs its own reader — the
 * generic one above only sees the body, and a page without its total renders as "this is
 * everything" whether or not it is.
 */
async function readSends(headers: HeadersInit, id: string): Promise<Part> {
  try {
    const res = await fetch(
      serverSvcUrl('campaign-service', 'campaign', 8128, `/api/v1/campaigns/${encodeURIComponent(id)}/sends?page=0&size=50`),
      { headers, signal: AbortSignal.timeout(4000), cache: 'no-store' },
    )
    if (!res.ok) {
      return {
        data: EMPTY_SENDS,
        state: res.status === 401 || res.status === 403 ? 'unauthorized' : res.status === 404 ? 'not_deployed' : 'unreachable',
      }
    }
    return {
      data: {
        items: await res.json(),
        total: Number(res.headers.get('x-total-count') ?? 0),
        page: Number(res.headers.get('x-page') ?? 0),
        size: Number(res.headers.get('x-page-size') ?? 0),
      },
      state: 'ok',
    }
  } catch {
    return { data: EMPTY_SENDS, state: 'unreachable' }
  }
}

/** A 409 is an honest "not configured", not an unavailable experiment panel. */
async function readExperiment(headers: HeadersInit, id: string): Promise<Part> {
  try {
    const res = await fetch(
      serverSvcUrl('campaign-service', 'campaign', 8128, `/api/v1/campaigns/${encodeURIComponent(id)}/experiment`),
      { headers, signal: AbortSignal.timeout(4000), cache: 'no-store' },
    )
    if (res.status === 409) return { data: null, state: 'not_configured' }
    if (!res.ok) {
      return {
        data: null,
        state: res.status === 401 || res.status === 403 ? 'unauthorized' : res.status === 404 ? 'not_deployed' : 'unreachable',
      }
    }
    return { data: await res.json(), state: 'ok' }
  } catch {
    return { data: null, state: 'unreachable' }
  }
}

/** A 409 is the honest absent A/B configuration, distinct from a degraded campaign-service. */
async function readContentExperiment(headers: HeadersInit, id: string): Promise<Part> {
  try {
    const res = await fetch(
      serverSvcUrl('campaign-service', 'campaign', 8128, `/api/v1/campaigns/${encodeURIComponent(id)}/content-experiment`),
      { headers, signal: AbortSignal.timeout(4000), cache: 'no-store' },
    )
    if (res.status === 409) return { data: null, state: 'not_configured' }
    if (!res.ok) {
      return {
        data: null,
        state: res.status === 401 || res.status === 403 ? 'unauthorized' : res.status === 404 ? 'not_deployed' : 'unreachable',
      }
    }
    return { data: await res.json(), state: 'ok' }
  } catch {
    return { data: null, state: 'unreachable' }
  }
}

/** A 503 here means the separately activated Kafka projection has not caught up, never zero. */
async function readIncentives(headers: HeadersInit, id: string): Promise<Part> {
  try {
    const res = await fetch(
      serverSvcUrl('campaign-service', 'campaign', 8128, `/api/v1/campaigns/${encodeURIComponent(id)}/incentives`),
      { headers, signal: AbortSignal.timeout(4000), cache: 'no-store' },
    )
    if (res.status === 503) return { data: null, state: 'not_ready' }
    if (!res.ok) {
      return {
        data: null,
        state: res.status === 401 || res.status === 403 ? 'unauthorized' : res.status === 404 ? 'not_deployed' : 'unreachable',
      }
    }
    return { data: await res.json(), state: 'ok' }
  } catch {
    return { data: null, state: 'unreachable' }
  }
}

export async function GET(_req: Request, ctx: { params: Promise<{ id: string }> }) {
  const session = await auth()
  if (!session?.user?.accessToken) {
    return NextResponse.json({ error: 'unauthenticated' }, { status: 401 })
  }
  const { id } = await ctx.params
  const headers = { authorization: `Bearer ${session.user.accessToken}` }

  const [campaign, enrolments, sends, sendSummary, journey, engagement, incentives, experiment] = await Promise.all([
    read(headers, `/api/v1/campaigns/${encodeURIComponent(id)}`, null),
    read(headers, `/api/v1/campaigns/${encodeURIComponent(id)}/enrolments`, []),
    // First page only. Paging and filtering go through /api/campaigns/[id]/sends so turning a
    // page does not re-read the campaign and its enrolments.
    readSends(headers, id),
    // Counts come from the service, not from the page above: a suppressed-total derived from the
    // rows on screen understates every campaign larger than one page, and that total is the number
    // an operator acts on.
    read(headers, `/api/v1/campaigns/${encodeURIComponent(id)}/sends/summary`, {}),
    // The journey funnel: per-step SQL aggregates. Bundled with the first paint because the flow is
    // the first thing on the screen, not something you scroll to.
    //
    // ORDER IS THE CONTRACT. This array is positional and the destructuring above names the
    // positions; adding a read in the middle silently hands every later name someone else's result.
    // That is how the journey slot came to hold the summary object: `funnel` was no longer an array,
    // and the screen died on `.map` with the 404 on /journey never surfacing, because its state had
    // landed under `sendSummary`.
    read(headers, `/api/v1/campaigns/${encodeURIComponent(id)}/journey`, []),
    // App attention is a separate, privacy-minimised projection.  It is never inferred from a
    // banner handoff, so a missing/lagging source remains visible as unavailable in Campaign Studio.
    read(headers, `/api/v1/campaigns/${encodeURIComponent(id)}/engagement`, []),
    // Authoritative reward outcomes are independent from attention. Keeping this positional read
    // adjacent to engagement makes the two funnels visible without ever equating a click to value.
    readIncentives(headers, id),
    readExperiment(headers, id),
  ])

  // Stable catalogue keys belong on the campaign record. Resolve the human label only for a
  // configured entry source, so an ordinary detail page does not pay two calls to explain nulls.
  const entry = campaign.data as {
    schedule?: unknown
    trigger?: unknown
    steps?: Array<{ variantBVariables?: unknown }>
  } | null
  const hasContentExperiment = entry?.steps?.some(step => step.variantBVariables != null) ?? false
  const [cadences, triggers, contentExperiment] = await Promise.all([
    entry?.schedule ? read(headers, '/api/v1/campaigns/cadences', []) : Promise.resolve({ data: [], state: 'ok' as PartState }),
    entry?.trigger ? read(headers, '/api/v1/campaigns/triggers', []) : Promise.resolve({ data: [], state: 'ok' as PartState }),
    hasContentExperiment ? readContentExperiment(headers, id) : Promise.resolve({ data: null, state: 'not_configured' as PartState }),
  ])

  // Names for every party on this screen, resolved once and deduplicated. Done here rather than in
  // the component so the client never fans out one request per row, and so an unresolved name
  // degrades to the id instead of blanking the screen (lib/campaigns/party-names).
  const enrolmentRows = (Array.isArray(enrolments.data) ? enrolments.data : []) as Array<{ partyId?: string }>
  // Array.isArray, not `?? []`: a degraded `sends` part can carry a non-array `items`, and `.map`
  // on it throws — the exact defect the bundle test exists for, reproduced here in new code.
  const rawSendItems = (sends.data as { items?: unknown } | null)?.items
  const sendRows = (Array.isArray(rawSendItems) ? rawSendItems : []) as Array<{ partyId?: string }>
  const partyNames = await resolvePartyNames(headers, [
    ...enrolmentRows.map(e => e.partyId ?? ''),
    ...sendRows.map(r => r.partyId ?? ''),
  ])

  return NextResponse.json({
    partyNames,
    campaign: campaign.data,
    enrolments: enrolments.data,
    sends: sends.data,
    sendSummary: sendSummary.data,
    journey: journey.data,
    engagement: engagement.data,
    incentives: incentives.data,
    experiment: experiment.data,
    contentExperiment: contentExperiment.data,
    entryCatalogues: { cadences: cadences.data, triggers: triggers.data },
    // Per-part state travels to the client: the send log is the part most likely to be
    // restricted, and an empty send log rendered as "nothing was suppressed" would be the
    // exact misreading this screen exists to prevent.
    sources: {
      campaign: campaign.state,
      enrolments: enrolments.state,
      sends: sends.state,
      sendSummary: sendSummary.state,
      journey: journey.state,
      engagement: engagement.state,
      incentives: incentives.state,
      experiment: experiment.state,
      contentExperiment: contentExperiment.state,
      cadences: cadences.state,
      triggers: triggers.state,
    },
  })
}

/**
 * Revise a definition before it enters four-eyes review.  The BFF deliberately forwards no maker
 * identity: campaign-service derives that from the access token and rejects anyone other than the
 * original maker, so a browser cannot turn an edit into an impersonation claim.
 */
export async function PUT(req: Request, ctx: { params: Promise<{ id: string }> }) {
  const session = await auth()
  if (!session?.user?.accessToken) {
    return NextResponse.json({ error: 'unauthenticated' }, { status: 401 })
  }
  const { id } = await ctx.params
  try {
    const raw = await req.json() as Record<string, unknown>
    // These fields are server-owned.  Dropping them at the BFF keeps an accidental stale detail
    // payload from looking like an editable audit record; campaign-service independently derives
    // and verifies the maker from the token.
    const { id: _id, state: _state, createdBy: _createdBy, approvedBy: _approvedBy, createdAt: _createdAt, updatedAt: _updatedAt, ...draft } = raw
    const res = await fetch(serverSvcUrl('campaign-service', 'campaign', 8128, `/api/v1/campaigns/${encodeURIComponent(id)}`), {
      method: 'PUT',
      headers: { authorization: `Bearer ${session.user.accessToken}`, 'content-type': 'application/json' },
      body: JSON.stringify(draft),
      signal: AbortSignal.timeout(8000),
      cache: 'no-store',
    })
    const text = await res.text()
    const payload = text ? JSON.parse(text) : {}
    return NextResponse.json(
      res.ok ? { state: 'ok', campaign: payload } : { state: res.status === 401 || res.status === 403 ? 'forbidden' : 'rejected', ...payload },
      { status: 200 },
    )
  } catch {
    return NextResponse.json({ state: 'unreachable' }, { status: 200 })
  }
}
