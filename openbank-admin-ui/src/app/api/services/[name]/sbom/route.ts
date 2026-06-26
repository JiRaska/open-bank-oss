// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

import { NextResponse } from 'next/server'
import { promises as fs } from 'fs'
import path from 'path'

// Serves the per-service CycloneDX SBOM baked into the admin-ui image at
// build time (Dockerfile sbom-collector stage). NO host filesystem mount —
// SBOMs are an immutable artifact, version-locked with whatever the local
// `./gradlew sbomAll` produced when the image was built.
//
// Two response modes:
//   - default (`Accept: application/vnd.cyclonedx+json` or no ?summary param):
//     returns the raw CycloneDX JSON as a downloadable attachment.
//   - `?summary=true`: parses the SBOM and returns a compact JSON summary
//     suitable for inline rendering (component count, licenses, top deps,
//     PURL-derived ecosystem breakdown). The expand button in Tech Inventory
//     calls this mode.
//
// For services not yet built with cyclonedxBom we return 404 with a hint —
// the UI shows a friendly "SBOM not generated" state rather than a hard error.

const SAFE_NAME_RE = /^[a-z][a-z0-9-]{2,40}$/

export const dynamic = 'force-dynamic'
export const revalidate = 0

function sbomFile(serviceDir: string): string {
  const bundleRoot = process.env.OPENBANK_SBOM_BUNDLE
    ?? process.env.OPENBANK_REPO_ROOT
    ?? path.resolve(process.cwd(), '..')
  // Image-baked path: /app/sbom-bundle/openbank-<name>/bom.json
  // Fallback (host fs / dev mode): <repo>/openbank-<name>/build/reports/bom.json
  return process.env.OPENBANK_SBOM_BUNDLE
    ? path.join(bundleRoot, serviceDir, 'bom.json')
    : path.join(bundleRoot, serviceDir, 'build', 'reports', 'bom.json')
}

interface CycloneDxComponent {
  type?: string
  name?: string
  group?: string
  version?: string
  purl?: string
  scope?: string
  licenses?: Array<{ license?: { id?: string; name?: string } }>
}

// CycloneDX `metadata.tools` was an array of `{vendor,name,version}` in spec
// versions 1.0–1.4. In 1.5 it became either an object with a `components` array
// (or `services` array), or stayed as the old array form for backward compat.
// We accept both shapes and normalise in summarise().
type CycloneDxTool = { vendor?: string; author?: string; name?: string; version?: string }
type CycloneDxTools =
  | CycloneDxTool[]                                          // pre-1.5 shape
  | { components?: CycloneDxTool[]; services?: CycloneDxTool[] }  // 1.5+ shape

interface CycloneDxSbom {
  bomFormat?: string
  specVersion?: string
  serialNumber?: string
  version?: number
  metadata?: {
    timestamp?: string
    tools?: CycloneDxTools
    component?: CycloneDxComponent
  }
  components?: CycloneDxComponent[]
}

interface SbomSummary {
  service: string
  generatedAt: string | null
  generator: string | null
  specVersion: string | null
  rootComponent: {
    name: string | null
    version: string | null
    purl: string | null
  }
  totals: {
    components: number
    direct: number
    transitive: number
    withLicense: number
    withoutLicense: number
  }
  licenses: Array<{ license: string; count: number }>
  ecosystems: Array<{ ecosystem: string; count: number }>
  topComponents: Array<{
    group: string | null
    name: string
    version: string | null
    purl: string | null
    licenses: string[]
    scope: string | null
  }>
}

