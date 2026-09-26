// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
import { NextResponse } from 'next/server'
import { forwardScaApproval } from '@/lib/sca/upstream'

export const dynamic = 'force-dynamic'
type Context = { params: Promise<{ id: string }> }

export async function GET(_request: Request, { params }: Context) {
  return forwardScaApproval((await params).id)
}

export async function PATCH(request: Request, { params }: Context) {
  let body: unknown
  try { body = await request.json() } catch {
    return NextResponse.json({ error: 'invalid_request' }, { status: 400 })
  }
  if (!body || typeof body !== 'object' || !('approve' in body) || typeof body.approve !== 'boolean') {
    return NextResponse.json({ error: 'invalid_request' }, { status: 400 })
  }
  return forwardScaApproval((await params).id, body.approve)
}
