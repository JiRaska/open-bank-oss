// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

import { useEffect, useState } from 'react'
import Link from 'next/link'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { BookOpen, FileText, ArrowRight, AlertCircle, Package, Search } from 'lucide-react'
import { ServerlessTierBadge } from '@/components/finops/ServerlessTierBadge'
import { ServerlessLegend } from '@/components/finops/ServerlessLegend'
import { CatalogDriftBanner } from '@/components/governance/CatalogDriftBanner'
import { PageHeader } from '@/components/ui/PageHeader'
import { findService } from '@/lib/services/registry'

// Static fallback / backlog base. The live list comes from the cluster at runtime
// (see the effect below: /api/services/health → ADR-0051 Kubernetes discovery), so a
// newly-deployed service appears here automatically with no edit. This array is only
// the off-cluster (local-dev) fallback AND the docs backlog of services that *should*
// exist but aren't deployed yet — it is unioned with the live list, never the sole
// source.
//
// Every id here MUST have a SERVICE_REGISTRY entry (src/lib/services/registry.ts).
// There is NO name-guessing fallback: the docs loader is exact-match on the registry
// id (`libs` is the one special case — it reads the image-baked bundle instead of a
// live service). An id with no registry entry renders a card whose docs link 404s
// with `Unknown service`. A stale comment here once promised an
// `openbank-<id>-service` → `openbank-<id>` fallback that docs.ts never implemented,
// which is how 7 such dead cards accumulated. Enforced by
// src/test/service-registry.guard.test.ts.
const STATIC_CANDIDATES = [
  { id: 'libs',                label: 'openbank-libs',         group: 'platform' },
  { id: 'account',             label: 'Account Service',       group: 'core' },
  { id: 'ledger',              label: 'Ledger Service',        group: 'core' },
  { id: 'transaction',         label: 'Transaction Service',   group: 'core' },
  { id: 'balance',             label: 'Balance Service',       group: 'core' },
  { id: 'product-catalog',     label: 'Product Catalog',       group: 'core' },
  { id: 'pid',                 label: 'PID Service',           group: 'identity' },
  { id: 'party',               label: 'Party Service',         group: 'identity' },
  { id: 'sca',                 label: 'SCA Service',           group: 'identity' },
  { id: 'consent',             label: 'Consent Service',       group: 'open-banking' },
  { id: 'psd2',                label: 'PSD2 Service',          group: 'open-banking' },
  { id: 'tpp-registry',        label: 'TPP Registry',          group: 'open-banking' },
  { id: 'sepa-payment',        label: 'SEPA Payment',          group: 'payments' },
  { id: 'sepa-instant',        label: 'SEPA Instant',          group: 'payments' },
  { id: 'domestic-payment',    label: 'Domestic Payment',      group: 'payments' },
  { id: 'card-issuance',       label: 'Card Issuance',         group: 'payments' },
  { id: 'fx',                  label: 'FX Service',            group: 'payments' },
  { id: 'standing-order',      label: 'Standing Order',        group: 'payments' },
  { id: 'swift',               label: 'SWIFT Service',         group: 'payments' },
  { id: 'clearing',            label: 'Clearing Service',      group: 'payments' },
  { id: 'interest',            label: 'Interest Service',      group: 'payments' },
  { id: 'lending',             label: 'Lending Service',       group: 'payments' },
  { id: 'sdd',                 label: 'SDD Service',           group: 'payments' },
  { id: 'kyc',                 label: 'KYC Service',           group: 'compliance' },
  { id: 'aml',                 label: 'AML Service',           group: 'compliance' },
  { id: 'sanctions',           label: 'Sanctions Service',     group: 'compliance' },
  { id: 'audit',               label: 'Audit Service',         group: 'compliance' },
  { id: 'dispute',             label: 'Dispute Service',       group: 'compliance' },
  { id: 'anacredit',           label: 'AnaCredit Service',     group: 'compliance' },
  { id: 'statement',           label: 'Statement Service',     group: 'compliance' },
  { id: 'fraud',               label: 'Fraud Service',         group: 'compliance' },
  { id: 'onboarding',          label: 'Onboarding Service',    group: 'identity' },
  { id: 'agent',               label: 'Agent (MCP)',           group: 'platform' },
  { id: 'notification',        label: 'Notification Service',  group: 'platform' },
  { id: 'copilot',             label: 'Copilot Service',       group: 'platform' },
  { id: 'security-scanner',    label: 'Security Scanner',      group: 'platform' },
  { id: 'analytics-sink',      label: 'Analytics Sink',        group: 'platform' },
] as const

