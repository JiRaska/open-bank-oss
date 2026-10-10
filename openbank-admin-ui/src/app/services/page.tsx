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
import { buildRegistry, findInRegistry, type ServiceEntry } from '@/lib/services/registry'

// The ONLY hand-listed card: `libs` is a documentation bundle, not a runtime service, so no
// catalog module backs it. Every service card is DERIVED at runtime from the code-generated catalog
// (/api/catalog/services → every runnable module) and unioned with the live cluster inventory
// (/api/services/health → Kubernetes discovery, ADR-0051), so neither a new module nor a newly
// deployed workload needs an edit here.
const STATIC_CANDIDATES = [
  { id: 'libs', label: 'openbank-libs', group: 'platform' },
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
  version?: string
  gitCommit?: string
  source?: 'live' | 'bundle'
  error?: string
}

interface Candidate { id: string; label: string; group: string; desc?: string; catalogShort?: string }

// Catalog modules that ship no runtime service: the shared libraries and the IaC
// module. `kind: 'ui'` (admin-ui) and `kind: 'library'` (openbank-libs) are excluded
// by kind; these four are classified `component` by generate-catalog.mjs but are not
// fleet members. Everything else in the catalog is. Kept explicit and tiny — the
// guard test asserts each entry is still a real catalog module, so a rename fails CI
// rather than silently skewing the count.
const NON_FLEET_MODULES = new Set(['infra', 'libs-domain', 'libs-runtime', 'libs-testing'])

/**
 * Fleet size, derived from the code-generated catalog (ADR-0029 D3) rather than
 * hand-counted. The previous hand-typed count went stale; a derived
 * count cannot drift.
 */
function fleetSize(services: { short: string; kind: string; runnable?: boolean }[]): number {
  return services.filter(
    s => s.runnable === true || (s.runnable === undefined && s.kind !== 'ui' && s.kind !== 'library' && !NON_FLEET_MODULES.has(s.short)),
  ).length
}

interface CatalogModule { name: string; short: string; kind: string; runnable?: boolean; apiTitle?: string | null; port?: number | null; mgmtPort?: number | null }

function candidateFromCatalog(registry: readonly ServiceEntry[], catalogModule: CatalogModule): Candidate {
  const registered = registry.find(service => service.container === catalogModule.name)
  const id = registered?.id ?? catalogModule.short.replace(/-service$/, '')
  return {
    id,
    label: registered?.label ?? catalogModule.apiTitle ?? catalogModule.short.replaceAll('-', ' '),
    group: registered?.group ?? 'platform',
    catalogShort: catalogModule.short,
  }
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
function catalogShortFor(registry: readonly ServiceEntry[], c: Candidate): string {
  if (c.catalogShort) return c.catalogShort
  const entry = findInRegistry(registry, c.id)
  return entry ? entry.container.replace(/^openbank-/, '') : c.id
}

export default function ServicesDocsOverviewPage() {
  const { t } = useLanguage()
  const [candidates, setCandidates] = useState<Candidate[]>(STATIC_CANDIDATES as readonly Candidate[] as Candidate[])
  const [source, setSource] = useState<'kubernetes' | 'static'>('static')
  const [statuses, setStatuses] = useState<Record<string, DocsStatus>>({})
  const [loading, setLoading] = useState(true)
  const [fleetCount, setFleetCount] = useState<number | null>(null)
  const [registry, setRegistry] = useState<ServiceEntry[]>([])
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
      .then((data: { services?: CatalogModule[] } | null) => {
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
      let fleet: ServiceEntry[] = []
      try {
        const catalogResponse = await fetch('/api/catalog/services', { cache: 'no-store' })
        if (catalogResponse.ok) {
          const catalog = await catalogResponse.json() as { services?: CatalogModule[] }
          const derived = buildRegistry(catalog.services ?? [])
          if (mounted) setRegistry(derived)
          fleet = derived
          for (const catalogModule of catalog.services ?? []) {
            if (catalogModule.runnable !== true) continue
            const candidate = candidateFromCatalog(derived, catalogModule)
            if (!byId.has(candidate.id)) byId.set(candidate.id, candidate)
          }
        }
      } catch { /* keep the static fallback */ }
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
              const registered = fleet.find(service => service.k8sName === s.name || service.container === `openbank-${s.name}`)
              const id = registered?.id ?? s.name.replace(/-service$/, '')
              const existing = byId.get(id)
              byId.set(id, existing
                ? { ...existing, group: s.group }
                : { id, label: s.label || id, group: s.group, catalogShort: s.name })
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
            const body = await rr.json() as { items?: unknown[]; version?: string; gitCommit?: string; source?: 'live' | 'bundle' }
            if (!body.items?.length) return { id: c.id, hasDocs: false }
            return {
              id: c.id,
              hasDocs: true,
              sections: body.items?.length ?? 0,
              version: body.version,
              gitCommit: body.gitCommit,
              source: body.source,
            }
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
  const liveDocs = candidates.filter(c => statuses[c.id]?.hasDocs && statuses[c.id]?.source === 'live').length

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
            {t('dokumentací dostupných z běžících verzí', 'documentation sets available from running versions')}
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
          {t('Seznam vychází z katalogu buildu a z clusteru. Každá služba publikuje dokumentaci ze svého image na /q/openbank/docs; vlastní kapitoly patří do src/main/resources/docs/.',
             'The list comes from the build catalog and cluster. Each service publishes docs from its own image at /q/openbank/docs; authored chapters live in src/main/resources/docs/.')}
        </div>
      </div>

      {!loading && (
        <div role="status" aria-live="polite" style={{ display: 'flex', alignItems: 'center', gap: '10px', flexWrap: 'wrap', fontSize: '11px', color: 'var(--text-secondary)' }}>
          <span style={{ display: 'inline-flex', alignItems: 'center', gap: '6px' }}><span style={{ width: '8px', height: '8px', borderRadius: '50%', background: 'var(--success)' }} />{t(`${liveDocs} živě ze služeb`, `${liveDocs} live from services`)}</span>
          <span>·</span>
          <span>{t(`${candidates.length - liveDocs} ostatních položek: přibalené knihovny nebo nedostupný endpoint`, `${candidates.length - liveDocs} other entries: bundled libraries or unavailable endpoints`)}</span>
        </div>
      )}

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
            <option value="missing">{t('Nedostupná', 'Unavailable')}</option>
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
                {(statuses[svc.id]?.version || statuses[svc.id]?.gitCommit) && (
                  <div style={{ fontSize: '11px', color: 'var(--text-secondary)', fontVariantNumeric: 'tabular-nums' }}>
                    {statuses[svc.id]?.version && <span>{t('Verze', 'Version')} {statuses[svc.id]?.version}</span>}
                    {statuses[svc.id]?.gitCommit && <span>{statuses[svc.id]?.version ? ' · ' : ''}{t('Build', 'Build')} {statuses[svc.id]?.gitCommit?.slice(0, 8)}</span>}
                  </div>
                )}
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
            {t('Dokumentace nyní nedostupná', 'Documentation currently unavailable')} ({withoutDocs.length})
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
                {t('Služba nemusí být nasazená nebo její endpoint neodpovídá. Ručně psané kapitoly se ukládají do',
                   'The service may not be deployed or its endpoint may not respond. Authored chapters belong in')}{' '}
                <code style={{ background: 'var(--surface-2)', padding: '1px 6px', borderRadius: 'var(--r-sm)', fontSize: '11px' }}>src/main/resources/docs/README.md</code>
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
      <CatalogDriftBanner present={candidates.map(c => catalogShortFor(registry, c))} />
    </div>
  )
}
