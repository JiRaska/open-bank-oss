// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// API-catalog OpenAPI surface (ADR-0056). Serves a service's OpenAPI document to
// the catalog page server-side, from the SPEC BAKED INTO THE IMAGE — never from
// the live service.
//
// Why not the live `/q/openapi` over the BFF: every Quarkus service exposes
// OpenAPI (and health/metrics) on its MANAGEMENT port (8085), while the BFF proxy
// forwards only to the HTTP port (8100) — and a sibling pod cannot reach the
// management port either. So a live fetch 404s for every deployed service, which
// is exactly why the catalog showed "0 endpoints".
//
// The committed `openbank-<svc>/src/main/resources/openapi.yaml` is the
// governance source of truth (CLAUDE.md rule #4: info.version == version.txt,
// enforced in CI). The Dockerfile bakes every such spec into
// /app/openapi-bundle/<folder>/openapi.yaml, so this route resolves the full API
// contract for ALL services — deployed or not — with no external GitHub
// dependency and no management-port problem. The image carries exactly the specs
// from the commit it was built from, so the catalog is auditable and
// self-consistent.

import { NextResponse } from 'next/server'
import { promises as fs } from 'fs'
import path from 'path'
import { parse as parseYaml } from 'yaml'

// k8s Deployment/Service name → monorepo folder. The bundle is keyed by folder
// (e.g. `account-service` → `openbank-account-service`, `product-catalog` →
// `openbank-product-catalog`, `sepa-payment` → `openbank-sepa-payment`).
const SAFE_SVC_RE = /^[a-z][a-z0-9-]{2,50}$/

function bundleRoot(): string {
  return process.env.OPENBANK_OPENAPI_BUNDLE ?? '/app/openapi-bundle'
}

/** Candidate spec paths, image bundle first, then a dev (off-cluster) checkout. */
function candidatePaths(folder: string): string[] {
  const repoRoot = process.env.OPENBANK_REPO_ROOT ?? path.resolve(process.cwd(), '..')
  return [
    path.join(bundleRoot(), folder, 'openapi.yaml'),
    path.join(repoRoot, folder, 'src', 'main', 'resources', 'openapi.yaml'),
  ]
}

type RouteContext = { params: Promise<{ service: string }> }

export async function GET(req: Request, { params }: RouteContext) {
  const { service } = await params
  if (!SAFE_SVC_RE.test(service)) {
    return NextResponse.json({ error: 'invalid service id' }, { status: 400 })
  }
  // `?format=yaml` streams the raw committed spec (so the "OpenAPI YAML" link is a
  // real, downloadable document); default parses YAML → JSON for the catalog page.
  const wantYaml = new URL(req.url).searchParams.get('format') === 'yaml'

  const folder = service.startsWith('openbank-') ? service : `openbank-${service}`

  let raw: string | null = null
  for (const p of candidatePaths(folder)) {
    try {
      raw = await fs.readFile(p, 'utf-8')
      break
    } catch {
      // try next candidate
    }
  }

  if (raw === null) {
    // Soft-fail with a stable JSON body so the page can degrade to a calm empty
    // state (the spec simply isn't bundled for this service) rather than a scary
    // error — mirrors the BFF's "not deployed" semantics.
    return NextResponse.json({ error: `No OpenAPI spec for service: ${service}` }, { status: 404 })
  }

  if (wantYaml) {
    return new NextResponse(raw, {
      headers: {
        'content-type': 'application/yaml; charset=utf-8',
        'cache-control': 'private, max-age=3600',
      },
    })
  }

  let doc: unknown
  try {
    doc = parseYaml(raw)
  } catch {
    return NextResponse.json({ error: 'spec parse error' }, { status: 502 })
  }

  return NextResponse.json(doc, {
    // Baked into an immutable image layer — safe to cache for the page's lifetime.
    headers: { 'cache-control': 'private, max-age=3600' },
  })
}