const GROUP_LABELS: Record<string, { label: string; color: string }> = {
  'core':         { label: 'Core Banking',     color: '#2563eb' },
  'identity':     { label: 'Identity',         color: '#059669' },
  'open-banking': { label: 'Open Banking',     color: '#7c3aed' },
  'payments':     { label: 'Payments',         color: '#dc2626' },
  'compliance':   { label: 'Compliance',       color: '#d97706' },
  'platform':     { label: 'Platform',         color: '#6b7280' },
}

interface DocsStatus {
  id: string
  hasDocs: boolean
  sections?: number
  error?: string
}

interface Candidate { id: string; label: string; group: string; desc?: string }

// Catalog modules that ship no runtime service: the shared libraries and the IaC
// module. `kind: 'ui'` (admin-ui) and `kind: 'library'` (openbank-libs) are excluded
// by kind; these four are classified `component` by generate-catalog.mjs but are not
// fleet members. Everything else in the catalog is. Kept explicit and tiny — the
// guard test asserts each entry is still a real catalog module, so a rename fails CI
// rather than silently skewing the count.
const NON_FLEET_MODULES = new Set(['infra', 'libs-domain', 'libs-runtime', 'libs-testing'])

/**
 * Fleet size, derived from the code-generated catalog (ADR-0029 D3) rather than
 * hand-counted. The previous hardcoded "33" was stale by 21 services; a derived
 * count cannot drift.
 */
function fleetSize(services: { short: string; kind: string }[]): number {
  return services.filter(
    s => s.kind !== 'ui' && s.kind !== 'library' && !NON_FLEET_MODULES.has(s.short),
  ).length
}

/**
 * Card id → catalog `short` name, for the drift banner. Resolved through the
 * registry (`container` minus the `openbank-` prefix IS the catalog short), not by
 * appending `-service` and hoping — that guess is wrong for every module without the
 * suffix (sepa-instant, product-catalog, analytics-sink, security-scanner…).
 * Ids discovered live from the cluster have no registry entry; for those the k8s
 * workload name already equals the catalog short.
 *
 * Deliberately `container`-derived and NOT k8sNameOf(): the catalog is keyed by
 * module directory, which differs from the k8s workload name for security-scanner.
 */
function catalogShortFor(c: Candidate): string {
  const entry = findService(c.id)
  return entry ? entry.container.replace(/^openbank-/, '') : c.id
}

