// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

/**
 * Security Excellence — jediný souhrnný pohled na bezpečnost celého ekosystému.
 *
 * Hub NAD existujícími specializovanými obrazovkami, ne náhrada: každý pilíř
 * (posture scan, ICT incidenty, fraud, AML, sankce, maker-checker schvalování,
 * audit, identita/KYC) si drží svou detailní stránku; tato obrazovka jen
 * agreguje jejich read-only signály do jednoho skóre a jednoho radarového
 * přehledu, s drill-through odkazy na detail.
 *
 * Degradace podle ADR-0056: doména, která neodpověděla, se vykreslí jako
 * explicitně nedostupná (nedostupná / nenasazená / bez oprávnění) — NIKDY
 * jako falešná nula nebo "vše OK". Skóre se počítá jen z domén, které
 * skutečně odpověděly.
 */

import { useCallback, useEffect, useMemo, useState } from 'react'
import Link from 'next/link'
import {
  Shield, ScanLine, AlertTriangle, ShieldAlert, AlertOctagon, ClipboardCheck,
  ScrollText, Fingerprint, RefreshCw, ArrowRight, Scale, Package,
} from 'lucide-react'
import { AuthGuard } from '@/components/auth/AuthGuard'
import { PageHeader } from '@/components/ui/PageHeader'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { svcUrl } from '@/lib/services/bff'

// ── Doménové stavy ───────────────────────────────────────────────────────────

type DomainStatus = 'ok' | 'degraded' | 'critical' | 'unavailable' | 'loading'

interface Domain {
  id: string
  nameCs: string
  nameEn: string
  descCs: string
  descEn: string
  href: string
  icon: React.ElementType
  status: DomainStatus
  /** 0–100; undefined u unavailable/loading */
  score?: number
  /** Klíčová metrika zobrazená na kartě (např. "3 otevřené incidenty"). */
  metricCs?: string
  metricEn?: string
  /** Důvod nedostupnosti (typed envelope). */
  unavailableReason?: string
}

const GRADE_COLORS: Record<string, string> = {
  'A+': '#059669', A: '#10b981', B: '#3b82f6', C: '#f59e0b', D: '#ef4444', F: '#991b1b',
}

function gradeFor(score: number): string {
  if (score >= 95) return 'A+'
  if (score >= 88) return 'A'
  if (score >= 75) return 'B'
  if (score >= 60) return 'C'
  if (score >= 40) return 'D'
  return 'F'
}

const STATUS_TONE: Record<DomainStatus, { color: string; bg: string }> = {
  ok:          { color: '#059669', bg: '#ecfdf5' },
  degraded:    { color: '#d97706', bg: '#fffbeb' },
  critical:    { color: '#b91c1c', bg: '#fef2f2' },
  unavailable: { color: 'var(--text-tertiary, #6b7280)', bg: 'var(--surface-3, #f3f4f6)' },
  loading:     { color: 'var(--text-tertiary, #6b7280)', bg: 'var(--surface-3, #f3f4f6)' },
}

// ── Fan-out fetchery — každý vrací partial update domény, nikdy nehodí ───────

interface SafeJsonResult {
  ok: boolean
  status: number
  body: unknown
}

async function safeJson(url: string): Promise<SafeJsonResult> {
  try {
    const res = await fetch(url, { cache: 'no-store', signal: AbortSignal.timeout(8000) })
    if (!res.ok) return { ok: false, status: res.status, body: null }
    return { ok: true, status: res.status, body: await res.json() }
  } catch {
    return { ok: false, status: 0, body: null }
  }
}

// Patch je definován až tady (za safeJson): typový alias končící `>>` těsně před
// generickou funkcí by se v i18n guard detektoru (`>…<` regex) přečetl jako
// JSX text — interface SafeJsonResult výše a toto pořadí tomu předchází.
type Patch = Partial<Pick<Domain, 'status' | 'score' | 'metricCs' | 'metricEn' | 'unavailableReason'>>

const unavailable = (reason: string): Patch => ({ status: 'unavailable', unavailableReason: reason })

async function fetchPosture(): Promise<Patch> {
  const { ok, body } = await safeJson('/api/security')
  if (!ok) return unavailable('unreachable')
  const env = body as { available?: boolean; reason?: string; report?: { platformScore: number; criticalFindings: number; highFindings: number; reachableServices: number; totalServices: number } }
  if (!env.available || !env.report) return unavailable(env.reason ?? 'not_deployed')
  const r = env.report
  return {
    status: r.criticalFindings > 0 ? 'critical' : r.highFindings > 0 ? 'degraded' : 'ok',
    score: r.platformScore,
    metricCs: `${r.criticalFindings} krit. / ${r.highFindings} vys. nálezů · ${r.reachableServices}/${r.totalServices} služeb`,
    metricEn: `${r.criticalFindings} crit. / ${r.highFindings} high findings · ${r.reachableServices}/${r.totalServices} services`,
  }
}

