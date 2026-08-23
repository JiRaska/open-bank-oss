// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

import { useState, useEffect, useCallback } from 'react'
import {
  RefreshCw, DollarSign, Layers, Server, Workflow, Info, ArrowLeft,
} from 'lucide-react'
import Link from 'next/link'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { AuthGuard } from '@/components/auth/AuthGuard'
import { DataUnavailable } from '@/components/feedback/DataUnavailable'
import type { UnavailableKind } from '@/components/feedback/DataUnavailable'
import { PageHeader } from '@/components/ui/PageHeader'

// Cost-allocation (showback) view — ADR-0062. Reads /api/finops/allocation, which joins the AWS
// cost snapshot with build-time resource footprints and rolls spend up service -> domain -> flow.

interface ServiceAllocation {
  service: string; domain: string; amount: number; pct: number; cpuMillis: number; memMiB: number
}
interface DomainAllocation { domain: string; amount: number; pct: number; serviceCount: number }
interface FlowAllocation { id: string; labelEn: string; labelCs: string; amount: number; pct: number; services: string[]; regulatoryRef?: string }
interface AllocationResult {
  available: boolean
  currency: string
  periodStart: string
  periodEnd: string
  total: number
  allocatable: number
  platformOverhead: number
  byService: ServiceAllocation[]
  byDomain: DomainAllocation[]
  byFlow: FlowAllocation[]
  unmapped: string[]
  method: string
  collectedAt: string | null
}

const DOMAIN_COLOR: Record<string, string> = {
  core: '#6366f1', payments: '#16a34a', compliance: '#dc2626',
  identity: '#d97706', 'open-banking': '#0891b2', platform: '#7c3aed',
}

function money(n: number, locale: string): string {
  return n.toLocaleString(locale, { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}

function BarRow({ label, sub, amount, pct, color, currency, numberLocale }: {
  label: string; sub?: string; amount: number; pct: number; color: string; currency: string; numberLocale: string
}) {
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: '12px' }}>
      <div style={{ width: '220px', flexShrink: 0, overflow: 'hidden' }}>
        <div style={{ fontSize: '12px', color: 'var(--text-secondary)', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{label}</div>
        {sub && <div style={{ fontSize: '10px', color: 'var(--text-tertiary)' }}>{sub}</div>}
      </div>
      <div style={{ flex: 1, height: '8px', background: 'var(--surface-3)', borderRadius: '4px', overflow: 'hidden', minWidth: '80px' }}>
        <div style={{ width: `${Math.min(Math.max(pct, 1), 100)}%`, height: '100%', background: color, borderRadius: '4px' }} />
      </div>
      <span style={{ fontSize: '11px', color: 'var(--text-tertiary)', minWidth: '44px', textAlign: 'right' }}>{pct.toFixed(0)}%</span>
      <span style={{ fontSize: '12px', fontWeight: 700, color: 'var(--text-primary)', fontFamily: 'monospace', minWidth: '82px', textAlign: 'right' }}>
        ${money(amount, numberLocale)} {currency}
      </span>
    </div>
  )
}

function Kpi({ icon, label, value, sub, color }: {
  icon: React.ReactNode; label: string; value: string; sub: string; color: string
}) {
  return (
    <div className="stat-card">
      <div style={{ width: '36px', height: '36px', borderRadius: '10px', background: `${color}18`,
        display: 'flex', alignItems: 'center', justifyContent: 'center', color, marginBottom: '12px' }}>{icon}</div>
      <div style={{ fontSize: '24px', fontWeight: 800, color: 'var(--text-primary)', letterSpacing: '-0.03em' }}>{value}</div>
      <div style={{ fontSize: '12px', fontWeight: 600, color: 'var(--text-secondary)' }}>{label}</div>
      <div style={{ fontSize: '11px', color: 'var(--text-tertiary)' }}>{sub}</div>
    </div>
  )
}

function Section({ icon, title, hint, children }: {
  icon: React.ReactNode; title: string; hint?: string; children: React.ReactNode
}) {
  return (
    <div style={{ background: 'var(--surface)', border: '1px solid var(--border)', borderRadius: 'var(--r-lg)', padding: '20px 24px', marginBottom: '20px' }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: '10px', marginBottom: hint ? '4px' : '16px' }}>
        {icon}
        <span style={{ fontSize: '14px', fontWeight: 700, color: 'var(--text-primary)' }}>{title}</span>
      </div>
      {hint && <p style={{ fontSize: '11px', color: 'var(--text-tertiary)', margin: '0 0 16px' }}>{hint}</p>}
      <div style={{ display: 'flex', flexDirection: 'column', gap: '6px' }}>{children}</div>
    </div>
  )
}

