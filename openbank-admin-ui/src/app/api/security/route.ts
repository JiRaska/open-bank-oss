// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { NextResponse } from 'next/server'
import { promises as fs } from 'fs'
import path from 'path'
import { auth } from '@/auth'
import { inCluster, resolveInClusterService } from '@/lib/discovery'
import { classifySecurityReportFreshness, parseSecurityReportTimestamp } from '@/lib/security/reportFreshness'
import { parseSecurityEnvelope } from '@/lib/security/summary'

// ── Security posture: READ-ONLY consumer of CI-produced results ──────────────
//
// Why this route only has GET. Security scanning is a CI/CD-pipeline concern, not
// an operator-triggered action: industry practice (OWASP DevSecOps, SLSA, EBA ICT
// risk guidelines) is that SAST/DAST/dependency/secret scans run continuously in
// the pipeline (here: `.github/workflows/security.yml` — Trivy, CodeQL, Gitleaks)
// and the dashboard merely *displays* the latest verdict. Letting the browser
// POST `/scan` to launch a live fleet scan is an anti-pattern — it puts an
// expensive, privileged action behind an unauthenticated-by-default UI button and
// invites abuse. So this route NEVER triggers a scan; it serves the most recent
// report from one of two read-only sources, newest-wins:
//   1. a CI-produced report baked into the admin-ui image at build time
//      (OPENBANK_SECURITY_REPORT, mirrors the SBOM-bundle pattern), or
//   2. a read-only GET against a deployed scanner's `/report` (if configured).
//
// Contract (ADR-0056 graceful degradation): ALWAYS HTTP 200 with a typed envelope
//   { available: true,  report }                              — have a report
//   { available: false, reason: 'not_deployed'|'unreachable'|'error', detail? }
// so the page degrades through <DataUnavailable> and never leaks a raw status.

export const dynamic = 'force-dynamic'
export const revalidate = 0

type Unavailable = {
  available: false
  reason: 'not_deployed' | 'unreachable' | 'error' | 'unauthorized'
  detail?: string
}

// Resolve the security-scanner base URL:
//   1. In-cluster: Kubernetes Service DNS via discovery (authoritative, same pattern
//      as all other BFF server-side routes — ADR-0056). The scanner runs as
//      'security-scanner-service' in the 'security-scanner' namespace.
//   2. Explicit env override SECURITY_SCANNER_URL (local dev / off-cluster).
// We NEVER expose scanner URLs to the browser; this entire module is server-side.
async function scannerBase(): Promise<{ base: string | null; unknown: boolean }> {
  if (inCluster()) {
    const resolved = await resolveInClusterService('security-scanner-service')
    if (resolved.state === 'found') return { base: resolved.baseUrl, unknown: false }
    if (resolved.state === 'not_found') return { base: null, unknown: false }
    return { base: null, unknown: true }
  }
  return { base: process.env.SECURITY_SCANNER_URL ?? null, unknown: false }
}

function reportFile(): string {
  // Image-baked path produced by CI / the deploy build (security.yml → summary).
  return process.env.OPENBANK_SECURITY_REPORT
    ?? path.resolve(process.cwd(), 'security-report.json')
}

async function fromBundle(): Promise<NextResponse | null> {
  try {
    const raw = await fs.readFile(reportFile(), 'utf-8')
    const envelope = parseSecurityEnvelope({ available: true, report: JSON.parse(raw) })
    if (!envelope.available) return null
    // Return only validated fields, as for the scanner. A malformed bundle must
    // not outrank a usable live report merely because it declares a newer time.
    return NextResponse.json(envelope, { status: 200 })
  } catch {
    return null // no bundled report — try the live read-only source next
  }
}

