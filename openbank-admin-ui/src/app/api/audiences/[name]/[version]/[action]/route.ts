// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import { NextRequest, NextResponse } from 'next/server'
import { auth } from '@/auth'
import { serverSvcUrl } from '@/lib/services/bff'

export const dynamic = 'force-dynamic'

type Context = { params: Promise<{ name: string; version: string; action: string }> }
const supportedActions = ['preview', 'submit', 'approve'] as const
type SupportedAction = (typeof supportedActions)[number]

function isSupportedAction(action: string): action is SupportedAction {
  return supportedActions.includes(action as SupportedAction)
}

async function forward(request: NextRequest, context: Context) {
  const session = await auth()
  if (!session?.user?.accessToken) return NextResponse.json({ error: 'unauthenticated' }, { status: 401 })
  const { name, version, action } = await context.params
  if (!isSupportedAction(action)) return NextResponse.json({ error: 'unknown action' }, { status: 404 })
  const response = await fetch(serverSvcUrl('campaign-service', 'campaign', 8128, `/api/v1/audiences/${encodeURIComponent(name)}/${encodeURIComponent(version)}/${action}`), {
    method: request.method, headers: { authorization: `Bearer ${session.user.accessToken}` }, signal: AbortSignal.timeout(5000), cache: 'no-store',
  })
  return NextResponse.json(await response.json(), { status: response.status })
}

export async function GET(request: NextRequest, context: Context) { return forward(request, context) }
export async function POST(request: NextRequest, context: Context) { return forward(request, context) }