export default function ServicesDocsOverviewPage() {
  const { t } = useLanguage()
  const [candidates, setCandidates] = useState<Candidate[]>(STATIC_CANDIDATES as readonly Candidate[] as Candidate[])
  const [source, setSource] = useState<'kubernetes' | 'static'>('static')
  const [statuses, setStatuses] = useState<Record<string, DocsStatus>>({})
  const [loading, setLoading] = useState(true)
  const [fleetCount, setFleetCount] = useState<number | null>(null)
  const [query, setQuery] = useState('')
  const [groupFilter, setGroupFilter] = useState('all')
  const [statusFilter, setStatusFilter] = useState<'all' | 'documented' | 'missing'>('all')

  // Fleet size for the openbank-libs card copy, derived from the catalog snapshot.
  // Degrades to a count-free description if the snapshot is absent (graceful-state
  // rule #1) — never renders a guessed number.
  useEffect(() => {
    let mounted = true
    fetch('/api/catalog/services', { cache: 'no-store' })
      .then(r => (r.ok ? r.json() : null))
      .then((data: { services?: { short: string; kind: string }[] } | null) => {
        if (mounted && Array.isArray(data?.services)) setFleetCount(fleetSize(data.services))
      })
      .catch(() => { /* catalog snapshot absent — omit the number */ })
    return () => { mounted = false }
  }, [])

  useEffect(() => {
    let mounted = true
    const run = async () => {
      // 1. Resolve the service list from the LIVE cluster inventory (ADR-0051,
      //    /api/services/health → Kubernetes discovery), unioned with the static
      //    catalog. A service deployed to the cluster shows up here with no code
      //    change; an undeployed-but-expected service stays in the docs backlog.
      //    Off-cluster (local dev) the inventory is `static` and we use the catalog.
      const byId = new Map<string, Candidate>(
        (STATIC_CANDIDATES as readonly Candidate[]).map(c => [c.id, c]),
      )
      let src: 'kubernetes' | 'static' = 'static'
      try {
        const r = await fetch('/api/services/health', { cache: 'no-store' })
        if (r.ok) {
          const body = await r.json() as {
            services?: { name: string; label: string; group: string }[]
            source?: string
          }
          if (body.source === 'kubernetes' && body.services?.length) {
            src = 'kubernetes'
            for (const s of body.services) {
              const id = s.name.replace(/-service$/, '')
              if (!byId.has(id)) byId.set(id, { id, label: s.label || id, group: s.group })
            }
          }
        }
      } catch {
        // unreachable inventory → fall back to the static catalog
      }
      const list = Array.from(byId.values())
      if (mounted) { setCandidates(list); setSource(src) }

      // 2. Probe docs presence per service (live Docs-as-Service endpoint).
      const results = await Promise.all(
        list.map(async c => {
          try {
            const rr = await fetch(`/api/services/${c.id}/docs`, { cache: 'no-store' })
            if (!rr.ok) return { id: c.id, hasDocs: false }
            const body = await rr.json() as { items?: unknown[] }
            return { id: c.id, hasDocs: true, sections: body.items?.length ?? 0 }
          } catch (err) {
            return { id: c.id, hasDocs: false, error: String(err) }
          }
        }),
      )
      if (mounted) {
        const map: Record<string, DocsStatus> = {}
        results.forEach(rr => { map[rr.id] = rr })
        setStatuses(map)
        setLoading(false)
      }
    }
    run()
    return () => { mounted = false }
  }, [])

  const normalizedQuery = query.trim().toLocaleLowerCase()
  const filteredCandidates = candidates.filter(candidate => {
    const matchesQuery = !normalizedQuery || [candidate.label, candidate.id, candidate.group]
      .some(value => value.toLocaleLowerCase().includes(normalizedQuery))
    const matchesGroup = groupFilter === 'all' || candidate.group === groupFilter
    const hasDocs = statuses[candidate.id]?.hasDocs === true
    const matchesStatus = statusFilter === 'all'
      || (statusFilter === 'documented' && hasDocs)
      || (statusFilter === 'missing' && !hasDocs)
    return matchesQuery && matchesGroup && matchesStatus
  })
  const withDocs = filteredCandidates.filter(c => statuses[c.id]?.hasDocs)
  const withoutDocs = filteredCandidates.filter(c => !statuses[c.id]?.hasDocs)

  // openbank-libs is the one card with editorial copy; its fleet count is derived,
  // never hardcoded.
  const descFor = (svc: Candidate): string | undefined => {
    if (svc.desc) return svc.desc
    if (svc.id !== 'libs') return undefined
    return fleetCount === null
      ? t('Sdílená infrastrukturní knihovna pro celou flotilu mikroslužeb',
           'Shared infrastructure library for the whole microservice fleet')
      : t(`Sdílená infrastrukturní knihovna pro všech ${fleetCount} mikroslužeb`,
           `Shared infrastructure library for all ${fleetCount} microservices`)
  }

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: '20px' }}>
      <PageHeader
        icon={<BookOpen size={20} aria-hidden="true" />}
        title={t('Dokumentace služeb', 'Service Documentation')}
        subtitle={`${t('Per-service business + technical + compliance dokumentace.', 'Per-service business + technical + compliance documentation.')} ${t('Standard: arc42-lite + C4 + Backstage TechDocs file layout (markdown).', 'Standard: arc42-lite + C4 + Backstage TechDocs file layout (markdown).')}`}
      />

      {/* Summary banner */}
      <div style={{
        background: 'var(--surface)',
        border: '1px solid var(--border)',
        borderRadius: 'var(--r-lg)',
        padding: '16px 18px',
        display: 'flex', alignItems: 'center', gap: '20px',
      }}>
        <div style={{ padding: '10px', borderRadius: 'var(--r-md)', background: 'var(--success-bg)', color: 'var(--success)' }}>
          <Package size={18} />
        </div>
        <div>
          <div style={{ fontSize: '20px', fontWeight: 300, letterSpacing: '-0.02em' }}>
            {loading ? '…' : `${withDocs.length} / ${candidates.length}`}
          </div>
          <div style={{ fontSize: '12px', color: 'var(--text-secondary)' }}>
            {t('služeb má dokumentaci', 'services have documentation')}
            {!loading && (
              <span style={{ color: 'var(--text-tertiary)', marginLeft: '6px' }}>
                · {source === 'kubernetes'
                    ? t('živě z clusteru', 'live from cluster')
                    : t('statický katalog', 'static catalog')}
              </span>
            )}
          </div>
        </div>
        <div style={{ marginLeft: 'auto', fontSize: '12px', color: 'var(--text-tertiary)', maxWidth: '420px', lineHeight: 1.5 }}>
          {t('Seznam služeb je odvozen živě z clusteru (ADR-0051) sjednocený s katalogem; dokumentaci přidáte složkou docs/ se souborem README.md v repu služby.',
             'The service list is derived live from the cluster (ADR-0051) unioned with the catalogue; add documentation via a docs/ folder with a README.md in the service repo.')}
        </div>
      </div>

      {/* Serverless tiers & plan (scale-to-zero) — ADR-0057 / ADR-0083 */}
      <ServerlessLegend />

      <section aria-label={t('Filtry dokumentace služeb', 'Service documentation filters')} style={{
        display: 'flex', alignItems: 'center', gap: '10px', flexWrap: 'wrap',
        padding: '12px 14px', background: 'var(--surface)', border: '1px solid var(--border)', borderRadius: 'var(--r-lg)',
      }}>
        <div style={{ position: 'relative', flex: '1 1 260px', minWidth: '220px' }}>
          <label htmlFor="service-docs-query" style={{ position: 'absolute', width: '1px', height: '1px', padding: 0, margin: '-1px', overflow: 'hidden', clip: 'rect(0, 0, 0, 0)', whiteSpace: 'nowrap', border: 0 }}>
            {t('Hledat službu', 'Search services')}
          </label>
          <Search size={15} aria-hidden="true" style={{ position: 'absolute', left: '10px', top: '9px', color: 'var(--text-tertiary)' }} />
          <input
            id="service-docs-query"
            type="search"
            value={query}
            onChange={event => setQuery(event.target.value)}
            placeholder={t('Hledat podle názvu nebo skupiny…', 'Search by service or group…')}
            style={{ width: '100%', padding: '7px 10px 7px 32px', border: '1px solid var(--border)', borderRadius: 'var(--r-md)', background: 'var(--surface-2)', color: 'var(--text-primary)', fontSize: '12px' }}
          />
        </div>
        <label style={{ display: 'flex', alignItems: 'center', gap: '6px', fontSize: '12px', color: 'var(--text-secondary)' }}>
          <span>{t('Skupina', 'Group')}</span>
          <select value={groupFilter} onChange={event => setGroupFilter(event.target.value)} style={{ padding: '7px 28px 7px 9px', border: '1px solid var(--border)', borderRadius: 'var(--r-md)', background: 'var(--surface-2)', color: 'var(--text-primary)', fontSize: '12px' }}>
            <option value="all">{t('Všechny', 'All')}</option>
            {Object.entries(GROUP_LABELS).map(([id, group]) => <option key={id} value={id}>{t(group.label, group.label)}</option>)}
          </select>
        </label>
        <label style={{ display: 'flex', alignItems: 'center', gap: '6px', fontSize: '12px', color: 'var(--text-secondary)' }}>
          <span>{t('Stav', 'Status')}</span>
          <select value={statusFilter} disabled={loading} onChange={event => setStatusFilter(event.target.value as typeof statusFilter)} style={{ padding: '7px 28px 7px 9px', border: '1px solid var(--border)', borderRadius: 'var(--r-md)', background: 'var(--surface-2)', color: 'var(--text-primary)', fontSize: '12px' }}>
            <option value="all">{t('Všechny', 'All')}</option>
            <option value="documented">{t('S dokumentací', 'Documented')}</option>
            <option value="missing">{t('Bez dokumentace', 'Missing docs')}</option>
          </select>
        </label>
        <span role="status" aria-live="polite" style={{ marginLeft: 'auto', fontSize: '11px', color: 'var(--text-tertiary)' }}>
          {t(`${filteredCandidates.length} z ${candidates.length} služeb`, `${filteredCandidates.length} of ${candidates.length} services`)}
        </span>
      </section>

      {/* Documented services */}
      {withDocs.length > 0 && (
        <section>
          <h2 style={{ fontSize: '13px', fontWeight: 600, textTransform: 'uppercase', letterSpacing: '0.06em', color: 'var(--text-secondary)', marginBottom: '12px' }}>
            {t('Služby s dokumentací', 'Documented services')}
          </h2>
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(280px, 1fr))', gap: '10px' }}>
            {withDocs.map(svc => (
              <Link key={svc.id} href={`/services/${svc.id}/docs`}
                style={{
                  display: 'flex', flexDirection: 'column', gap: '8px',
                  padding: '14px 16px',
                  background: 'var(--surface)',
                  border: '1px solid var(--border)',
                  borderRadius: 'var(--r-md)',
                  textDecoration: 'none', color: 'var(--text-primary)',
                  transition: 'border-color 0.15s, background 0.15s',
                }}
                className="docs-card-hover"
              >
                <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
                  <div style={{ width: '8px', height: '8px', borderRadius: '50%', background: GROUP_LABELS[svc.group]?.color }} />
                  <div style={{ fontSize: '13px', fontWeight: 600, flex: 1, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{svc.label}</div>
                  <ArrowRight size={14} style={{ color: 'var(--text-tertiary)', flexShrink: 0 }} />
                </div>
                <div style={{ display: 'flex', alignItems: 'center', gap: '6px', fontSize: '11px', color: 'var(--text-tertiary)', flexWrap: 'wrap' }}>
                  <FileText size={11} />
                  {statuses[svc.id]?.sections ?? '?'} {t('sekcí', 'sections')} ·
                  <span style={{ color: GROUP_LABELS[svc.group]?.color }}>{GROUP_LABELS[svc.group]?.label}</span>
                  <ServerlessTierBadge serviceId={svc.id} dense />
                </div>
                {descFor(svc) && (
                  <div style={{ fontSize: '12px', color: 'var(--text-secondary)', lineHeight: 1.4 }}>
                    {descFor(svc)}
                  </div>
                )}
              </Link>
            ))}
          </div>
        </section>
      )}

      {/* Services without docs */}
      {!loading && withoutDocs.length > 0 && (
        <section>
          <h2 style={{ fontSize: '13px', fontWeight: 600, textTransform: 'uppercase', letterSpacing: '0.06em', color: 'var(--text-secondary)', marginBottom: '12px' }}>
            {t('Zatím bez dokumentace', 'Not yet documented')} ({withoutDocs.length})
          </h2>
          <div style={{
            background: 'var(--surface)',
            border: '1px solid var(--border)',
            borderRadius: 'var(--r-lg)',
            padding: '14px 16px',
            display: 'flex', alignItems: 'flex-start', gap: '12px',
            fontSize: '12px',
          }}>
            <AlertCircle size={14} style={{ color: 'var(--text-tertiary)', flexShrink: 0, marginTop: '2px' }} />
            <div style={{ flex: 1 }}>
              <div style={{ color: 'var(--text-secondary)', marginBottom: '8px' }}>
                {t('Aby se služba sem zařadila, přidejte do jejího repa', 'To document a service, add to its repo')}{' '}
                <code style={{ background: 'var(--surface-2)', padding: '1px 6px', borderRadius: 'var(--r-sm)', fontSize: '11px' }}>docs/README.md</code>
                {' '}{t('podle vzoru', 'following the pattern of')}{' '}
                <Link href="/services/libs/docs" style={{ color: 'var(--accent)' }}>openbank-libs/docs/</Link>.
              </div>
              <div style={{ display: 'flex', flexWrap: 'wrap', gap: '6px' }}>
                {withoutDocs.map(s => (
                  <span key={s.id} style={{
                    display: 'inline-flex', alignItems: 'center', gap: '6px',
                    fontSize: '11px', padding: '2px 8px', borderRadius: '12px',
                    background: 'var(--surface-2)', color: 'var(--text-tertiary)',
                  }}>
                    {s.label}
                    <ServerlessTierBadge serviceId={s.id} dense />
                  </span>
                ))}
              </div>
            </div>
          </div>
        </section>
      )}
      {!loading && filteredCandidates.length === 0 && (
        <div role="status" style={{ padding: '36px 20px', textAlign: 'center', color: 'var(--text-secondary)', background: 'var(--surface)', border: '1px solid var(--border)', borderRadius: 'var(--r-lg)' }}>
          {t('Žádná služba neodpovídá zvoleným filtrům.', 'No services match the selected filters.')}
        </div>
      )}
      <CatalogDriftBanner present={candidates.map(catalogShortFor)} />
    </div>
  )
}