function summarise(sbom: CycloneDxSbom, serviceName: string): SbomSummary {
  const components = sbom.components ?? []
  const licenseCounts = new Map<string, number>()
  const ecoCounts = new Map<string, number>()
  let direct = 0
  let withLicense = 0

  for (const c of components) {
    const lics = (c.licenses ?? [])
      .map(l => l.license?.id ?? l.license?.name)
      .filter((x): x is string => Boolean(x))
    if (lics.length > 0) {
      withLicense++
      for (const l of lics) licenseCounts.set(l, (licenseCounts.get(l) ?? 0) + 1)
    }
    if (!c.scope || c.scope === 'required') direct++
    const eco = c.purl ? extractEcosystem(c.purl) : 'unknown'
    ecoCounts.set(eco, (ecoCounts.get(eco) ?? 0) + 1)
  }

  // Top components — alphabetical by group:name. Capped to 200 for UI sanity;
  // the full list is always available via the download endpoint.
  const top = components
    .slice()
    .sort((a, b) => {
      const ak = `${a.group ?? ''}:${a.name ?? ''}`.toLowerCase()
      const bk = `${b.group ?? ''}:${b.name ?? ''}`.toLowerCase()
      return ak.localeCompare(bk)
    })
    .slice(0, 200)
    .map(c => ({
      group: c.group ?? null,
      name: c.name ?? '(unnamed)',
      version: c.version ?? null,
      purl: c.purl ?? null,
      licenses: (c.licenses ?? []).map(l => l.license?.id ?? l.license?.name ?? '').filter(Boolean),
      scope: c.scope ?? null,
    }))

  const tools = sbom.metadata?.tools
  const firstTool: CycloneDxTool | undefined = Array.isArray(tools)
    ? tools[0]
    : tools?.components?.[0] ?? tools?.services?.[0]
  return {
    service: serviceName,
    generatedAt: sbom.metadata?.timestamp ?? null,
    generator: firstTool
      ? `${firstTool.vendor ?? firstTool.author ?? ''} ${firstTool.name ?? ''} ${firstTool.version ?? ''}`.trim()
      : null,
    specVersion: sbom.specVersion ?? null,
    rootComponent: {
      name: sbom.metadata?.component?.name ?? null,
      version: sbom.metadata?.component?.version ?? null,
      purl: sbom.metadata?.component?.purl ?? null,
    },
    totals: {
      components: components.length,
      direct,
      transitive: components.length - direct,
      withLicense,
      withoutLicense: components.length - withLicense,
    },
    licenses: [...licenseCounts.entries()]
      .sort((a, b) => b[1] - a[1])
      .map(([license, count]) => ({ license, count })),
    ecosystems: [...ecoCounts.entries()]
      .sort((a, b) => b[1] - a[1])
      .map(([ecosystem, count]) => ({ ecosystem, count })),
    topComponents: top,
  }
}

/** Extract the ecosystem segment from a `pkg:<type>/<group>/<name>@<ver>` PURL. */
function extractEcosystem(purl: string): string {
  const m = /^pkg:([a-z]+)/.exec(purl)
  return m?.[1] ?? 'unknown'
}

async function loadSbom(name: string): Promise<{ raw: string; parsed: CycloneDxSbom } | null> {
  const candidates = [
    sbomFile(`openbank-${name}-service`),
    sbomFile(`openbank-${name}`),
    sbomFile(name),
  ]
  for (const file of candidates) {
    try {
      const stat = await fs.stat(file)
      if (!stat.isFile()) continue
      const raw = await fs.readFile(file, 'utf-8')
      return { raw, parsed: JSON.parse(raw) as CycloneDxSbom }
    } catch {
      // try next
    }
  }
  return null
}

export async function GET(
  req: Request,
  ctx: { params: Promise<{ name: string }> }
) {
  const { name } = await ctx.params
  if (!SAFE_NAME_RE.test(name)) {
    return NextResponse.json({ error: 'invalid service name' }, { status: 400 })
  }

  const url = new URL(req.url)
  const wantSummary = url.searchParams.get('summary') === 'true'

  const loaded = await loadSbom(name)
  if (!loaded) {
    return NextResponse.json({
      error: 'SBOM not found',
      hint: `Run ./gradlew :openbank-${name}-service:cyclonedxBom (or ./gradlew sbomAll) and rebuild admin-ui.`,
    }, { status: 404 })
  }

  if (wantSummary) {
    return NextResponse.json(summarise(loaded.parsed, name), {
      headers: { 'Cache-Control': 'public, max-age=300' },
    })
  }

  return new NextResponse(loaded.raw, {
    status: 200,
    headers: {
      'Content-Type': 'application/vnd.cyclonedx+json',
      'Content-Disposition': `attachment; filename="openbank-${name}-sbom.cdx.json"`,
      'Cache-Control': 'public, max-age=300',
    },
  })
}
