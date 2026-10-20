// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import { NextResponse } from 'next/server'
import { loadComplianceCatalog } from '@/lib/governance/compliance'

export const dynamic = 'force-dynamic'
export const revalidate = 0

export async function GET() {
  const catalog = await loadComplianceCatalog()
  return NextResponse.json(
    catalog ? { status: 'available', ...catalog } : { status: 'unavailable', controls: [], frameworks: [], coverage: {}, posture: null },
    { headers: { 'Cache-Control': 'no-store, max-age=0' } },
  )
}
