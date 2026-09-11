// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Pure security-report aggregation, extracted from the Security Scanner page so
// the "unreachable service is not a vulnerability" rule is unit-testable rather
// than living only in JSX. An unreachable service (typically scaled to zero) has
// NO known findings — counting it as a failure, or letting its upstream F-grade
// drag the platform score, is a false positive. These helpers compute the
// headline numbers over REACHABLE services only and surface the unreachable ones
// as a coverage gap.

export const SECURITY_SEVERITIES = ['CRITICAL', 'HIGH', 'MEDIUM', 'LOW', 'INFO'] as const
export const SECURITY_GRADES = ['A+', 'A', 'B', 'C', 'D', 'F'] as const
export const OWASP_CATEGORIES = [
  'A01_BROKEN_ACCESS_CONTROL', 'A02_CRYPTOGRAPHIC_FAILURES', 'A03_INJECTION',
  'A04_INSECURE_DESIGN', 'A05_SECURITY_MISCONFIGURATION', 'A06_VULNERABLE_COMPONENTS',
  'A07_AUTH_FAILURES', 'A08_SOFTWARE_INTEGRITY_FAILURES', 'A09_LOGGING_MONITORING_FAILURES', 'A10_SSRF',
] as const

export interface SecurityFinding {
  id: string
  category: typeof OWASP_CATEGORIES[number]
  severity: typeof SECURITY_SEVERITIES[number]
  title: string
  description: string
  remediation: string
  cweId?: string
  cvssScore?: number
  endpoint?: string
}

export interface ScanFindingLike { severity: string }
export interface ScanResultLike {
  reachable: boolean
  score: number
  grade: string
  findings: ScanFindingLike[]
}

export interface ServiceScanResult extends ScanResultLike {
  serviceName: string
  serviceUrl: string
  scannedAt: string
  findings: SecurityFinding[]
  durationMs: number
  headersPresent: Record<string, boolean>
  openApiAvailable: boolean
}

export interface PlatformSecurityReport {
  reportId: string
  generatedAt: string
  totalServices: number
  reachableServices: number
  serviceResults: ServiceScanResult[]
  platformScore: number
  platformGrade: typeof SECURITY_GRADES[number]
  criticalFindings: number
  highFindings: number
  owaspCoverage: Record<string, number>
  complianceStatus: Record<string, boolean>
}

export type SecurityEnvelope =
  | { available: true; report: PlatformSecurityReport }
  | { available: false; reason: 'not_deployed' | 'unreachable' | 'error' | 'unauthorized'; detail?: string }

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i
const RFC3339 = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?(?:Z|[+-]\d{2}:\d{2})$/
const SERVICE_NAME = /^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$/

function object(value: unknown): Record<string, unknown> {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error('Expected object')
  return value as Record<string, unknown>
}

function finiteInteger(value: unknown, min: number, max: number): number {
  if (!Number.isInteger(value) || (value as number) < min || (value as number) > max) throw new Error('Invalid integer')
  return value as number
}

function string(value: unknown, max: number, allowEmpty = false): string {
  if (typeof value !== 'string' || (!allowEmpty && value.trim() === '') || value.length > max) throw new Error('Invalid text')
  return value
}

function enumValue<const T extends readonly string[]>(value: unknown, allowed: T): T[number] {
  if (typeof value !== 'string' || !allowed.includes(value)) throw new Error('Invalid enum')
  return value as T[number]
}

function instant(value: unknown): string {
  if (typeof value !== 'string' || !RFC3339.test(value) || !Number.isFinite(Date.parse(value))) throw new Error('Invalid timestamp')
  return value
}

function booleanRecord(value: unknown): Record<string, boolean> {
  const source = object(value)
  if (Object.keys(source).length > 100 || Object.values(source).some(item => typeof item !== 'boolean')) throw new Error('Invalid boolean map')
  return source as Record<string, boolean>
}

function finding(value: unknown): SecurityFinding {
  const item = object(value)
  const nullableText = (candidate: unknown, max: number): string | undefined => {
    if (candidate == null) return undefined
    return string(candidate, max)
  }
  if (item.cvssScore != null && (typeof item.cvssScore !== 'number' || !Number.isFinite(item.cvssScore) || item.cvssScore < 0 || item.cvssScore > 10)) {
    throw new Error('Invalid CVSS score')
  }
  return {
    id: string(item.id, 200),
    category: enumValue(item.category, OWASP_CATEGORIES),
    severity: enumValue(item.severity, SECURITY_SEVERITIES),
    title: string(item.title, 2_000),
    description: string(item.description, 20_000, true),
    remediation: string(item.remediation, 20_000, true),
    cweId: nullableText(item.cweId, 100),
    cvssScore: item.cvssScore as number | undefined,
    endpoint: nullableText(item.endpoint, 4_000),
  }
}

