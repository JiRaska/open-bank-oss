// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

import { NextResponse } from 'next/server'
import { cookies } from 'next/headers'
import { loadDocsIndex } from '@/lib/services/docs'
import { LANG_COOKIE } from '@/lib/i18n/LanguageContext'

// Docs-as-Service index proxy.
//   • runnable services → fetch live from /q/openbank/docs?lang=<lang>
//   • openbank-libs     → image-baked bundle
// Language resolution order: ?lang query → openbank-admin-lang cookie → 'en'.

export const dynamic = 'force-dynamic'
export const revalidate = 0

export async function GET(
  req: Request,
  ctx: { params: Promise<{ name: string }> },
) {
  const { name } = await ctx.params
  const url = new URL(req.url)
  const langQuery = url.searchParams.get('lang') ?? undefined
  const langCookie = (await cookies()).get(LANG_COOKIE)?.value
  const lang = langQuery ?? langCookie ?? 'en'

  const index = await loadDocsIndex(name, lang)
  if (!index) {
    return NextResponse.json(
      { error: 'docs not available', service: name },
      { status: 404 },
    )
  }
  return NextResponse.json(index, {
    headers: {
      'Cache-Control': index.source === 'live'
        ? 's-maxage=60, stale-while-revalidate=300'
        : 'no-store',
      Vary: 'Cookie',
    },
  })
}
