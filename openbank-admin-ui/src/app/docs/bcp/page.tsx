// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'
import { useState, useEffect } from 'react'
import { ShieldAlert, CheckCircle2, XCircle, Clock, AlertTriangle, RefreshCw, ChevronDown, ChevronRight, Server, Database, Lock, Zap, CreditCard, Eye, BookOpen } from 'lucide-react'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { DocsPageHeader } from '@/components/docs/DocsPageHeader'
import { PrintDocumentButton } from '@/components/docs/PrintDocumentButton'
import { CatalogDriftBanner } from '@/components/governance/CatalogDriftBanner'

type Bilingual = [cs: string, en: string]

const TIERS: {
  id: number
  label: Bilingual
  labelShort: Bilingual
  color: string
  rto: string
  rpo: string
  priority: string
  regulatoryBasis: Bilingual
  icon: typeof Database
  isComplianceGate?: boolean
  services: { name: string; label: string; note: Bilingual }[]
}[] = [
  {
    id: 0,
    label: ['Tier 0 — Infrastrukturní prerekvizity', 'Tier 0 — Infrastructure Prerequisites'],
    labelShort: ['Infrastruktura', 'Infrastructure'],
    color: '#6b7280',
    rto: '5 min',
    rpo: '0 (zero loss)',
    priority: 'P0',
    regulatoryBasis: ['Prerekvizita všech ostatních tierů', 'Prerequisite for all other tiers'],
    icon: Database,
    services: [
      { name: 'postgres',         label: 'PostgreSQL (CloudNativePG)', note: ['Primární datové úložiště (operátor CNPG)', 'Primary data store (CNPG operator)'] },
      { name: 'kafka',            label: 'Apache Kafka',     note: ['Event streaming, outbox pattern', 'Event streaming, outbox pattern'] },
      { name: 'temporal',         label: 'Temporal',         note: ['Orchestrace platebních a závěrkových workflow (settlement, SEPA, EoM)', 'Payment & close workflow orchestration (settlement, SEPA, EoM)'] },
      { name: 'keycloak',         label: 'Keycloak (IAM)',   note: ['PSD2 Art. 97 — autentizace', 'PSD2 Art. 97 — authentication'] },
      { name: 'openbao',          label: 'OpenBao',          note: ['PCI DSS Req. 3.5 — správa klíčů', 'PCI DSS Req. 3.5 — key management'] },
      { name: 'valkey',           label: 'Valkey (Redis)',   note: ['Idempotency — prevence duplikátů', 'Idempotency — duplicate prevention'] },
      { name: 'schema-registry',  label: 'Schema Registry',  note: ['Kafka schema validace', 'Kafka schema validation'] },
    ],
  },
  {
    id: 1,
    label: ['Tier 1 — Hlavní účetní kniha a identita', 'Tier 1 — Core Ledger & Identity'],
    labelShort: ['Hlavní účetní kniha', 'Core Ledger'],
    color: '#2563eb',
    rto: '15 min',
    rpo: '< 1 min',
    priority: 'P1',
    regulatoryBasis: ['CNB § 4 — správa účtů; DORA Art. 17 — audit trail musí být online při incidentu', 'CNB § 4 — account management; DORA Art. 17 — audit trail must be online during an incident'],
    icon: Server,
    services: [
      { name: 'account-service',     label: 'Account Service',     note: ['Lifecycle účtů, IBAN', 'Account lifecycle, IBAN'] },
      { name: 'ledger-service',      label: 'Ledger Service',      note: ['Podvojné účetnictví', 'Double-entry bookkeeping'] },
      { name: 'transaction-service', label: 'Transaction Service', note: ['Zpracování transakcí', 'Transaction processing'] },
      { name: 'party-service',       label: 'Party Service',       note: ['GDPR Art. 25 — zákaznická data', 'GDPR Art. 25 — customer data'] },
      { name: 'audit-service',       label: 'Audit Service',       note: ['DORA Art. 17 — immutable audit log', 'DORA Art. 17 — immutable audit log'] },
    ],
  },
  {
    id: 2,
    label: ['Tier 2 — Compliance gate', 'Tier 2 — Compliance Gate'],
    labelShort: ['Compliance gate', 'Compliance Gate'],
    color: '#dc2626',
    rto: '20 min',
    rpo: '< 5 min',
    priority: 'P1',
    regulatoryBasis: ['5AMLD Art. 18 — platby NESMÍ jet bez AML/Sanctions; DORA Art. 12 — compliance gate', '5AMLD Art. 18 — payments MUST NOT run without AML/Sanctions; DORA Art. 12 — compliance gate'],
    icon: Lock,
    isComplianceGate: true,
    services: [
      { name: 'balance-service',     label: 'Balance Service',     note: ['Real-time zůstatky', 'Real-time balances'] },
      { name: 'aml-service',         label: 'AML Service',         note: ['5AMLD Art. 18 — monitoring transakcí', '5AMLD Art. 18 — transaction monitoring'] },
      { name: 'sanctions-service',   label: 'Sanctions Service',   note: ['5AMLD Art. 13 — sanctions screening', '5AMLD Art. 13 — sanctions screening'] },
      { name: 'kyc-service',         label: 'KYC Service',         note: ['5AMLD Art. 13-14 — due diligence', '5AMLD Art. 13-14 — due diligence'] },
      { name: 'security-scanner',    label: 'Security Scanner',    note: ['DORA Art. 8(2) — continuous scanning', 'DORA Art. 8(2) — continuous scanning'] },
      { name: 'notification-service',label: 'Notification Service',note: ['GDPR Art. 34 — breach notification', 'GDPR Art. 34 — breach notification'] },
    ],
  },
  {
    id: 3,
    label: ['Tier 3 — PSD2 / SCA', 'Tier 3 — PSD2 / SCA'],
    labelShort: ['PSD2 / SCA', 'PSD2 / SCA'],
    color: '#d97706',
    rto: '30 min',
    rpo: '< 5 min',
    priority: 'P2',
    regulatoryBasis: ['PSD2 Art. 97 — SCA před každou platbou; PSD2 Art. 65-67 — consent management', 'PSD2 Art. 97 — SCA before every payment; PSD2 Art. 65-67 — consent management'],
    icon: Lock,
    services: [
      { name: 'sca-service',          label: 'SCA Service',         note: ['PSD2 Art. 97 — strong customer auth', 'PSD2 Art. 97 — strong customer auth'] },
      { name: 'consent-service',      label: 'Consent Service',     note: ['PSD2 Art. 65-67 — payment consent', 'PSD2 Art. 65-67 — payment consent'] },
      { name: 'tpp-registry-service', label: 'TPP Registry',        note: ['PSD2 Art. 65 — validace TPP', 'PSD2 Art. 65 — TPP validation'] },
      { name: 'pid-service',          label: 'PID Service',         note: ['eIDAS 2.0 — payment instrument dir.', 'eIDAS 2.0 — payment instrument dir.'] },
      { name: 'psd2-service',         label: 'PSD2 Service',        note: ['PSD2 AISP/PISP API gateway', 'PSD2 AISP/PISP API gateway'] },
    ],
  },
  {
    id: 4,
    label: ['Tier 4 — Zpracování plateb', 'Tier 4 — Payment Processing'],
    labelShort: ['Platby', 'Payments'],
    color: '#7c3aed',
    rto: '30 min',
    rpo: '< 1 min',
    priority: 'P2',
    regulatoryBasis: ['CNB — platební systémy; PCI DSS Req. 3 — ochrana dat karet; DORA Art. 12 — RTO 15-30 min', 'CNB — payment systems; PCI DSS Req. 3 — card data protection; DORA Art. 12 — RTO 15-30 min'],
    icon: CreditCard,
    services: [
      { name: 'domestic-payment',      label: 'Domestic Payment',    note: ['CZ domácí platby', 'CZ domestic payments'] },
      { name: 'sepa-payment',          label: 'SEPA Payment',        note: ['SEPA Credit Transfer', 'SEPA Credit Transfer'] },
      { name: 'sepa-instant-service',  label: 'SEPA Instant',        note: ['SCT Inst — real-time EUR', 'SCT Inst — real-time EUR'] },
      { name: 'swift-service',         label: 'SWIFT Service',       note: ['Mezinárodní platby MT/MX', 'International payments MT/MX'] },
      { name: 'fx-service',            label: 'FX Service',          note: ['Devizové operace', 'Foreign exchange operations'] },
      { name: 'clearing-service',      label: 'Clearing Service',    note: ['Mezibankovní clearing', 'Interbank clearing'] },
      { name: 'standing-order-service',label: 'Standing Order',      note: ['Trvalé příkazy', 'Standing orders'] },
      { name: 'card-issuance-service', label: 'Card Issuance',       note: ['PCI DSS Req. 3 — data karet', 'PCI DSS Req. 3 — card data'] },
    ],
  },
  {
    id: 5,
    label: ['Tier 5 — Provoz a observabilita', 'Tier 5 — Operations & Observability'],
    labelShort: ['Provoz', 'Operations'],
    color: '#059669',
    rto: '60 min',
    rpo: '< 15 min',
    priority: 'P3',
    regulatoryBasis: ['DORA Art. 8 — monitoring; PCI DSS Req. 12 — dispute handling', 'DORA Art. 8 — monitoring; PCI DSS Req. 12 — dispute handling'],
    icon: Eye,
    services: [
      { name: 'dispute-service',  label: 'Dispute Service',  note: ['PCI DSS Req. 12 — chargebacks', 'PCI DSS Req. 12 — chargebacks'] },
      { name: 'interest-service', label: 'Interest Service', note: ['CNB — úrokové výpočty', 'CNB — interest calculations'] },
      { name: 'admin-ui',         label: 'Admin UI',         note: ['Operátorská konzole', 'Operator console'] },
      { name: 'keda',             label: 'KEDA',             note: ['Scale-to-zero autoscaling — úspora nákladů (ADR-0057)', 'Scale-to-zero autoscaling — cost saving (ADR-0057)'] },
      { name: 'grafana',          label: 'Grafana',          note: ['DORA Art. 8 — monitoring', 'DORA Art. 8 — monitoring'] },
      { name: 'prometheus',       label: 'Prometheus',       note: ['Metriky', 'Metrics'] },
      { name: 'loki',             label: 'Loki',             note: ['DORA Art. 17 — log retention', 'DORA Art. 17 — log retention'] },
      { name: 'tempo',            label: 'Tempo',            note: ['Distributed tracing', 'Distributed tracing'] },
      { name: 'kafka-ui',         label: 'Kafka UI',         note: ['Operační nástroj', 'Operational tool'] },
    ],
  },
]

