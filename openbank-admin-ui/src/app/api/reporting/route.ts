// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Reporting catalogue BFF — ADR-0286 (issue #8943).
//
// Returns the registry's PUBLIC metadata (id, titles, description, parameter schema, columns) so
// the /reporting page can render a selector and a parameter form without importing the registry
// into the browser bundle. The SQL builders stay server-side by construction: they are not part
// of this payload, and the registry module is never imported from a client component.
//
// The catalogue is NOT permission-filtered here: the page hides nothing that the BFF would not
// re-check anyway (each entry's permission is enforced on /api/reporting/[queryId]), and showing
// a locked report with its scope is more honest than an absent one.

import { NextResponse } from 'next/server'
import { requireApiPermission } from '@/lib/auth/api-permission'
import { REPORT_REGISTRY } from '@/lib/reporting/registry'

export const dynamic = 'force-dynamic'

export async function GET() {
  // The catalogue page shell sits under compliance:view; the index mirrors that. Individual
  // reports enforce their own (possibly narrower) permission on execution.
  const access = await requireApiPermission('compliance:view')
  if (!access.ok) {
    return NextResponse.json({ error: access.error }, { status: access.status })
  }

  return NextResponse.json(
    {
      reports: REPORT_REGISTRY.map((e) => ({
        id: e.id,
        titleCs: e.titleCs,
        titleEn: e.titleEn,
        descriptionCs: e.descriptionCs,
        descriptionEn: e.descriptionEn,
        permission: e.permission,
        params: e.params,
        columns: e.columns,
      })),
    },
    { headers: { 'Cache-Control': 'no-store' } },
  )
}
