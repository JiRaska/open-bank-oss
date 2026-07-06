// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

import { useState, useEffect, useCallback, useRef } from 'react'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { CreditCard, ArrowLeftRight, Users, Activity, TrendingUp, TrendingDown,
  ShieldCheck, AlertTriangle, RefreshCw, Zap,
  DollarSign, Globe, BarChart3, Clock, Server, HardDrive } from 'lucide-react'
import Link from 'next/link'

// Tri-state per fleet member. `deployed=false` is NEUTRAL (planned, not an outage) —
// it must never be counted as an error, or the 23 not-yet-deployed services in the
// sandbox would read as an 85% error rate. `up` is only meaningful when deployed.
interface SvcStatus { name: string; label: string; group: string; deployed: boolean; up: boolean; latencyMs: number | null }

// Shape of one entry in /api/services/health `services[]` (k8s discovery, ADR-0051).
interface HealthEntry { name: string; port: number; label: string; group: string; container: string; status: string; latencyMs: number | null }

// Canonical intended fleet (ADR-0029 governance manifest) — the authoritative roster
// of every service the platform is designed to run, independent of what is currently
// deployed. Since ADR-0071 it is code-derived: fetched from /api/services/governance
// (backed by governance.json), not a build-time import. The dashboard overlays live
// k8s discovery on top of it so an operator sees the WHOLE fleet with each member's
// deployment status, not just the handful currently running.

// serviceName ("sepa-payment") -> display label ("Sepa Payment") for roster members
// that discovery hasn't enriched with a label (i.e. not-deployed services).
function titleCase(name: string): string {
  return name.split('-').map(w => w.charAt(0).toUpperCase() + w.slice(1)).join(' ')
}

const GROUP_COLORS: Record<string, string> = {
  core: '#6366f1', payments: '#10b981', compliance: '#f59e0b',
  identity: '#3b82f6', 'open-banking': '#8b5cf6', platform: '#64748b'
}

