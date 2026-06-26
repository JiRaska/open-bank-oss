// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

import { NextResponse } from 'next/server'

// Exposes admin-ui build metadata stamped into the image at build time.
// Source: ARG BUILD_VERSION / BUILD_GIT_SHA / BUILD_DATE from Dockerfile,
// fed by docker-compose build args (ADMIN_UI_VERSION / _GIT_SHA / _DATE).
// Counterpart to the per-service /q/openbank/build-info endpoint produced
// by openbank-libs BuildInfo (singleton stamped from libs.versions.toml).
export const dynamic = 'force-dynamic'
export const revalidate = 0

export async function GET() {
  return NextResponse.json(
    {
      component: 'admin-ui',
      version: process.env.BUILD_VERSION ?? process.env.NEXT_PUBLIC_BUILD_VERSION ?? 'dev',
      gitSha: process.env.BUILD_GIT_SHA ?? process.env.NEXT_PUBLIC_BUILD_GIT_SHA ?? 'unknown',
      buildDate: process.env.BUILD_DATE ?? process.env.NEXT_PUBLIC_BUILD_DATE ?? 'unknown',
      node: process.versions.node,
      next: '14.2.5',
    },
    { headers: { 'Cache-Control': 'no-store' } },
  )
}
