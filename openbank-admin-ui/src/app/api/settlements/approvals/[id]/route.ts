// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { NextResponse } from 'next/server'
import { forwardOperatorApproval, readDecision } from '@/lib/approvals/operatorUpstream'

export const dynamic = 'force-dynamic'
type Context = { params: Promise<{ id: string }> }

export async function GET(_request: Request, { params }: Context) {
  return forwardOperatorApproval('settlement', (await params).id)
}

export async function PATCH(request: Request, { params }: Context) {
  const decision = await readDecision(request)
  if (decision === null) return NextResponse.json({ error: 'invalid_request' }, { status: 400 })
  return forwardOperatorApproval('settlement', (await params).id, decision)
}