const INCIDENTS: {
  severity: string
  label: Bilingual
  color: string
  bg: string
  criteria: Bilingual
  response: Bilingual
  reporting: Bilingual
}[] = [
  { severity: 'P0', label: ['Kritický', 'Critical'], color: '#dc2626', bg: '#fef2f2', criteria: ['Tier 0 nebo Tier 1 down; platební zpracování zastaveno', 'Tier 0 or Tier 1 down; payment processing halted'], response: ['Okamžitě', 'Immediately'], reporting: ['CNB do 24h (DORA Art. 17)', 'CNB within 24h (DORA Art. 17)'] },
  { severity: 'P1', label: ['Vysoký', 'High'],     color: '#d97706', bg: '#fffbeb', criteria: ['Tier 2 compliance gate down; AML/Sanctions nedostupné', 'Tier 2 compliance gate down; AML/Sanctions unavailable'], response: ['< 15 min', '< 15 min'], reporting: ['Interní eskalace; CNB pokud > 4h', 'Internal escalation; CNB if > 4h'] },
  { severity: 'P2', label: ['Střední', 'Medium'],   color: '#7c3aed', bg: '#f5f3ff', criteria: ['Tier 3-4 částečná degradace; některé typy plateb nedostupné', 'Tier 3-4 partial degradation; some payment types unavailable'], response: ['< 30 min', '< 30 min'], reporting: ['Interní tracking', 'Internal tracking'] },
  { severity: 'P3', label: ['Nízký', 'Low'],      color: '#059669', bg: '#f0fdf4', criteria: ['Tier 5 operační nástroje down; žádný dopad na zákazníky', 'Tier 5 operational tools down; no customer impact'], response: ['< 2h', '< 2h'], reporting: ['Interní tracking', 'Internal tracking'] },
]