async function fetchIncidents(): Promise<Patch> {
  const { ok, body } = await safeJson('/api/security/incidents')
  if (!ok) return unavailable('unreachable')
  const env = body as { available?: boolean; reason?: string; incidents?: Array<{ severity: string; status: string; reportedToRegulator: boolean }> }
  if (!env.available || !env.incidents) return unavailable(env.reason ?? 'not_deployed')
  const open = env.incidents.filter(i => !/closed|resolved/i.test(i.status))
  const openCritical = open.filter(i => /critical|high/i.test(i.severity)).length
  const unreported = open.filter(i => !i.reportedToRegulator).length
  return {
    status: openCritical > 0 ? 'critical' : open.length > 0 ? 'degraded' : 'ok',
    score: Math.max(0, 100 - openCritical * 30 - (open.length - openCritical) * 10 - unreported * 5),
    metricCs: `${open.length} otevřených (${openCritical} kritických) · ${unreported} nenahlášených`,
    metricEn: `${open.length} open (${openCritical} critical) · ${unreported} unreported`,
  }
}

async function fetchFraud(): Promise<Patch> {
  const { ok, status, body } = await safeJson(svcUrl('fraud-service', '/api/v1/fraud/review-queue', { limit: '100' }))
  if (!ok) return unavailable(status === 404 ? 'not_deployed' : status === 401 || status === 403 ? 'unauthorized' : 'unreachable')
  const items = Array.isArray(body) ? body : ((body as { items?: unknown[]; queue?: unknown[] })?.items ?? (body as { queue?: unknown[] })?.queue ?? [])
  const pending = items.length
  return {
    status: pending > 20 ? 'degraded' : 'ok',
    score: Math.max(0, 100 - pending * 4),
    metricCs: `${pending} ve frontě ke kontrole`,
    metricEn: `${pending} in the review queue`,
  }
}

async function fetchAml(): Promise<Patch> {
  const { ok, status, body } = await safeJson(svcUrl('aml-service', '/api/v1/aml/cases'))
  if (!ok) return unavailable(status === 404 ? 'not_deployed' : status === 401 || status === 403 ? 'unauthorized' : 'unreachable')
  const items = Array.isArray(body) ? body : ((body as { cases?: unknown[] })?.cases ?? [])
  const open = (items as Array<{ status?: string }>).filter(c => !/closed/i.test(c.status ?? ''))
  return {
    status: open.length > 0 ? 'degraded' : 'ok',
    score: Math.max(0, 100 - open.length * 10),
    metricCs: `${open.length} otevřených případů`,
    metricEn: `${open.length} open cases`,
  }
}

async function fetchSanctions(): Promise<Patch> {
  const { ok, body } = await safeJson('/api/sanctions/approvals')
  if (!ok) return unavailable('unreachable')
  const env = body as { available?: boolean; reason?: string; approvals?: Array<{ status?: string }> }
  if (env.available === false) return unavailable(env.reason ?? 'not_deployed')
  const pending = (env.approvals ?? (Array.isArray(body) ? body as unknown[] : [])).filter(
    a => /pending/i.test((a as { status?: string }).status ?? 'pending'),
  ).length
  return {
    status: pending > 0 ? 'degraded' : 'ok',
    score: Math.max(0, 100 - pending * 15),
    metricCs: `${pending} čekajících schválení`,
    metricEn: `${pending} pending approvals`,
  }
}

async function fetchApprovals(): Promise<Patch> {
  const { ok, body } = await safeJson('/api/approvals/pending')
  if (!ok) return unavailable('unreachable')
  const items = Array.isArray(body) ? body : ((body as { items?: unknown[] })?.items ?? [])
  const pending = items.length
  return {
    status: pending > 10 ? 'degraded' : 'ok',
    score: Math.max(0, 100 - pending * 3),
    metricCs: `${pending} čeká na maker-checker`,
    metricEn: `${pending} awaiting maker-checker`,
  }
}

async function fetchAudit(): Promise<Patch> {
  const { ok, status } = await safeJson('/api/svc/audit-service/api/v1/audit/entries?limit=1')
  if (!ok) return unavailable(status === 404 ? 'not_deployed' : status === 401 || status === 403 ? 'unauthorized' : 'unreachable')
  return {
    status: 'ok', score: 100,
    metricCs: 'Auditní stopa je aktivní', metricEn: 'Audit trail is active',
  }
}