function serviceResult(value: unknown): ServiceScanResult {
  const item = object(value)
  if (typeof item.reachable !== 'boolean' || typeof item.openApiAvailable !== 'boolean') throw new Error('Invalid service flags')
  const serviceUrl = string(item.serviceUrl, 4_000)
  try {
    const parsed = new URL(serviceUrl)
    if (parsed.protocol !== 'http:' && parsed.protocol !== 'https:') throw new Error('Invalid service URL')
  } catch {
    throw new Error('Invalid service URL')
  }
  if (!Array.isArray(item.findings) || item.findings.length > 10_000) throw new Error('Invalid findings')
  const findings = item.findings.map(finding)
  if (new Set(findings.map(entry => entry.id)).size !== findings.length) throw new Error('Duplicate finding id')
  const score = finiteInteger(item.score, 0, 100)
  const grade = enumValue(item.grade, SECURITY_GRADES)
  if (gradeFromScore(score) !== grade) throw new Error('Contradictory service grade')
  return {
    serviceName: string(item.serviceName, 64),
    serviceUrl,
    scannedAt: instant(item.scannedAt),
    durationMs: finiteInteger(item.durationMs, 0, Number.MAX_SAFE_INTEGER),
    reachable: item.reachable,
    findings,
    score,
    grade,
    headersPresent: booleanRecord(item.headersPresent),
    openApiAvailable: item.openApiAvailable,
  }
}

export function parseSecurityEnvelope(value: unknown): SecurityEnvelope {
  const envelope = object(value)
  if (envelope.available === false) {
    const reason = enumValue(envelope.reason, ['not_deployed', 'unreachable', 'error', 'unauthorized'] as const)
    return { available: false, reason, detail: envelope.detail == null ? undefined : string(envelope.detail, 2_000, true) }
  }
  if (envelope.available !== true) throw new Error('Invalid security envelope')
  const raw = object(envelope.report)
  if (!Array.isArray(raw.serviceResults) || raw.serviceResults.length > 1_000) throw new Error('Invalid service results')
  const serviceResults = raw.serviceResults.map(serviceResult)
  if (new Set(serviceResults.map(item => item.serviceName)).size !== serviceResults.length) throw new Error('Duplicate service result')
  if (serviceResults.some(item => !SERVICE_NAME.test(item.serviceName))) throw new Error('Invalid service name')
  const totalServices = finiteInteger(raw.totalServices, 0, 1_000)
  const reachableServices = finiteInteger(raw.reachableServices, 0, totalServices)
  const criticalFindings = finiteInteger(raw.criticalFindings, 0, 10_000_000)
  const highFindings = finiteInteger(raw.highFindings, 0, 10_000_000)
  if (totalServices !== serviceResults.length || reachableServices !== serviceResults.filter(item => item.reachable).length) throw new Error('Contradictory service counts')
  const findings = serviceResults.flatMap(item => item.findings)
  if (criticalFindings !== findings.filter(item => item.severity === 'CRITICAL').length || highFindings !== findings.filter(item => item.severity === 'HIGH').length) {
    throw new Error('Contradictory finding counts')
  }
  const platformScore = finiteInteger(raw.platformScore, 0, 100)
  const platformGrade = enumValue(raw.platformGrade, SECURITY_GRADES)
  if (gradeFromScore(platformScore) !== platformGrade) throw new Error('Contradictory platform grade')
  const owaspCoverageRaw = object(raw.owaspCoverage)
  const owaspCoverage: Record<string, number> = {}
  for (const category of OWASP_CATEGORIES) {
    const count = finiteInteger(owaspCoverageRaw[category], 0, 10_000_000)
    if (count !== findings.filter(item => item.category === category).length) throw new Error('Contradictory OWASP count')
    owaspCoverage[category] = count
  }
  const knownOwaspCategories = new Set<string>(OWASP_CATEGORIES)
  if (Object.keys(owaspCoverageRaw).some(key => !knownOwaspCategories.has(key))) throw new Error('Unknown OWASP category')
  if (typeof raw.reportId !== 'string' || !UUID.test(raw.reportId)) throw new Error('Invalid report id')
  return { available: true, report: {
    reportId: raw.reportId,
    generatedAt: instant(raw.generatedAt), totalServices, reachableServices, serviceResults,
    platformScore, platformGrade, criticalFindings, highFindings, owaspCoverage,
    complianceStatus: booleanRecord(raw.complianceStatus),
  } }
}

// Standard letter-grade bands, used to derive the platform grade from the score
// recomputed over only the reachable services.
export function gradeFromScore(score: number): string {
  if (score >= 95) return 'A+'
  if (score >= 90) return 'A'
  if (score >= 80) return 'B'
  if (score >= 70) return 'C'
  if (score >= 60) return 'D'
  return 'F'
}

export interface ReachableSummary {
  reachableCount: number
  unreachableCount: number
  criticalCount: number
  highCount: number
  avgScore: number
  platformGrade: string
}

export function summarizeReachable(results: ScanResultLike[]): ReachableSummary {
  const reachable = results.filter(r => r.reachable)
  const countSeverity = (sev: string) =>
    reachable.reduce((n, r) => n + r.findings.filter(f => f.severity === sev).length, 0)
  const avgScore = reachable.length
    ? Math.round(reachable.reduce((s, r) => s + r.score, 0) / reachable.length)
    : 0
  return {
    reachableCount: reachable.length,
    unreachableCount: results.length - reachable.length,
    criticalCount: countSeverity('CRITICAL'),
    highCount: countSeverity('HIGH'),
    avgScore,
    platformGrade: reachable.length ? gradeFromScore(avgScore) : 'N/A',
  }
}

export type ServiceVerdict = 'not_scanned' | 'fail' | 'pass' | 'review'

// The per-row status pill. Unreachable → "not_scanned" (neutral), NEVER "fail" —
// that mis-mapping was the reported false positive.
export function serviceVerdict(r: { reachable: boolean; grade: string }): ServiceVerdict {
  if (!r.reachable) return 'not_scanned'
  if (['F', 'D', 'C'].includes(r.grade)) return 'fail'
  if (['A+', 'A'].includes(r.grade)) return 'pass'
  return 'review'
}