async function fromScanner(): Promise<NextResponse | null> {
  const resolved = await scannerBase()
  const { base } = resolved
  if (!base) {
    if (!resolved.unknown) return null
    const payload: Unavailable = { available: false, reason: 'error', detail: 'Service discovery is incomplete; scanner location is unknown' }
    return NextResponse.json(payload, { status: 200 })
  }
  // security-scanner is OIDC-gated (quarkus.oidc + @RolesAllowed on its resources), so the
  // read needs the operator's Keycloak bearer relayed server-side — exactly like the generic
  // /api/svc proxy does. Without it the scanner answered 401 and this route surfaced
  // "scanner HTTP 401" on a healthy, deployed service.
  const accessToken = (await auth())?.user?.accessToken
  if (!accessToken) {
    const payload: Unavailable = { available: false, reason: 'unauthorized' }
    return NextResponse.json(payload, { status: 200 })
  }
  try {
    const res = await fetch(`${base}/api/v1/security/report`, {
      headers: { Authorization: `Bearer ${accessToken}`, Accept: 'application/json' },
      cache: 'no-store',
      signal: AbortSignal.timeout(10_000),
    })
    if (!res.ok) {
      // 404 from the scanner = deployed but no scan completed yet (in-memory store
      // is empty after pod restart; scheduler runs on 30-min cadence + 2-min initial
      // delay). Report as 'unreachable' so the page shows a neutral state rather
      // than the misleading "not deployed" message.
      // 401/403 = the relayed operator token was rejected (expired, or the role is missing).
      // That is a session state, not a scanner fault, so say so rather than printing a status.
      const reason: Unavailable['reason'] =
        res.status === 404 ? 'unreachable'
          : res.status === 401 || res.status === 403 ? 'unauthorized'
            : 'error'
      const detail = res.status === 404
        ? 'No scan completed yet — first scan runs 2 minutes after startup, then every 30 minutes'
        : reason === 'unauthorized' ? undefined
          : `scanner HTTP ${res.status}`
      const payload: Unavailable = { available: false, reason, detail }
      return NextResponse.json(payload, { status: 200 })
    }
    const body = await res.json().catch(() => null)
    let report: unknown
    try {
      const envelope = parseSecurityEnvelope({ available: true, report: body })
      if (!envelope.available) throw new Error('Invalid report')
      report = envelope.report
    } catch {
      const payload: Unavailable = { available: false, reason: 'error', detail: 'Invalid report from scanner' }
      return NextResponse.json(payload, { status: 200 })
    }
    return NextResponse.json({ available: true, report }, { status: 200 })
  } catch (e) {
    // fetch threw → scanner not reachable (not deployed / DNS / refused / timeout).
    const detail = e instanceof Error ? e.message : String(e)
    const payload: Unavailable = { available: false, reason: 'unreachable', detail }
    return NextResponse.json(payload, { status: 200 })
  }
}

export async function GET() {
  const [bundle, scanner] = await Promise.all([fromBundle(), fromScanner()])
  if (bundle && scanner) {
    const [bundleBody, scannerBody] = await Promise.all([
      bundle.clone().json().catch(() => null),
      scanner.clone().json().catch(() => null),
    ])
    // A scanner outage must not hide the last usable report. When both sources
    // provide reports, compare their source timestamps; request time is not data
    // freshness. If only one report has a parseable timestamp, prefer that one.
    const scannerStatus = scannerBody?.available === true
      ? 'available' as const
      : 'unavailable' as const
    if (scannerBody?.available === true && bundleBody?.available === true) {
      const bundleTime = parseSecurityReportTimestamp(bundleBody.report?.generatedAt)
      const scannerTime = parseSecurityReportTimestamp(scannerBody.report?.generatedAt)
      if (scannerTime !== null && (bundleTime === null || scannerTime > bundleTime)) {
        return withReportMetadata(scannerBody.report, 'scanner', scannerStatus)
      }
      if (bundleTime !== null) return withReportMetadata(bundleBody.report, 'ci-bundle', scannerStatus)
      return withReportMetadata(scannerBody.report, 'scanner', scannerStatus)
    }
    if (bundleBody?.available === true) return withReportMetadata(bundleBody.report, 'ci-bundle', scannerStatus)
    if (scannerBody?.available === true) return withReportMetadata(scannerBody.report, 'scanner', scannerStatus)
    return scanner
  }
  if (bundle) {
    const bundleBody = await bundle.clone().json().catch(() => null)
    if (bundleBody?.available === true) return withReportMetadata(bundleBody.report, 'ci-bundle', 'not-configured')
    return bundle
  }
  if (scanner) {
    const scannerBody = await scanner.clone().json().catch(() => null)
    if (scannerBody?.available === true) return withReportMetadata(scannerBody.report, 'scanner', 'available')
    return scanner
  }
  return NextResponse.json(
      { available: false, reason: 'not_deployed', detail: 'no CI security report bundled and no scanner configured' } satisfies Unavailable,
      { status: 200 },
    )
}

function withReportMetadata(
  report: unknown,
  source: 'scanner' | 'ci-bundle',
  scannerStatus: 'available' | 'unavailable' | 'not-configured',
): NextResponse {
  const generatedAt = typeof report === 'object' && report !== null && 'generatedAt' in report
    ? report.generatedAt
    : undefined
  return NextResponse.json({
    available: true,
    report,
    source,
    scannerStatus,
    freshness: classifySecurityReportFreshness(generatedAt),
  }, { status: 200, headers: { 'Cache-Control': 'no-store' } })
}
