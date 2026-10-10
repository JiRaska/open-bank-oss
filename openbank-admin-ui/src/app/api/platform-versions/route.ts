// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { NextResponse } from 'next/server'
import { getPlatformView } from '@/lib/live-platform-versions'

export const dynamic = 'force-dynamic'

/** Merged LIVE (Prometheus) + DECLARED (gitops/terraform) platform versions, with drift status. */
export async function GET() {
  const view = await getPlatformView()
  return NextResponse.json(view, { headers: { 'Cache-Control': 'no-store' } })
}