type HealthStatus = 'healthy' | 'unhealthy' | 'starting' | 'unknown'

interface ServiceHealth {
  [container: string]: HealthStatus
}

const CATALOG_PRESENT = TIERS.flatMap(tier => tier.services.map(s => s.name))
  .filter(n => n.endsWith('-service') || n.endsWith('-payment') || n.endsWith('-instant'))

export default function BcpPage() {
  const { t, language } = useLanguage()
  const dateLocale = language === 'cs' ? 'cs-CZ' : 'en-GB'
  const [health, setHealth] = useState<ServiceHealth>({})
  const [loading, setLoading] = useState(true)
  const [lastRefresh, setLastRefresh] = useState<Date | null>(null)
  const [expandedTiers, setExpandedTiers] = useState<Set<number>>(new Set([0, 1, 2]))
  // Completeness cross-check (ADR-0029 D3): code-derived catalog services that are
  // NOT assigned to any continuity tier. Surfaced so a newly-added service can't
  // silently fall outside the BCP model — the hand-curated TIERS no longer hides drift.
  const [unclassified, setUnclassified] = useState<string[]>([])

  const fetchHealth = async () => {
    setLoading(true)
    try {
      const [svcRes, infraRes] = await Promise.allSettled([
        fetch('/api/services/health'),
        fetch('/api/infra/status'),
      ])
      const toStatus = (s: string): HealthStatus =>
        s === 'UP' ? 'healthy' : s === 'DOWN' ? 'unhealthy' : 'unknown'
      const mapped: ServiceHealth = {}
      // Business services: keyed by the bare deployment name (ADR-0051 discovery),
      // which is exactly what the tier `name` fields now use. Prefer the canonical
      // `services[]` array (the shape the Dashboard consumes); fall back to the
      // legacy `byContainer` map so a future endpoint that drops one shape can't
      // silently blank every tier to UNKNOWN.
      if (svcRes.status === 'fulfilled' && svcRes.value.ok) {
        const data = await svcRes.value.json() as {
          services?: { name: string; status: string }[]
          byContainer?: Record<string, { status: string }>
        }
        if (Array.isArray(data.services) && data.services.length > 0) {
          for (const s of data.services) mapped[s.name] = toStatus(s.status)
        } else if (data.byContainer) {
          for (const [container, entry] of Object.entries(data.byContainer)) {
            mapped[container] = toStatus(entry.status)
          }
        }
      }
      // Infra: /api/infra/status is already keyed by the stable infra id
      // (postgres, kafka, keycloak, …) — the same ids the Tier 0/5 entries use.
      if (infraRes.status === 'fulfilled' && infraRes.value.ok) {
        const data = await infraRes.value.json() as Record<string, { status: string }>
        for (const [id, entry] of Object.entries(data)) {
          mapped[id] = toStatus(entry.status)
        }
      }
      // The admin UI is serving this very page, so it is healthy by construction.
      mapped['admin-ui'] = 'healthy'
      setHealth(mapped)
    } catch {
    } finally {
      setLoading(false)
      setLastRefresh(new Date())
    }
  }

  useEffect(() => {
    fetchHealth()
    const interval = setInterval(fetchHealth, 30000)
    return () => clearInterval(interval)
  }, [])

  // Pull the code-derived catalog once and diff it against the tier membership.
  useEffect(() => {
    let alive = true
    const classified = new Set(TIERS.flatMap(tier => tier.services.map(s => s.name)))
    fetch('/api/catalog/services', { cache: 'no-store' })
      .then(r => r.ok ? r.json() : null)
      .then(data => {
        if (!alive || !data?.available || !Array.isArray(data.services)) return
        setUnclassified(
          data.services
            .filter((s: { short: string; kind: string }) => s.kind === 'service' && !classified.has(s.short))
            .map((s: { short: string }) => s.short)
            .sort(),
        )
      })
      .catch(() => { /* catalog snapshot absent — skip the cross-check */ })
    return () => { alive = false }
  }, [])

  const toggleTier = (id: number) => {
    setExpandedTiers(prev => {
      const next = new Set(prev)
      next.has(id) ? next.delete(id) : next.add(id)
      return next
    })
  }

  const getTierStatus = (tier: typeof TIERS[0]): { status: HealthStatus; healthy: number; total: number } => {
    const total = tier.services.length
    const statuses = tier.services.map(s => health[s.name] ?? 'unknown')
    const healthy = statuses.filter(s => s === 'healthy').length
    const hasUnhealthy = statuses.some(s => s === 'unhealthy')
    const hasStarting = statuses.some(s => s === 'starting')
    const allUnknown = statuses.every(s => s === 'unknown')

    if (allUnknown) return { status: 'unknown', healthy, total }
    if (hasUnhealthy) return { status: 'unhealthy', healthy, total }
    if (hasStarting) return { status: 'starting', healthy, total }
    return { status: 'healthy', healthy, total }
  }

  const complianceTierStatus = getTierStatus(TIERS[2])
  const paymentsBlocked = complianceTierStatus.status !== 'healthy' && complianceTierStatus.status !== 'unknown'

  const overallHealthy = TIERS.reduce((acc, t) => acc + getTierStatus(t).healthy, 0)
  const overallTotal = TIERS.reduce((acc, t) => acc + t.services.length, 0)

  const StatusIcon = ({ status, size = 14 }: { status: HealthStatus; size?: number }) => {
    if (status === 'healthy')   return <CheckCircle2 size={size} style={{ color: '#16a34a' }} />
    if (status === 'unhealthy') return <XCircle size={size} style={{ color: '#dc2626' }} />
    if (status === 'starting')  return <Clock size={size} style={{ color: '#d97706' }} />
    return <AlertTriangle size={size} style={{ color: '#9ca3af' }} />
  }

  const statusLabel = (s: HealthStatus) => ({
    healthy: 'healthy', unhealthy: 'unhealthy', starting: 'starting', unknown: 'unknown',
  }[s])

  const statusBadgeStyle = (s: HealthStatus): React.CSSProperties => {
    const map: Record<HealthStatus, React.CSSProperties> = {
      healthy:   { background: '#dcfce7', color: '#15803d', border: '1px solid #bbf7d0' },
      unhealthy: { background: '#fee2e2', color: '#b91c1c', border: '1px solid #fecaca' },
      starting:  { background: '#fef9c3', color: '#a16207', border: '1px solid #fef08a' },
      unknown:   { background: '#f3f4f6', color: '#6b7280', border: '1px solid #e5e7eb' },
    }
    return map[s]
  }

  return (
    <div className="docs-printable">
      <DocsPageHeader
        crumbs={<>
            <span>OpenBank</span>
            <span className="breadcrumb-sep">/</span>
            <span>{t('Dokumentace', 'Documentation')}</span>
            <span className="breadcrumb-sep">/</span>
            <span className="breadcrumb-current">{t('Plán kontinuity provozu', 'Business Continuity Plan')}</span>
          </>}
        title={t('Plán kontinuity provozu', 'Business Continuity Plan')}
        subtitle={t('Prioritizovaný plán obnovy dle DORA Art. 11-12, CNB § 20d, EBA ICT Risk Guidelines', 'Prioritized recovery plan per DORA Art. 11-12, CNB § 20d, EBA ICT Risk Guidelines')}
        icon={<ShieldAlert aria-hidden="true" size={18} style={{ color: '#dc2626' }} />}
        actions={<div className="docs-header-actions" style={{ display: 'flex', alignItems: 'center', gap: '10px' }}>
          {lastRefresh && (
            <span style={{ fontSize: '12px', color: 'var(--text-secondary)' }}>
              {t('Aktualizováno:', 'Updated:')} {lastRefresh.toLocaleTimeString(dateLocale)}
            </span>
          )}
          <PrintDocumentButton />
          <button
            type="button"
            className="btn"
            onClick={fetchHealth}
            disabled={loading}
            aria-busy={loading}
            aria-label={t('Obnovit stav BCP', 'Refresh BCP status')}
            style={{
              display: 'flex', alignItems: 'center', gap: '6px',
              padding: '8px 14px', borderRadius: '8px', border: '1px solid var(--border)',
              background: 'var(--surface-2)', cursor: 'pointer', fontSize: '13px',
              color: 'var(--text-primary)',
            }}
          >
            <RefreshCw size={13} aria-hidden="true" style={{ animation: loading ? 'spin 1s linear infinite' : 'none' }} />
            {t('Obnovit', 'Refresh')}
          </button>
        </div>}
      />

      {/* Summary stats */}
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(4, 1fr)', gap: '12px', marginBottom: '24px' }}>
        {[
          {
            label: t('Celkový stav', 'Overall status'),
            value: loading ? '…' : overallHealthy === overallTotal ? 'NOMINAL' : 'DEGRADED',
            sub: loading ? '' : t(`${overallHealthy}/${overallTotal} služeb healthy`, `${overallHealthy}/${overallTotal} services healthy`),
            color: overallHealthy === overallTotal ? '#16a34a' : '#dc2626',
            bg: overallHealthy === overallTotal ? '#dcfce7' : '#fee2e2',
          },
          {
            label: t('Compliance gate', 'Compliance gate'),
            value: loading ? '…' : complianceTierStatus.status === 'healthy' ? 'CLEAR' : 'BLOCKED',
            sub: '5AMLD Art. 18 / DORA Art. 12',
            color: complianceTierStatus.status === 'healthy' ? '#16a34a' : '#dc2626',
            bg: complianceTierStatus.status === 'healthy' ? '#dcfce7' : '#fee2e2',
          },
          {
            label: t('Platební zpracování', 'Payment processing'),
            value: loading ? '…' : paymentsBlocked ? t('BLOKOVÁNO', 'BLOCKED') : t('POVOLENO', 'ALLOWED'),
            sub: t('Tier 4 platební služby', 'Tier 4 payment services'),
            color: paymentsBlocked ? '#dc2626' : '#16a34a',
            bg: paymentsBlocked ? '#fee2e2' : '#dcfce7',
          },
          {
            label: t('Cílový RTO (full stack)', 'Target RTO (full stack)'),
            value: '< 8 min',
            sub: t('Předpřipravené Docker images', 'Pre-built Docker images'),
            color: '#2563eb',
            bg: '#eff6ff',
          },
        ].map(stat => (
          <div key={stat.label} className="card" style={{ padding: '16px' }}>
            <div style={{ fontSize: '11px', color: 'var(--text-secondary)', marginBottom: '6px', textTransform: 'uppercase', letterSpacing: '0.05em' }}>{stat.label}</div>
            <div style={{ fontSize: '20px', fontWeight: 800, color: stat.color, marginBottom: '4px' }}>{stat.value}</div>
            <div style={{ fontSize: '11px', color: 'var(--text-secondary)' }}>{stat.sub}</div>
          </div>
        ))}
      </div>

      {paymentsBlocked && (
        <div style={{
          background: '#fee2e2', border: '1px solid #fecaca', borderRadius: '10px',
          padding: '14px 18px', marginBottom: '20px',
          display: 'flex', alignItems: 'center', gap: '10px',
        }}>
          <AlertTriangle size={18} style={{ color: '#dc2626', flexShrink: 0 }} />
          <div>
            <div style={{ fontWeight: 700, color: '#b91c1c', fontSize: '14px' }}>
              {t('⛔ Compliance gate selhala — platební zpracování BLOKOVÁNO', '⛔ Compliance gate failed — payment processing BLOCKED')}
            </div>
            <div style={{ fontSize: '12px', color: '#991b1b', marginTop: '2px' }}>
              {t('AML, Sanctions nebo Balance service není healthy. Platby nesmí být zpracovávány. Regulatorní základ: 5AMLD Art. 18, DORA Art. 12.', 'AML, Sanctions or Balance service is not healthy. Payments must not be processed. Regulatory basis: 5AMLD Art. 18, DORA Art. 12.')}
            </div>
          </div>
        </div>
      )}

      {/* Completeness cross-check: catalog services not assigned to any tier */}
      {unclassified.length > 0 && (
        <div className="card" style={{
          marginBottom: '24px', padding: '12px 16px', borderLeft: '4px solid var(--warning, #d97706)',
          display: 'flex', alignItems: 'flex-start', gap: '10px',
        }}>
          <AlertTriangle size={16} style={{ color: 'var(--warning, #d97706)', flexShrink: 0, marginTop: '2px' }} />
          <div style={{ fontSize: '12px', color: 'var(--text-secondary)' }}>
            {t(
              `${unclassified.length} služeb z katalogu zatím není zařazeno do žádné tieru kontinuity (ADR-0029 cross-check): `,
              `${unclassified.length} catalog service(s) are not yet assigned to any continuity tier (ADR-0029 cross-check): `,
            )}
            <strong style={{ fontFamily: 'monospace', color: 'var(--text-primary)' }}>{unclassified.join(', ')}</strong>
            {t(' — doplň je do RTO/RPO modelu níže.', ' — classify them in the RTO/RPO model below.')}
          </div>
        </div>
      )}

      {/* Startup tiers */}
      <div style={{ marginBottom: '32px' }}>
        <h2 style={{ fontSize: '15px', fontWeight: 700, color: 'var(--text-primary)', marginBottom: '12px', display: 'flex', alignItems: 'center', gap: '8px' }}>
          <Zap size={15} style={{ color: 'var(--accent)' }} />
          {t('Prioritizované pořadí startu', 'Prioritized startup order')}
        </h2>

        <div style={{ display: 'flex', flexDirection: 'column', gap: '8px' }}>
          {TIERS.map((tier, idx) => {
            const { status, healthy, total } = getTierStatus(tier)
            const expanded = expandedTiers.has(tier.id)
            const TierIcon = tier.icon

            return (
              <div key={tier.id} className="card" style={{ padding: 0, overflow: 'hidden', borderLeft: `4px solid ${tier.color}` }}>
                {/* Tier header */}
                <div
                  onClick={() => toggleTier(tier.id)}
                  style={{
                    display: 'flex', alignItems: 'center', gap: '12px',
                    padding: '14px 18px', cursor: 'pointer',
                    background: expanded ? 'var(--surface-2)' : 'transparent',
                  }}
                >
                  {/* Tier number */}
                  <div style={{
                    width: '28px', height: '28px', borderRadius: '50%',
                    background: tier.color, color: '#fff',
                    display: 'flex', alignItems: 'center', justifyContent: 'center',
                    fontSize: '12px', fontWeight: 800, flexShrink: 0,
                  }}>
                    {tier.id}
                  </div>

                  <TierIcon size={15} style={{ color: tier.color, flexShrink: 0 }} />

                  <div style={{ flex: 1, minWidth: 0 }}>
                    <div style={{ display: 'flex', alignItems: 'center', gap: '8px', flexWrap: 'wrap' }}>
                      <span style={{ fontSize: '14px', fontWeight: 700, color: 'var(--text-primary)' }}>{t(...tier.label)}</span>
                      {(tier as any).isComplianceGate && (
                        <span style={{ fontSize: '10px', fontWeight: 700, padding: '2px 7px', borderRadius: '20px', background: '#fee2e2', color: '#b91c1c', border: '1px solid #fecaca' }}>
                          {t('COMPLIANCE GATE', 'COMPLIANCE GATE')}
                        </span>
                      )}
                    </div>
                    <div style={{ fontSize: '11px', color: 'var(--text-secondary)', marginTop: '2px' }}>{t(...tier.regulatoryBasis)}</div>
                  </div>

                  {/* RTO/RPO */}
                  <div style={{ display: 'flex', gap: '16px', flexShrink: 0 }}>
                    <div style={{ textAlign: 'center' }}>
                      <div style={{ fontSize: '10px', color: 'var(--text-secondary)', textTransform: 'uppercase' }}>RTO</div>
                      <div style={{ fontSize: '13px', fontWeight: 700, color: 'var(--text-primary)' }}>{tier.rto}</div>
                    </div>
                    <div style={{ textAlign: 'center' }}>
                      <div style={{ fontSize: '10px', color: 'var(--text-secondary)', textTransform: 'uppercase' }}>RPO</div>
                      <div style={{ fontSize: '13px', fontWeight: 700, color: 'var(--text-primary)' }}>{tier.rpo}</div>
                    </div>
                    <div style={{ textAlign: 'center' }}>
                      <div style={{ fontSize: '10px', color: 'var(--text-secondary)', textTransform: 'uppercase' }}>{t('Priorita', 'Priority')}</div>
                      <div style={{ fontSize: '13px', fontWeight: 700, color: tier.color }}>{tier.priority}</div>
                    </div>
                  </div>

                  {/* Health summary */}
                  <div style={{ display: 'flex', alignItems: 'center', gap: '8px', flexShrink: 0 }}>
                    <StatusIcon status={status} size={16} />
                    <span style={{ ...statusBadgeStyle(status), fontSize: '11px', fontWeight: 600, padding: '3px 8px', borderRadius: '20px' }}>
                      {loading ? '…' : `${healthy}/${total}`}
                    </span>
                  </div>

                  {expanded ? <ChevronDown size={16} style={{ color: 'var(--text-secondary)', flexShrink: 0 }} /> : <ChevronRight size={16} style={{ color: 'var(--text-secondary)', flexShrink: 0 }} />}
                </div>

                {/* Services list */}
                {expanded && (
                  <div style={{ borderTop: '1px solid var(--border)', padding: '12px 18px' }}>
                    {/* Startup sequence note */}
                    {idx > 0 && (
                      <div style={{ fontSize: '11px', color: 'var(--text-secondary)', marginBottom: '10px', padding: '6px 10px', background: 'var(--surface-3)', borderRadius: '6px' }}>
                        {t(`⏱ Startuje po Tier ${tier.id - 1} — všechny služby v tomto tieru startují `, `⏱ Starts after Tier ${tier.id - 1} — all services in this tier start `)}<strong>{t('paralelně', 'in parallel')}</strong>
                        {tier.id === 3 && t(' (výjimka: psd2-service čeká na consent-service healthy)', ' (exception: psd2-service waits for consent-service healthy)')}
                      </div>
                    )}
                    <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(280px, 1fr))', gap: '8px' }}>
                      {tier.services.map(svc => {
                        const svcStatus = health[svc.name] ?? 'unknown'
                        return (
                          <div key={svc.name} style={{
                            display: 'flex', alignItems: 'flex-start', gap: '10px',
                            padding: '10px 12px', borderRadius: '8px',
                            background: 'var(--surface-2)', border: '1px solid var(--border)',
                          }}>
                            <StatusIcon status={svcStatus} size={14} />
                            <div style={{ flex: 1, minWidth: 0 }}>
                              <div style={{ fontSize: '13px', fontWeight: 600, color: 'var(--text-primary)' }}>{svc.label}</div>
                              <div style={{ fontSize: '11px', color: 'var(--text-secondary)', marginTop: '2px' }}>{t(...svc.note)}</div>
                              <div style={{ fontSize: '10px', color: 'var(--text-tertiary)', marginTop: '2px', fontFamily: 'monospace' }}>{svc.name}</div>
                            </div>
                            <span style={{ ...statusBadgeStyle(svcStatus), fontSize: '10px', fontWeight: 600, padding: '2px 7px', borderRadius: '20px', flexShrink: 0 }}>
                              {loading ? '…' : statusLabel(svcStatus)}
                            </span>
                          </div>
                        )
                      })}
                    </div>
                  </div>
                )}
              </div>
            )
          })}
        </div>
      </div>

      {/* Incident classification */}
      <div style={{ marginBottom: '32px' }}>
        <h2 style={{ fontSize: '15px', fontWeight: 700, color: 'var(--text-primary)', marginBottom: '12px', display: 'flex', alignItems: 'center', gap: '8px' }}>
          <AlertTriangle size={15} style={{ color: '#d97706' }} />
          {t('Klasifikace incidentů (DORA Art. 17)', 'Incident classification (DORA Art. 17)')}
        </h2>
        <div style={{ display: 'flex', flexDirection: 'column', gap: '8px' }}>
          {INCIDENTS.map(inc => (
            <div key={inc.severity} className="card" style={{ padding: '14px 18px', borderLeft: `4px solid ${inc.color}` }}>
              <div style={{ display: 'grid', gridTemplateColumns: '80px 1fr 120px 200px', gap: '16px', alignItems: 'center' }}>
                <span style={{ fontSize: '13px', fontWeight: 800, color: inc.color }}>{inc.severity} — {t(...inc.label)}</span>
                <span style={{ fontSize: '13px', color: 'var(--text-primary)' }}>{t(...inc.criteria)}</span>
                <span style={{ fontSize: '13px', fontWeight: 600, color: 'var(--text-primary)' }}>{t(...inc.response)}</span>
                <span style={{ fontSize: '12px', color: 'var(--text-secondary)' }}>{t(...inc.reporting)}</span>
              </div>
            </div>
          ))}
        </div>
        <div style={{ fontSize: '11px', color: 'var(--text-secondary)', marginTop: '8px', padding: '8px 12px', background: 'var(--surface-2)', borderRadius: '6px' }}>
          <strong>{t('DORA Art. 17 — Práh významného incidentu:', 'DORA Art. 17 — Major incident threshold:')}</strong> {t('> 4h výpadek NEBO > 10 % transakcí zasaženo NEBO > EUR 1M dopad → hlášení CNB do 24h, závěrečná zpráva do 1 měsíce.', '> 4h downtime OR > 10 % of transactions affected OR > EUR 1M impact → report to CNB within 24h, final report within 1 month.')}
        </div>
      </div>

      {/* Test schedule */}
      <div style={{ marginBottom: '32px' }}>
        <h2 style={{ fontSize: '15px', fontWeight: 700, color: 'var(--text-primary)', marginBottom: '12px', display: 'flex', alignItems: 'center', gap: '8px' }}>
          <BookOpen size={15} style={{ color: 'var(--accent)' }} />
          {t('Plán testování BCP (DORA Art. 11)', 'BCP testing schedule (DORA Art. 11)')}
        </h2>
        <div style={{ overflowX: 'auto' }}>
          <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: '13px' }}>
            <thead>
              <tr style={{ background: 'var(--surface-2)' }}>
                {[t('Typ testu', 'Test type'), t('Frekvence', 'Frequency'), t('Rozsah', 'Scope'), t('Vlastník', 'Owner')].map(h => (
                  <th key={h} style={{ padding: '10px 14px', textAlign: 'left', fontWeight: 600, color: 'var(--text-secondary)', fontSize: '11px', textTransform: 'uppercase', borderBottom: '1px solid var(--border)' }}>{h}</th>
                ))}
              </tr>
            </thead>
            <tbody>
              {([
                { type: ['Cold start drill', 'Cold start drill'], freq: ['Měsíčně', 'Monthly'], scope: ['Celý stack od nuly', 'Full stack from scratch'], owner: ['Platform Engineering', 'Platform Engineering'] },
                { type: ['Tier 0 failover', 'Tier 0 failover'], freq: ['Čtvrtletně', 'Quarterly'], scope: ['Postgres/Kafka restart s daty', 'Postgres/Kafka restart with data'], owner: ['Platform Engineering', 'Platform Engineering'] },
                { type: ['Obnova platebních služeb', 'Payment service recovery'], freq: ['Čtvrtletně', 'Quarterly'], scope: ['Tier 4 restart s aktivními transakcemi', 'Tier 4 restart with active transactions'], owner: ['Payments Team', 'Payments Team'] },
                { type: ['Plná simulace DR', 'Full DR simulation'], freq: ['Pololetně', 'Semi-annually'], scope: ['Kompletní rebuild prostředí', 'Complete environment rebuild'], owner: ['Všechny týmy', 'All teams'] },
                { type: ['TLPT (Threat-Led Penetration Test)', 'TLPT (Threat-Led Penetration Test)'], freq: ['Ročně', 'Annually'], scope: ['Externí red team', 'External red team'], owner: ['Security', 'Security'] },
              ] as { type: Bilingual; freq: Bilingual; scope: Bilingual; owner: Bilingual }[]).map((row, i) => (
                <tr key={i} style={{ borderBottom: '1px solid var(--border)' }}>
                  <td style={{ padding: '10px 14px', fontWeight: 600, color: 'var(--text-primary)' }}>{t(...row.type)}</td>
                  <td style={{ padding: '10px 14px', color: 'var(--text-secondary)' }}>{t(...row.freq)}</td>
                  <td style={{ padding: '10px 14px', color: 'var(--text-secondary)' }}>{t(...row.scope)}</td>
                  <td style={{ padding: '10px 14px', color: 'var(--text-secondary)' }}>{t(...row.owner)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        <div style={{ fontSize: '11px', color: 'var(--text-secondary)', marginTop: '8px' }}>
          {t('Záznamy z testů musí být uchovávány ', 'Test records must be retained for ')}<strong>{t('5 let', '5 years')}</strong>{t(' (DORA Art. 11(6)).', ' (DORA Art. 11(6)).')}
        </div>
      </div>
      <CatalogDriftBanner present={CATALOG_PRESENT} />
    </div>
  )
}
