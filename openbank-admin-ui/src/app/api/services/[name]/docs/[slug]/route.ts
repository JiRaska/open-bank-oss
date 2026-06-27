// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { NextResponse } from 'next/server'
import { cookies } from 'next/headers'
import { loadDocsDocument } from '@/lib/services/docs'
import { LANG_COOKIE } from '@/lib/i18n/LanguageContext'

// Per-slug docs fetch. Language resolution: ?lang → cookie → 'en'.

export const dynamic = 'force-dynamic'
export const revalidate = 0

export async function GET(
  req: Request,
  ctx: { params: Promise<{ name: string; slug: string }> },
) {
  const { name, slug } = await ctx.params
  const url = new URL(req.url)
  const langQuery = url.searchParams.get('lang') ?? undefined
  const langCookie = (await cookies()).get(LANG_COOKIE)?.value
  const lang = langQuery ?? langCookie ?? 'en'

  const doc = await loadDocsDocument(name, slug, lang)
  if (!doc) {
    return NextResponse.json(
      { error: 'doc not available', service: name, slug, lang },
      { status: 404 },
    )
  }
  return new NextResponse(doc.markdown, {
    status: 200,
    headers: {
      'Content-Type': 'text/markdown; charset=utf-8',
      'Content-Language': doc.lang || 'any',
      'X-Docs-Source': doc.source,
      'X-Docs-Available-Languages': doc.availableLanguages.join(','),
      Vary: 'Cookie',
      'Cache-Control': doc.source === 'live' ? 'public, max-age=60' : 'no-store',
    },
  })
}
