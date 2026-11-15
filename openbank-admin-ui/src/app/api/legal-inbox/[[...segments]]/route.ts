// SPDX-License-Identifier: Apache-2.0

import { NextRequest, NextResponse } from 'next/server'
import { auth } from '@/auth'
import { hasPermission } from '@/lib/auth/roles'
import { serverSvcUrl } from '@/lib/services/bff'

export const dynamic = 'force-dynamic'

const idPattern = /^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/i
type Context = { params: Promise<{ segments?: string[] }> }

async function forward(req: NextRequest, { params }: Context) {
  const session = await auth()
  const token = session?.user?.error === 'RefreshAccessTokenError' ? undefined : session?.user?.accessToken
  if (!token) return NextResponse.json({ error: 'unauthorized' }, { status: 401 })
  if (!hasPermission(session?.user?.roles ?? [], 'legal-inbox:view')) {
    return NextResponse.json({ error: 'forbidden' }, { status: 403 })
  }

  const segments = (await params).segments ?? []
  const [id, operation, file] = segments
  const validId = !!id && idPattern.test(id)
  const post = req.method === 'POST'
  const original = !post && validId && segments.length === 3 && operation === 'originals' &&
    (file === 'message' || file === 'delivery')
  const list = !post && segments.length === 0
  const history = !post && segments.length === 1 && id === 'isds-runs'
  const detail = !post && validId && segments.length === 1
  const action = post && validId && segments.length === 2 && (operation === 'extract' || operation === 'assignment')
  if (!(list || history || detail || original || action)) return NextResponse.json({ error: 'not_found' }, { status: 404 })

  if (post && (req.headers.get('sec-fetch-site') === 'cross-site' ||
      req.headers.get('content-type')?.split(';')[0] !== 'application/json')) {
    return NextResponse.json({ error: 'invalid_request' }, { status: 400 })
  }
  const path = '/api/v1/legal-inbox' + (segments.length ? '/' + segments.join('/') : '')
  const offset = req.nextUrl.searchParams.get('offset') ?? '0'
  if (list && !/^\d{1,9}$/.test(offset)) {
    return NextResponse.json({ error: 'invalid_offset' }, { status: 400 })
  }
  const override = process.env.LEGAL_PROCESS_SERVICE_URL?.replace(/\/+$/, '')
  const query = list ? { offset } : undefined
  const url = override
    ? override + path + (list ? '?offset=' + offset : '')
    : serverSvcUrl('legal-process-service', 'legal-process', 8191, path, query)

  let body: { team: string; expectedRevision: number } | undefined
  if (post && operation === 'assignment') {
    const length = req.headers.get('content-length')
    if (!length || !/^\d{1,4}$/.test(length) || Number(length) > 1024) {
      return NextResponse.json({ error: 'invalid_request' }, { status: 400 })
    }
    try {
      const input = await req.json()
      if (!['ENFORCEMENT_CZ', 'LEGAL_REVIEW_CZ'].includes(input?.team) ||
          !Number.isSafeInteger(input?.expectedRevision) || input.expectedRevision < 0 ||
          input.expectedRevision > 2_147_483_647) {
        return NextResponse.json({ error: 'invalid_request' }, { status: 400 })
      }
      body = { team: input.team, expectedRevision: input.expectedRevision }
    } catch {
      return NextResponse.json({ error: 'invalid_request' }, { status: 400 })
    }
  }

  try {
    const upstream = await fetch(url, {
      method: req.method,
      headers: {
        Authorization: 'Bearer ' + token,
        ...(body === undefined ? {} : { 'Content-Type': 'application/json' }),
      },
      body: body === undefined ? undefined : JSON.stringify(body),
      redirect: 'error',
      cache: 'no-store',
      signal: AbortSignal.timeout(15_000),
    })
    const headers = new Headers({ 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff' })
    if (!upstream.ok) {
      return NextResponse.json({ error: 'legal_inbox_error' }, { status: upstream.status, headers })
    }
    if (original) {
      headers.set('Content-Type', 'application/octet-stream')
      headers.set('Content-Disposition', `attachment; filename="isds-${file}.zfo"`)
    } else {
      headers.set('Content-Type', 'application/json')
    }
    return new NextResponse(upstream.body, { status: upstream.status, headers })
  } catch {
    return NextResponse.json({ error: 'upstream_unreachable' }, { status: 502 })
  }
}

export const GET = forward
export const POST = forward