async function fetchIdentity(): Promise<Patch> {
  const { ok, status, body } = await safeJson(svcUrl('party-service', '/api/v1/parties/cases'))
  if (!ok) return unavailable(status === 404 ? 'not_deployed' : status === 401 || status === 403 ? 'unauthorized' : 'unreachable')
  const items = Array.isArray(body) ? body : ((body as { cases?: unknown[] })?.cases ?? [])
  const open = (items as Array<{ status?: string }>).filter(c => !/closed|approved|rejected/i.test(c.status ?? ''))
  return {
    status: open.length > 10 ? 'degraded' : 'ok',
    score: Math.max(0, 100 - open.length * 5),
    metricCs: `${open.length} otevřených případů identity`,
    metricEn: `${open.length} open identity cases`,
  }
}

async function fetchSbom(): Promise<Patch> {
  // SBOM drift = image↔GitOps shoda per money-path služba (ADR-0030 D5). 503 znamená
  // "ještě neproskenováno" — typed unavailable, nikdy falešné OK.
  const { ok, status, body } = await safeJson('/api/sbom/drift')
  if (!ok) return unavailable(status === 503 ? 'not_deployed' : status === 401 || status === 403 ? 'unauthorized' : 'unreachable')
  const services = (body as { services?: Record<string, { status: string; inSync?: boolean }> })?.services ?? {}
  const entries = Object.values(services)
  const checked = entries.filter(e => e.status === 'checked')
  const outOfSync = checked.filter(e => e.inSync === false).length
  if (checked.length === 0) return unavailable('not_deployed')
  const ratio = checked.length ? (checked.length - outOfSync) / checked.length : 1
  return {
    status: outOfSync > 0 ? 'degraded' : 'ok',
    score: Math.round(ratio * 100),
    metricCs: `${outOfSync} mimo sync z ${checked.length} služeb`,
    metricEn: `${outOfSync} out of sync of ${checked.length} services`,
  }
}

// ── Stránka ──────────────────────────────────────────────────────────────────

const DOMAIN_DEFS: Array<Omit<Domain, 'status'>> = [
  { id: 'posture',   icon: ScanLine,       href: '/security',
    nameCs: 'Bezpečnostní posture', nameEn: 'Security Posture',
    descCs: 'Fleet-wide scan, OWASP Top 10, hlavičky, compliance', descEn: 'Fleet-wide scan, OWASP Top 10, headers, compliance' },
  { id: 'incidents', icon: AlertTriangle,  href: '/security/incidents',
    nameCs: 'ICT incidenty (DORA)', nameEn: 'ICT Incidents (DORA)',
    descCs: 'Registr incidentů, hlášení regulátorovi', descEn: 'Incident register, regulator reporting' },
  { id: 'fraud',     icon: ShieldAlert,    href: '/fraud',
    nameCs: 'Fraud', nameEn: 'Fraud',
    descCs: 'Fronta ke kontrole, detekce podvodů', descEn: 'Review queue, fraud detection' },
  { id: 'aml',       icon: AlertOctagon,   href: '/aml',
    nameCs: 'AML', nameEn: 'AML',
    descCs: 'Případy praní peněz, podezřelé transakce', descEn: 'Money-laundering cases, suspicious transactions' },
  { id: 'sanctions', icon: Shield,         href: '/sanctions',
    nameCs: 'Sankce', nameEn: 'Sanctions',
    descCs: 'Screening listů, blokované strany', descEn: 'List screening, blocked parties' },
  { id: 'approvals', icon: ClipboardCheck, href: '/approvals',
    nameCs: 'Maker-checker', nameEn: 'Maker-checker',
    descCs: 'Federovaná schvalovací schránka', descEn: 'Federated approval inbox' },
  { id: 'audit',     icon: ScrollText,     href: '/audit',
    nameCs: 'Auditní stopa', nameEn: 'Audit Trail',
    descCs: 'Neměnná evidence událostí', descEn: 'Immutable event evidence' },
  { id: 'identity',  icon: Fingerprint,    href: '/identity-cases',
    nameCs: 'Identita & KYC', nameEn: 'Identity & KYC',
    descCs: 'Ověření identity, deduplikace stran', descEn: 'Identity verification, party dedup' },
  { id: 'sbom',      icon: Package,        href: '/system/inventory',
    nameCs: 'Supply chain (SBOM)', nameEn: 'Supply Chain (SBOM)',
    descCs: 'Image↔GitOps drift, inventář komponent, CVE', descEn: 'Image↔GitOps drift, component inventory, CVEs' },
]

