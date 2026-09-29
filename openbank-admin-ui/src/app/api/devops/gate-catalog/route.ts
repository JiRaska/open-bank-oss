// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import { readFile } from 'node:fs/promises'
import path from 'node:path'
import { NextResponse } from 'next/server'

export const dynamic = 'force-dynamic'

export async function GET() {
  try {
    const file = process.env.OPENBANK_GATE_CATALOG ?? path.resolve(process.cwd(), 'gate-catalog.json')
    return NextResponse.json(JSON.parse(await readFile(file, 'utf8')))
  } catch {
    return NextResponse.json({ available: false, reason: 'Gate catalog was not generated for this build' }, { status: 503 })
  }
}
