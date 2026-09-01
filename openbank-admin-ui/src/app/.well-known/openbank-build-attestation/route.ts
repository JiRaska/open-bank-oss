// SPDX-License-Identifier: Apache-2.0
// Deliberately public, minimal build identity for a credential-free synthetic monitor.
// It exposes neither an operator API nor configuration, dependency or deployment detail.
import { NextResponse } from 'next/server'

export const dynamic = 'force-dynamic'
export const revalidate = 0

export async function GET() {
  const gitSha = process.env.BUILD_GIT_SHA ?? process.env.NEXT_PUBLIC_BUILD_GIT_SHA ?? 'unknown'
  return NextResponse.json({ component: 'admin-ui', gitSha }, {
    headers: { 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff' },
  })
}