export default function SecurityExcellencePage() {
  const { t, language } = useLanguage()
  const [patches, setPatches] = useState<Record<string, Patch>>({})
  const [loading, setLoading] = useState(true)
  const [refreshedAt, setRefreshedAt] = useState<Date | null>(null)

  const load = useCallback(async () => {
    setLoading(true)
    setPatches({})
    const fetchers: Record<string, () => Promise<Patch>> = {
      posture: fetchPosture, incidents: fetchIncidents, fraud: fetchFraud, aml: fetchAml,
      sanctions: fetchSanctions, approvals: fetchApprovals, audit: fetchAudit, identity: fetchIdentity,
      sbom: fetchSbom,
    }
    // Fan-out paralelně; každá doména se doplní jakmile odpoví (progressive render).
    await Promise.all(Object.entries(fetchers).map(async ([id, fn]) => {
      const patch = await fn()
      setPatches(prev => ({ ...prev, [id]: patch }))
    }))
    setRefreshedAt(new Date())
    setLoading(false)
  }, [])

  useEffect(() => { void load() }, [load])

  const domains: Domain[] = DOMAIN_DEFS.map(d => ({ ...d, status: patches[d.id]?.status ?? 'loading', ...patches[d.id] }))

  // Skóre excelence: vážený průměr pouze z domén, které skutečně odpověděly.
  // Posture váží 2× (jediný fleet-wide signál), ostatní 1×.
  const { score, answered, total } = useMemo(() => {
    let wsum = 0, w = 0, answered = 0
    for (const d of domains) {
      if (d.score == null) continue
      const weight = d.id === 'posture' ? 2 : 1
      wsum += d.score * weight; w += weight; answered++
    }
    return { score: w ? Math.round(wsum / w) : null, answered, total: domains.length }
  }, [domains])

  const grade = score != null ? gradeFor(score) : null
  const dateLocale = language === 'cs' ? 'cs-CZ' : 'en-GB'

  const reasonLabel = (reason?: string) => {
    switch (reason) {
      case 'not_deployed': return t('Nenasazeno v tomto prostředí', 'Not deployed in this environment')
      case 'unauthorized': return t('Role bez oprávnění', 'Role lacks permission')
      case 'unreachable':  return t('Služba neodpovídá', 'Service unreachable')
      default:             return t('Nedostupné', 'Unavailable')
    }
  }
  const statusLabel = (s: DomainStatus) => ({
    ok: t('V pořádku', 'Healthy'), degraded: t('Degradováno', 'Degraded'),
    critical: t('Kritické', 'Critical'), unavailable: t('Nedostupné', 'Unavailable'),
    loading: t('Načítám…', 'Loading…'),
  }[s])

  return (
    <AuthGuard permission="system:view">
      <div style={{ padding: '28px 32px', maxWidth: 1400 }}>
        <PageHeader
          icon={<Scale size={20} aria-hidden="true" />}
          title={t('Security Excellence', 'Security Excellence')}
          subtitle={t(
            'Jediný souhrnný pohled na bezpečnost celého ekosystému — posture, incidenty, fraud, AML, sankce, schvalování, audit a identita',
            'A single consolidated view of ecosystem-wide security — posture, incidents, fraud, AML, sanctions, approvals, audit and identity',
          )}
          actions={
            <button type="button" onClick={() => void load()} disabled={loading} aria-busy={loading}
              aria-label={t('Obnovit Security Excellence', 'Refresh Security Excellence')}
              className="btn btn-secondary btn-sm">
              <RefreshCw aria-hidden="true" size={13} /> {t('Obnovit', 'Refresh')}
            </button>
          }
        />

        {/* ── Hero: skóre excelence ─────────────────────────────────────── */}
        <section aria-label={t('Skóre Security Excellence', 'Security Excellence score')}
          style={{ display: 'flex', gap: 24, alignItems: 'center', margin: '20px 0 28px', padding: '24px 28px', border: '1px solid var(--border, #e5e7eb)', borderRadius: 12, background: 'var(--surface-2, #fafafa)' }}>
          <div style={{ textAlign: 'center', minWidth: 120 }}>
            <div style={{ fontSize: 52, fontWeight: 700, lineHeight: 1, color: grade ? GRADE_COLORS[grade] : 'var(--text-tertiary)' }}
              aria-live="polite">
              {score != null ? score : '—'}
            </div>
            <div style={{ fontSize: 13, color: 'var(--text-tertiary)' }}>/ 100</div>
            {grade && <div style={{ marginTop: 6, display: 'inline-block', padding: '2px 12px', borderRadius: 999, fontWeight: 700, color: '#fff', background: GRADE_COLORS[grade] }}>{grade}</div>}
          </div>
          <div style={{ flex: 1 }}>
            <strong>{t('Skóre excelence platformy', 'Platform excellence score')}</strong>
            <p style={{ margin: '6px 0 0', color: 'var(--text-secondary, #4b5563)', fontSize: 14 }}>
              {t(
                `Vážený průměr ${answered} z ${total} domén, které odpověděly. Domény označené „Nedostupné" skóre nesnižují — degradace je vidět na kartách, ne skrytá v čísle.`,
                `Weighted average of ${answered} of ${total} responding domains. Domains marked “Unavailable” do not drag the score down — degradation is visible on the cards, not hidden in the number.`,
              )}
            </p>
            {refreshedAt && (
              <p style={{ margin: '6px 0 0', fontSize: 12, color: 'var(--text-tertiary)' }}>
                {t('Aktualizováno', 'Refreshed')}: {refreshedAt.toLocaleTimeString(dateLocale)}
              </p>
            )}
          </div>
        </section>

        {/* ── Doménové pilíře ───────────────────────────────────────────── */}
        <section aria-label={t('Doménové pilíře bezpečnosti', 'Security domain pillars')}
          style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(300px, 1fr))', gap: 16 }}>
          {domains.map(d => {
            const tone = STATUS_TONE[d.status]
            const Icon = d.icon
            return (
              <Link key={d.id} href={d.href} style={{ textDecoration: 'none', color: 'inherit' }}
                aria-label={`${language === 'cs' ? d.nameCs : d.nameEn} — ${statusLabel(d.status)}`}>
                <article style={{ border: '1px solid var(--border, #e5e7eb)', borderRadius: 12, padding: '18px 20px', height: '100%', display: 'flex', flexDirection: 'column', gap: 10, background: 'var(--surface-1, #fff)' }}>
                  <header style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
                    <Icon size={18} aria-hidden="true" />
                    <strong style={{ flex: 1 }}>{language === 'cs' ? d.nameCs : d.nameEn}</strong>
                    <span style={{ fontSize: 12, fontWeight: 600, padding: '2px 10px', borderRadius: 999, color: tone.color, background: tone.bg }}>
                      {statusLabel(d.status)}
                    </span>
                  </header>
                  <p style={{ margin: 0, fontSize: 13, color: 'var(--text-tertiary)' }}>
                    {language === 'cs' ? d.descCs : d.descEn}
                  </p>
                  <div style={{ marginTop: 'auto', display: 'flex', alignItems: 'center', gap: 10 }}>
                    {d.status === 'unavailable' ? (
                      <span style={{ fontSize: 13, color: tone.color }}>{reasonLabel(d.unavailableReason)}</span>
                    ) : d.status === 'loading' ? (
                      <span style={{ fontSize: 13, color: tone.color }} role="status">{t('Načítám…', 'Loading…')}</span>
                    ) : (
                      <>
                        <span style={{ fontSize: 22, fontWeight: 700, color: GRADE_COLORS[gradeFor(d.score ?? 0)] }}>{d.score}</span>
                        <span style={{ fontSize: 13, color: 'var(--text-secondary, #4b5563)', flex: 1 }}>
                          {language === 'cs' ? d.metricCs : d.metricEn}
                        </span>
                      </>
                    )}
                    <ArrowRight size={14} aria-hidden="true" style={{ marginLeft: 'auto', color: 'var(--text-tertiary)' }} />
                  </div>
                </article>
              </Link>
            )
          })}
        </section>

        <p style={{ marginTop: 20, fontSize: 12, color: 'var(--text-tertiary)' }}>
          {t(
            'Hub agreguje read-only signály nad specializovanými obrazovkami — žádná z nich není nahrazena; každá karta vede na její detail. Zdroje: /api/security, /api/security/incidents, fraud-service, aml-service, /api/sanctions/approvals, /api/approvals/pending, audit-service, party-service, /api/sbom/drift.',
            'The hub aggregates read-only signals over the specialised screens — none of them is replaced; every card drills through to its detail. Sources: /api/security, /api/security/incidents, fraud-service, aml-service, /api/sanctions/approvals, /api/approvals/pending, audit-service, party-service, /api/sbom/drift.',
          )}
        </p>
      </div>
    </AuthGuard>
  )
}