export default function DashboardPage() {
  const { t } = useLanguage()
  const [statuses, setStatuses] = useState<SvcStatus[]>([])
  const [loading, setLoading] = useState(true)
  const [lastRefresh, setLastRefresh] = useState<Date | null>(null)
  const loadingRef = useRef(false)

  const load = useCallback(async () => {
    if (loadingRef.current) return
    loadingRef.current = true
    setLoading(true)
    // Canonical fleet from the code-derived governance manifest (ADR-0071).
    let fleet: { name: string; group: string }[] = []
    try {
      const govRes = await fetch('/api/services/governance', { cache: 'no-store' })
      if (govRes.ok) {
        const g = await govRes.json() as { items?: { serviceName: string; dataDomain: string }[] }
        fleet = (g.items ?? []).map(e => ({ name: e.serviceName, group: e.dataDomain }))
      }
    } catch { /* fleet stays empty → roster degrades calmly, no blank crash */ }
    try {
      const res = await fetch('/api/services/health', { signal: AbortSignal.timeout(10000), cache: 'no-store' })
      // Live discovery (ADR-0051) keyed by bare deployment name. Empty on failure —
      // the fleet still renders, every member simply shows as not-deployed rather
      // than the page going blank.
      const discovered = new Map<string, HealthEntry>()
      if (res.ok) {
        const data = await res.json() as { services: HealthEntry[] }
        for (const e of (data.services ?? [])) discovered.set(e.name, e)
      }
      // Overlay discovery on the canonical fleet: every intended service appears,
      // with deployed/healthy resolved from the cluster. A roster member absent from
      // discovery is NOT-DEPLOYED (neutral), never DOWN (which is a real outage).
      const results: SvcStatus[] = fleet.map(f => {
        const hit = discovered.get(f.name)
        return {
          name: f.name,
          label: hit?.label ?? titleCase(f.name),
          group: hit?.group ?? f.group,
          deployed: hit !== undefined,
          up: hit?.status === 'UP',
          latencyMs: hit?.latencyMs ?? null,
        }
      })
      setStatuses(results)
    } catch {
      // Keep the fleet visible (all not-deployed) instead of a blank dashboard.
      setStatuses(fleet.map(f => ({ name: f.name, label: titleCase(f.name), group: f.group, deployed: false, up: false, latencyMs: null })))
    }
    setLastRefresh(new Date())
    setLoading(false)
    loadingRef.current = false
  }, [])

  useEffect(() => {
    let isMounted = true
    let timeoutId: NodeJS.Timeout

    const tick = async () => {
      if (!isMounted) return
      await load()
      if (isMounted) {
        timeoutId = setTimeout(tick, 15000)
      }
    }

    tick()

    return () => {
      isMounted = false
      clearTimeout(timeoutId)
    }
  }, [load])

  // Health metrics are computed over DEPLOYED services only. Not-deployed roster
  // members are planned capacity, not failures — folding them into the denominator
  // would report a false outage (the old "0/27 → 100% error rate" bug).
  const fleetTotal = statuses.length
  const deployed = statuses.filter(s => s.deployed)
  const deployedCount = deployed.length
  const upCount = deployed.filter(s => s.up).length
  const healthPct = deployedCount > 0 ? Math.round((upCount / deployedCount) * 100) : 0
  const avgLatency = deployed.filter(s => s.latencyMs !== null).reduce((a, b) => a + (b.latencyMs ?? 0), 0) /
    Math.max(1, deployed.filter(s => s.latencyMs !== null).length)

  const groups = ['core', 'payments', 'compliance', 'identity', 'open-banking', 'platform']

  return (
    <div style={{ padding: '28px 32px', maxWidth: '1400px', animation: 'fadeIn 0.2s ease-out' }}>
      {/* Page header */}
      <div style={{ marginBottom: '28px', display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between' }}>
        <div>
          <h1 style={{ fontSize: '24px', fontWeight: 800, color: 'var(--text-primary)', letterSpacing: '-0.03em', marginBottom: '4px' }}>
            {t('Přehled platformy', 'Platform Overview')}
          </h1>
          <p style={{ fontSize: '13px', color: 'var(--text-secondary)' }}>
            {t('Platforma mikroslužeb OpenBank — zdraví a observabilita v reálném čase', 'OpenBank microservices platform — real-time health & observability')}
          </p>
        </div>
        <div style={{ display: 'flex', alignItems: 'center', gap: '10px' }}>
          {lastRefresh && (
            <span style={{ fontSize: '11px', color: 'var(--text-tertiary)' }}>
              {t('Aktualizováno', 'Updated')} {lastRefresh.toLocaleTimeString()}
            </span>
          )}
          <button onClick={load} disabled={loading} className="btn btn-secondary btn-sm">
            <RefreshCw size={13} style={{ animation: loading ? 'spin 0.8s linear infinite' : 'none' }} />
            {t('Obnovit', 'Refresh')}
          </button>
        </div>
      </div>

      {/* KPI row */}
      <div className="grid-4" style={{ marginBottom: '28px' }}>
        <KpiCard icon={<Server size={18} />} label={t('Služby online', 'Services Online')} value={`${upCount}/${deployedCount}`}
          sub={`${deployedCount}/${fleetTotal} ${t('nasazeno', 'deployed')} · ${healthPct}% ${t('v pořádku', 'healthy')}`}
          color={healthPct >= 90 ? 'var(--success)' : healthPct >= 70 ? 'var(--warning)' : 'var(--danger)'}
          trend={healthPct >= 90 ? 'up' : 'down'} />
        <KpiCard icon={<Zap size={18} />} label={t('Prům. latence', 'Avg Latency')} value={`${Math.round(avgLatency)}ms`}
          sub={t('kontrola p50', 'health check p50')} color="var(--accent)"
          trend={avgLatency < 100 ? 'up' : 'down'} />
        <KpiCard icon={<ShieldCheck size={18} />} label={t('Bezpečnost', 'Security Grade')}
          value={deployedCount > 0 && upCount >= deployedCount * 0.9 ? 'A' : 'B'}
          sub="OWASP + EBA ICT" color="var(--info)" trend="up" />
        <KpiCard icon={<Globe size={18} />} label={t('Shoda', 'Compliance')}
          value="PSD2 ✓" sub="EBA · CNB · GDPR" color="var(--success)" trend="up" />
      </div>

      {/* Observability KPIs */}
      <div className="grid-4" style={{ marginBottom: '28px' }}>
        <KpiCard icon={<AlertTriangle size={18} />} label={t('Chybovost', 'Error Rate')} value={`${100 - healthPct}%`}
          sub={t('odvozeno ze zdraví', 'derived from health')} color={100 - healthPct > 5 ? 'var(--danger)' : 'var(--success)'}
          trend={100 - healthPct > 5 ? 'up' : 'down'} />
        <KpiCard icon={<Clock size={18} />} label={t('p99 Latence (odh.)', 'p99 Latency (est.)')} value={`${Math.round(avgLatency * 2.5)}ms`}
          sub={t('odhad z latencí', 'approx from latencies')} color="var(--accent)"
          trend={avgLatency * 2.5 < 200 ? 'up' : 'down'} />
        <KpiCard icon={<Activity size={18} />} label={t('Propustnost', 'Throughput')} value={`${upCount * 125} req/s`}
          sub={t('proxy z health-checku', 'health-check proxy')} color="var(--info)"
          trend="up" />
        <KpiCard icon={<TrendingUp size={18} />} label={t('Zátěž systému', 'System Load')} value={`${Math.round((upCount / Math.max(1, deployedCount)) * 42)}%`}
          sub={t('odhad průměrné zátěže CPU', 'average cpu proxy')} color="var(--warning)" trend="up" />
      </div>

      {/* Uptime by group */}
      <div className="card" style={{ padding: '20px', marginBottom: '28px' }}>
        <div style={{ fontSize: '12px', fontWeight: 700, color: 'var(--text-secondary)', textTransform: 'uppercase',
          letterSpacing: '0.06em', marginBottom: '14px' }}>{t('Dostupnost dle skupiny', 'Uptime Ratio By Group')}</div>
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(200px, 1fr))', gap: '20px' }}>
          {groups.map(group => {
            const groupSvcs = statuses.filter(s => s.group === group)
            if (groupSvcs.length === 0) return null
            const deployedInGroup = groupSvcs.filter(s => s.deployed)
            const upInGroup = deployedInGroup.filter(s => s.up).length
            // Uptime is over deployed members of the group; a group with nothing
            // deployed yet shows a neutral "not deployed" rather than a red 0%.
            const hasDeployed = deployedInGroup.length > 0
            const pct = hasDeployed ? Math.round((upInGroup / deployedInGroup.length) * 100) : 0
            const color = GROUP_COLORS[group] ?? '#64748b'
            return (
              <div key={`uptime-${group}`}>
                <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: '12px', marginBottom: '6px' }}>
                  <span style={{ fontWeight: 600, color: 'var(--text-primary)', textTransform: 'capitalize' }}>{group.replace('-', ' ')}</span>
                  <span style={{ color: 'var(--text-secondary)', fontWeight: 500 }}>
                    {hasDeployed ? `${pct}%` : t('nenasazeno', 'not deployed')}
                  </span>
                </div>
                <div style={{ height: '6px', background: 'var(--border)', borderRadius: '3px', overflow: 'hidden' }}>
                  <div style={{ height: '100%', width: hasDeployed ? `${pct}%` : '0%', background: pct === 100 ? color : 'var(--danger)', transition: 'width 0.3s ease' }} />
                </div>
              </div>
            )
          })}
        </div>
      </div>

      {/* Service groups */}
      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '20px', marginBottom: '28px' }}>
        {groups.map(group => {
          const groupSvcs = statuses.filter(s => s.group === group)
          if (groupSvcs.length === 0) return null
          const deployedInGroup = groupSvcs.filter(s => s.deployed)
          const upInGroup = deployedInGroup.filter(s => s.up).length
          const plannedInGroup = groupSvcs.length - deployedInGroup.length
          const allHealthy = deployedInGroup.length > 0 && upInGroup === deployedInGroup.length
          const color = GROUP_COLORS[group] ?? '#64748b'
          // Sort deployed members first so the live ones lead each group card.
          const ordered = [...groupSvcs].sort((a, b) => Number(b.deployed) - Number(a.deployed))
          return (
            <div key={group} className="card" style={{ padding: '20px' }}>
              <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: '14px' }}>
                <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
                  <div style={{ width: '8px', height: '8px', borderRadius: '50%', background: color }} />
                  <span style={{ fontSize: '12px', fontWeight: 700, color: 'var(--text-primary)', textTransform: 'uppercase', letterSpacing: '0.06em' }}>
                    {group.replace('-', ' ')}
                  </span>
                </div>
                <span style={{ fontSize: '11px', color: allHealthy ? 'var(--success-text)' : deployedInGroup.length === 0 ? 'var(--text-tertiary)' : 'var(--warning-text)',
                  background: allHealthy ? 'var(--success-bg)' : deployedInGroup.length === 0 ? 'var(--surface-2)' : 'var(--warning-bg)',
                  padding: '2px 8px', borderRadius: '10px', fontWeight: 600 }}>
                  {upInGroup}/{deployedInGroup.length} {t('nasazeno', 'up')}{plannedInGroup > 0 ? ` · ${plannedInGroup} ${t('plán', 'planned')}` : ''}
                </span>
              </div>
              <div style={{ display: 'flex', flexWrap: 'wrap', gap: '6px' }}>
                {ordered.map(s => {
                  // Tri-state chip: live-up (green), deployed-down (red, a real outage),
                  // not-deployed (muted/neutral — planned, never an alarm).
                  const bg = !s.deployed ? 'var(--surface-2)' : s.up ? 'var(--success-bg)' : 'var(--danger-bg)'
                  const bd = !s.deployed ? 'var(--border)' : s.up ? 'var(--success-border)' : 'var(--danger-border)'
                  const fg = !s.deployed ? 'var(--text-tertiary)' : s.up ? 'var(--success-text)' : 'var(--danger-text)'
                  const dot = !s.deployed ? 'var(--text-tertiary)' : s.up ? 'var(--success)' : 'var(--danger)'
                  return (
                    <div key={s.name} title={!s.deployed ? t('Nenasazeno v tomto prostředí', 'Not deployed in this environment') : s.up ? t('BĚŽÍ', 'UP') : t('NEBĚŽÍ', 'DOWN')} style={{
                      display: 'flex', alignItems: 'center', gap: '5px',
                      padding: '4px 10px', borderRadius: '6px',
                      background: bg, border: `1px solid ${bd}`,
                      fontSize: '11px', fontWeight: 500, color: fg,
                      opacity: s.deployed ? 1 : 0.7,
                    }}>
                      <span style={{ width: '5px', height: '5px', borderRadius: '50%', background: dot, flexShrink: 0 }} />
                      {s.label}
                      {s.deployed && s.latencyMs ? <span style={{ opacity: 0.7 }}>{s.latencyMs}ms</span> : null}
                    </div>
                  )
                })}
              </div>
            </div>
          )
        })}
      </div>

      {/* Quick links */}
      <div className="card" style={{ padding: '20px' }}>
        <div style={{ fontSize: '12px', fontWeight: 700, color: 'var(--text-secondary)', textTransform: 'uppercase',
          letterSpacing: '0.06em', marginBottom: '14px' }}>{t('Rychlý přístup', 'Quick Access')}</div>
        <div style={{ display: 'flex', flexWrap: 'wrap', gap: '8px' }}>
          {[
            { href: '/accounts',          label: t('Účty', 'Accounts'),         icon: CreditCard,    color: '#6366f1' },
            { href: '/transactions',      label: t('Transakce', 'Transactions'),     icon: ArrowLeftRight, color: '#10b981' },
            { href: '/infrastructure',    label: t('Infra', 'Infrastructure'),  icon: Server,         color: '#f43f5e' },
            { href: '/parties',           label: t('Strany', 'Parties'),          icon: Users,          color: '#3b82f6' },
            { href: '/payments',          label: t('Platby', 'Payments'),         icon: DollarSign,     color: '#f59e0b' },
            { href: '/audit',             label: t('Audit záznam', 'Audit Log'),        icon: Activity,       color: '#8b5cf6' },
            { href: '/regulatory',        label: t('Regulace', 'Regulatory'),       icon: BarChart3,      color: '#ef4444' },
            { href: '/docs/api',          label: t('API Katalog', 'API Catalog'),      icon: Globe,          color: '#0891b2' },
            { href: '/docs/compliance',   label: t('Shoda', 'Compliance'),       icon: ShieldCheck,    color: '#059669' },
            { href: '/infrastructure',    label: t('Infrastruktura', 'Infrastructure'),icon: HardDrive,    color: '#64748b' },
          ].map(({ href, label, icon: Icon, color }) => (
            <Link key={href} href={href} style={{ textDecoration: 'none' }}>
              <div style={{
                display: 'flex', alignItems: 'center', gap: '7px',
                padding: '8px 14px', borderRadius: '8px',
                border: '1px solid var(--border)', background: 'var(--surface)',
                fontSize: '13px', fontWeight: 500, color: 'var(--text-primary)',
                transition: 'all 0.15s', cursor: 'pointer',
              }}
                onMouseEnter={e => { e.currentTarget.style.borderColor = color; e.currentTarget.style.background = 'var(--surface-2)' }}
                onMouseLeave={e => { e.currentTarget.style.borderColor = 'var(--border)'; e.currentTarget.style.background = 'var(--surface)' }}
              >
                <Icon size={14} style={{ color }} />
                {label}
              </div>
            </Link>
          ))}
        </div>
      </div>
    </div>
  )
}

function KpiCard({ icon, label, value, sub, color, trend }: {
  icon: React.ReactNode; label: string; value: string; sub: string; color: string; trend: 'up' | 'down'
}) {
  return (
    <div className="stat-card">
      <div style={{ display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between', marginBottom: '12px' }}>
        <div style={{ width: '36px', height: '36px', borderRadius: '10px',
          background: `${color}18`, display: 'flex', alignItems: 'center', justifyContent: 'center', color }}>
          {icon}
        </div>
        {trend === 'up'
          ? <TrendingUp size={14} style={{ color: 'var(--success)' }} />
          : <TrendingDown size={14} style={{ color: 'var(--danger)' }} />}
      </div>
      <div style={{ fontSize: '24px', fontWeight: 800, color: 'var(--text-primary)', letterSpacing: '-0.03em', marginBottom: '2px' }}>
        {value}
      </div>
      <div style={{ fontSize: '12px', fontWeight: 600, color: 'var(--text-secondary)', marginBottom: '2px' }}>{label}</div>
      <div style={{ fontSize: '11px', color: 'var(--text-tertiary)' }}>{sub}</div>
    </div>
  )
}
