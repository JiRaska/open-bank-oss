// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { NextResponse } from 'next/server'
import { auth } from '@/auth'

const BASE = process.env.PRODUCT_CATALOG_URL ?? 'http://openbank-product-catalog:8104'

/**
 * ADR-0056 bearer relay for the legacy product-catalog BFF routes.
 *
 * The browser session cookie authenticates the admin UI, but product-catalog
 * authorizes the operator from the OIDC bearer. Calling it anonymously made
 * every catalogue mutation fail at the backend and exposed arbitrary upstream
 * error bodies to the UI. Keep the relay here so the list, detail, fees and
 * lifecycle routes cannot drift apart again.
 */
export async function productCatalogUpstream(
  path: string,
  init: { method?: string; body?: string } = {},
): Promise<NextResponse> {
  const accessToken = (await auth())?.user?.accessToken
  if (!accessToken) return NextResponse.json({ error: 'unauthorized' }, { status: 401 })

  try {
    const headers = new Headers({ Accept: 'application/json', Authorization: `Bearer ${accessToken}` })
    if (init.body !== undefined) headers.set('Content-Type', 'application/json')
    const response = await fetch(`${BASE}${path}`, {
      method: init.method ?? 'GET',
      headers,
      body: init.body,
      cache: 'no-store',
      signal: AbortSignal.timeout(10_000),
    })

    if (!response.ok) {
      return NextResponse.json({ error: 'upstream_error' }, { status: response.status })
    }
    return NextResponse.json(await response.json(), {
      status: response.status,
      headers: { 'Cache-Control': 'no-store' },
    })
  } catch {
    return NextResponse.json({ error: 'upstream_unreachable' }, { status: 502 })
  }
}
