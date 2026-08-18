// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Docs BFF JSON surface (ADR-0056). The browser never calls GitHub directly; it
// asks admin-ui, which fetches release-please output server-side via
// @/lib/docs/releases (shared with the /docs/changelog|release-notes pages).
//   - changelog     → raw `<component>/CHANGELOG.md`
//   - release-notes → GitHub Releases whose tag matches the component
// Failures fail soft to an empty-but-linked payload, never a 404.
//
// Auth: this route is NOT in proxy.ts's public matcher exclusion, so the
// auth() middleware gates it — an unauthenticated request is redirected to
// login like any other admin page. We deliberately add no second in-route guard:
// the payload is public OSS release-please output (no cluster data), and a
// duplicate guard would only risk drifting from the middleware policy.

import { NextResponse } from 'next/server'
import { fetchChangelog, fetchReleaseNotes, SERVICE_RE } from '@/lib/docs/releases'

type RouteContext = { params: Promise<{ kind: string; service: string }> }

export async function GET(_req: Request, { params }: RouteContext) {
  const { kind, service } = await params

  if (!SERVICE_RE.test(service)) {
    return NextResponse.json({ error: 'invalid service id' }, { status: 400 })
  }

  const payload =
    kind === 'changelog'
      ? { kind, ...(await fetchChangelog(service)) }
      : kind === 'release-notes'
        ? { kind, ...(await fetchReleaseNotes(service)) }
        : null

  if (!payload) {
    return NextResponse.json({ error: `Unknown docs kind: ${kind}` }, { status: 404 })
  }

  return NextResponse.json(payload, {
    headers: { 'cache-control': 'private, max-age=300' },
  })
}