function AllocationContent() {
  const { t, language } = useLanguage()
  const numberLocale = language === 'cs' ? 'cs-CZ' : 'en-GB'
  const [data, setData] = useState<AllocationResult | null>(null)
  const [loading, setLoading] = useState(true)
  const [unavailable, setUnavailable] = useState<{ kind: UnavailableKind } | null>(null)

  const load = useCallback(async () => {
    setLoading(true)
    setUnavailable(null)
    try {
      const res = await fetch('/api/finops/allocation', { cache: 'no-store' })
      if (!res.ok) { setUnavailable({ kind: 'error' }); return }
      const json: AllocationResult = await res.json()
      if (!json.available) { setUnavailable({ kind: 'no_data' }); return }
      setData(json)
    } catch {
      setUnavailable({ kind: 'unreachable' })
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => { load() }, [load])

  const domainLabel = (d: string): string => ({
    core: t('Jádro', 'Core'),
    payments: t('Platby', 'Payments'),
    compliance: t('Compliance', 'Compliance'),
    identity: t('Identita', 'Identity'),
    'open-banking': t('Open Banking', 'Open Banking'),
    platform: t('Platforma', 'Platform'),
  }[d] ?? d)

  return (
    <div style={{ padding: '28px 32px', maxWidth: '1400px', animation: 'fadeIn 0.2s ease-out' }}>
      <PageHeader
        breadcrumb={<div className="breadcrumb"><span>OpenBank</span><span className="breadcrumb-sep">/</span><Link href="/finops" style={{ color: 'inherit', textDecoration: 'none' }}>FinOps</Link><span className="breadcrumb-sep">/</span><span className="breadcrumb-current">{t('Rozpad nákladů', 'Cost Allocation')}</span></div>}
        icon={<DollarSign size={20} aria-hidden="true" />}
        title={t('Rozpad nákladů', 'Cost Allocation')}
        subtitle={t('Cloud útraty rozpočítané po službě, doméně a business procesu — showback dle požadovaných zdrojů (ADR-0062)', 'Cloud spend split by service, domain and business flow — requests-weighted showback (ADR-0062)')}
        actions={<div style={{ display: 'flex', alignItems: 'center', gap: '12px' }}>
          <Link href="/finops" style={{ display: 'flex', alignItems: 'center', gap: '6px', padding: '7px 14px',
            borderRadius: '8px', border: '1px solid var(--border)', background: 'var(--surface)',
            color: 'var(--text-secondary)', fontSize: '12px', textDecoration: 'none' }}>
            <ArrowLeft size={13} /> {t('Zpět na FinOps', 'Back to FinOps')}
          </Link>
          <button type="button" onClick={load} disabled={loading} aria-busy={loading} aria-label={t('Obnovit rozpad nákladů', 'Refresh cost allocation')}
            style={{ display: 'flex', alignItems: 'center', gap: '6px', padding: '7px 14px', borderRadius: '8px',
              border: '1px solid var(--border)', background: 'var(--surface)', color: 'var(--text-secondary)',
              fontSize: '12px', cursor: loading ? 'wait' : 'pointer' }}>
            <RefreshCw size={13} aria-hidden="true" style={{ animation: loading ? 'spin 0.8s linear infinite' : 'none' }} />
            {t('Obnovit', 'Refresh')}
          </button>
        </div>}
      />

      {loading && !data ? (
        <div role="status" aria-live="polite" style={{ display: 'flex', alignItems: 'center', gap: '10px', padding: '40px', color: 'var(--text-tertiary)' }}>
          <RefreshCw size={16} aria-hidden="true" style={{ animation: 'spin 0.8s linear infinite' }} />
          <span style={{ fontSize: '13px' }}>{t('Počítám rozpad nákladů…', 'Computing cost allocation…')}</span>
        </div>
      ) : unavailable ? (
        <DataUnavailable kind={unavailable.kind} service="finops" feature={t('Rozpad nákladů', 'Cost allocation')} lang={language} />
      ) : data ? (
        <>
          <div className="grid-4" style={{ marginBottom: '28px' }}>
            <Kpi icon={<DollarSign size={18} />} color="#16a34a"
              label={t('Celkové cloud náklady', 'Total cloud spend')}
              value={`$${money(data.total, numberLocale)}`}
              sub={`${data.currency} · ${data.periodStart}→${data.periodEnd}`} />
            <Kpi icon={<Server size={18} />} color="#6366f1"
              label={t('Rozpočítatelný compute', 'Allocatable compute')}
              value={`$${money(data.allocatable, numberLocale)}`}
              sub={t(`${data.byService.length} služeb`, `${data.byService.length} services`)} />
            <Kpi icon={<Layers size={18} />} color="#7c3aed"
              label={t('Platformní režie', 'Platform overhead')}
              value={`$${money(data.platformOverhead, numberLocale)}`}
              sub={t('Control plane, NAT, LB, úložiště', 'Control plane, NAT, LB, storage')} />
            <Kpi icon={<Workflow size={18} />} color="#0891b2"
              label={t('Business procesy', 'Business flows')}
              value={`${data.byFlow.length}`}
              sub={t('s přiřazenými náklady', 'with attributed cost')} />
          </div>

          <div style={{ display: 'flex', alignItems: 'flex-start', gap: '8px', padding: '12px 16px', borderRadius: '8px',
            background: 'var(--surface-2)', border: '1px solid var(--border)', marginBottom: '20px' }}>
            <Info size={14} style={{ color: 'var(--text-tertiary)', flexShrink: 0, marginTop: '2px' }} />
            <span style={{ fontSize: '12px', color: 'var(--text-secondary)', lineHeight: 1.5 }}>
              {t(
                'Sdílený compute se rozpočítává mezi služby podle deklarovaných CPU/RAM požadavků (gitops). Platformní režie se nealokuje. U procesů se služby překrývají — součet přes procesy proto může přesáhnout 100 % (fully-loaded náklad na běh procesu).',
                'The shared compute pool is split across services by their declared CPU/RAM requests (gitops). Platform overhead is not allocated. Across business flows services overlap, so the per-flow sum can exceed 100% (fully-loaded cost to run each flow).',
              )}
            </span>
          </div>

          {data.unmapped.length > 0 && (
            <div style={{ display: 'flex', alignItems: 'center', gap: '8px', padding: '10px 14px', borderRadius: '8px',
              background: '#fef9c3', border: '1px solid #fde047', marginBottom: '20px' }}>
              <Info size={14} style={{ color: '#d97706', flexShrink: 0 }} />
              <span style={{ fontSize: '12px', color: '#92400e' }}>
                {t(
                  `${data.unmapped.length} běžících komponent není v governance manifestu (infra) — alokováno pod doménou „Platforma": ${data.unmapped.join(', ')}`,
                  `${data.unmapped.length} running components are not in the governance manifest (infra) — allocated under the "Platform" domain: ${data.unmapped.join(', ')}`,
                )}
              </span>
            </div>
          )}

          <Section icon={<Server size={16} style={{ color: '#6366f1' }} />} title={t('Podle služby', 'By service')}>
            {data.byService.map(s => (
              <BarRow key={s.service} label={s.service} sub={`${domainLabel(s.domain)} · ${s.cpuMillis}m · ${s.memMiB}Mi`}
                amount={s.amount} pct={s.pct} color={DOMAIN_COLOR[s.domain] ?? '#6366f1'} currency={data.currency} numberLocale={numberLocale} />
            ))}
          </Section>

          <Section icon={<Layers size={16} style={{ color: '#7c3aed' }} />}
            title={t('Podle domény (cost-center)', 'By domain (cost-center)')}>
            {data.byDomain.map(d => (
              <BarRow key={d.domain} label={domainLabel(d.domain)}
                sub={t(`${d.serviceCount} služeb`, `${d.serviceCount} services`)}
                amount={d.amount} pct={d.pct} color={DOMAIN_COLOR[d.domain] ?? '#6366f1'} currency={data.currency} numberLocale={numberLocale} />
            ))}
          </Section>

          <Section icon={<Workflow size={16} style={{ color: '#0891b2' }} />}
            title={t('Podle business procesu (fully-loaded)', 'By business flow (fully-loaded)')}
            hint={t(
              'Náklad na provoz procesu end-to-end včetně sdílených závislostí. Procesy sdílejí služby, proto se součty překrývají.',
              'Cost to run each flow end-to-end including shared dependencies. Flows share services, so the sums overlap.',
            )}>
            {data.byFlow.map(f => (
              <BarRow key={f.id} label={language === 'cs' ? f.labelCs : f.labelEn}
                sub={f.regulatoryRef ?? t(`${f.services.length} služeb`, `${f.services.length} services`)}
                amount={f.amount} pct={f.pct} color="#0891b2" currency={data.currency} numberLocale={numberLocale} />
            ))}
          </Section>
        </>
      ) : null}
    </div>
  )
}

export default function CostAllocationPage() {
  return (
    <AuthGuard permission="system:view">
      <AllocationContent />
    </AuthGuard>
  )
}
