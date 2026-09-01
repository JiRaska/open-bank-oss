// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

import { useState, useEffect, useCallback, useRef, type CSSProperties, type ElementType } from 'react'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { CreditCard, ArrowLeftRight, Users, Activity, ShieldCheck, RefreshCw,
  DollarSign, Globe, BarChart3, Server, ClipboardList, ScrollText, Landmark } from 'lucide-react'
import Link from 'next/link'
import { useSession } from 'next-auth/react'
import { PageHeader, StatCard, StatusBadge, type Tone } from '@/components/ui'
import { hasPermission, type Permission } from '@/lib/auth/roles'
import { personaForRoles, personaLabel, workspaceFor } from '@/lib/auth/persona'
import { fleetHealthState, summarizeFleetHealth } from '@/lib/dashboard/fleetHealth'
import styles from './Dashboard.module.css'
import { ExplorerGuide } from '@/components/brand/ExplorerGuide'

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
  core: 'var(--accent)', payments: 'var(--success)', compliance: 'var(--warning)',
  identity: 'var(--info)', 'open-banking': 'var(--accent)', platform: 'var(--text-tertiary)'
}

const WORKSPACE_ICONS: Record<string, ElementType> = {
  '/accounts': CreditCard,
  '/transactions': ArrowLeftRight,
  '/onboarding': ClipboardList,
  '/parties': Users,
  '/payments': DollarSign,
  '/standing-orders': ArrowLeftRight,
  '/clearing': Landmark,
  '/fx': Globe,
  '/kyc': ShieldCheck,
  '/aml': ShieldCheck,
  '/sanctions': ShieldCheck,
  '/audit': ScrollText,
  '/system/health': Activity,
  '/devops': Activity,
  '/observability': Activity,
  '/services': Server,
}

export default function DashboardPage() {
  const { t, language } = useLanguage()
  const dateLocale = language === 'cs' ? 'cs-CZ' : 'en-GB'
  const { data: session } = useSession()
  const [statuses, setStatuses] = useState<SvcStatus[]>([])
  const [loading, setLoading] = useState(true)
  const [lastRefresh, setLastRefresh] = useState<Date | null>(null)
  const loadingRef = useRef(false)

  const load = useCallback(async () => {
    if (loadingRef.current) return
    loadingRef.current = true
    setLoading(true)
    // Governance and live health are independent reads. Start them together so a
    // slow health probe cannot delay the canonical roster (and vice versa).
    const governanceRequest = fetch('/api/services/governance', { cache: 'no-store' }).catch(() => null)
    const healthRequest = fetch('/api/services/health', { signal: AbortSignal.timeout(10000), cache: 'no-store' }).catch(() => null)
    const [govRes, res] = await Promise.all([governanceRequest, healthRequest])

    // Canonical fleet from the code-derived governance manifest (ADR-0071).
    let fleet: { name: string; group: string }[] = []
    if (govRes?.ok) {
      try {
        const g = await govRes.json() as { items?: { serviceName: string; dataDomain: string }[] }
        fleet = (g.items ?? []).map(e => ({ name: e.serviceName, group: e.dataDomain }))
      } catch { /* fleet stays empty → roster degrades calmly, no blank crash */ }
    }

    try {
      // Live discovery (ADR-0051) keyed by bare deployment name. Empty on failure —
      // the fleet still renders, every member simply shows as not-deployed rather
      // than the page going blank.
      const discovered = new Map<string, HealthEntry>()
      if (res?.ok) {
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
  const health = summarizeFleetHealth(statuses)
  const healthState = fleetHealthState(health)
  const healthTone: Tone = healthState === 'healthy' ? 'success' : healthState === 'degraded' ? 'warning' : 'neutral'
  const roles = session?.user?.roles ?? []
  const persona = personaForRoles(roles)
  const workspace = workspaceFor(persona).filter(link => hasPermission(roles, link.permission))
  const personaLanguage = language === 'cs' ? 'cs' : 'en'

  const groups = ['core', 'payments', 'compliance', 'identity', 'open-banking', 'platform']

  return (
    <div className={styles.dashboard}>
      <PageHeader
        title={t('Můj pracovní prostor', 'My workspace')}
        subtitle={`${personaLabel(persona, personaLanguage)} · ${t('Prioritní pracovní fronty a aktuální stav platformy.', 'Priority work queues and the current platform state.')}`}
        icon={<Activity className={styles.headerIcon} size={20} aria-hidden="true" />}
        actions={
          <div className={styles.headerActions}>
          {lastRefresh && (
            <span className={styles.lastRefresh}>
              {t('Aktualizováno', 'Updated')} {lastRefresh.toLocaleTimeString(dateLocale)}
            </span>
          )}
          <button type="button" onClick={load} disabled={loading} aria-busy={loading} aria-label={t('Obnovit přehled platformy', 'Refresh platform overview')} className="btn btn-secondary btn-sm">
            <RefreshCw size={13} aria-hidden="true" style={{ animation: loading ? 'spin 0.8s linear infinite' : 'none' }} />
            {t('Obnovit', 'Refresh')}
          </button>
          </div>
        }
      />

      <ExplorerGuide title={t('Vítejte ve svém operačním kokpitu', 'Welcome to your operations cockpit')}>
        {t(
          'Začněte pracovními frontami podle svých oprávnění. Červená znamená skutečný problém; šedá služba jen čeká na nasazení. Explorer nemá rád falešné poplachy — a vy je nemusíte řešit.',
          'Start with the work queues matched to your permissions. Red means a real problem; a grey service is merely awaiting deployment. Explorer dislikes false alarms, so you do not have to chase them.',
        )}
      </ExplorerGuide>

      <section className={`card ${styles.workspace}`} aria-labelledby="workspace-heading">
        <div className={styles.workspaceHeading}>
          <div>
            <p className={styles.workspaceEyebrow}>{personaLabel(persona, personaLanguage)}</p>
            <h2 id="workspace-heading" className={styles.workspaceTitle}>{t('Pracovní fronty', 'Work queues')}</h2>
          </div>
          <span className={styles.workspaceContext}>{t('Podle vašich oprávnění', 'Based on your permissions')}</span>
        </div>
        <div className={styles.workspaceLinks}>
          {workspace.map(link => {
            const Icon = WORKSPACE_ICONS[link.href] ?? Activity
            return (
              <Link key={link.href} href={link.href} className={styles.workspaceLink}>
                <span className={styles.workspaceIcon}><Icon size={18} aria-hidden="true" /></span>
                <span>{t(link.nameCs, link.nameEn)}</span>
                <span className={styles.workspaceArrow} aria-hidden="true">→</span>
              </Link>
            )
          })}
        </div>
      </section>

      {/* These are intentionally current health facts, not estimated operational or compliance metrics. */}
      <section className={styles.metrics} aria-label={t('Klíčové metriky platformy', 'Platform key metrics')}>
        <StatCard className={styles.metric} icon={<Server size={15} />} label={t('Zdravé služby', 'Healthy services')} value={`${health.healthy}/${health.deployed}`} tone={healthTone}
          hint={t('aktuální health-check nasazených služeb', 'current health check of deployed services')} />
        <StatCard className={styles.metric} icon={<Activity size={15} />} label={t('Průměrná latence kontroly', 'Average check latency')}
          value={health.averageHealthCheckLatencyMs === null ? '—' : `${health.averageHealthCheckLatencyMs} ms`}
          hint={t('měření endpointu health-checku, ne p99', 'health-check endpoint measurement, not p99')} />
        <StatCard className={styles.metric} icon={<Server size={15} />} label={t('Nasazeno', 'Deployed')} value={`${health.deployed}/${health.total}`}
          hint={t('služby objevené v tomto prostředí', 'services discovered in this environment')} />
        <StatCard className={styles.metric} icon={<ShieldCheck size={15} />} label={t('Nenasaženo', 'Not deployed')} value={health.notDeployed} tone="neutral"
          hint={t('plánované služby; není to incident', 'planned services; not an incident')} />
      </section>

      {/* A current per-group health distribution, not an uptime/SLO measurement. */}
      <section className={`card ${styles.healthOverview}`} aria-labelledby="health-by-group-heading">
        <h2 id="health-by-group-heading" className={styles.sectionLabel}>{t('Aktuální zdraví dle skupiny', 'Current health by group')}</h2>
        <div className={styles.healthGroups}>
          {groups.map(group => {
            const groupSvcs = statuses.filter(s => s.group === group)
            if (groupSvcs.length === 0) return null
            const deployedInGroup = groupSvcs.filter(s => s.deployed)
            const upInGroup = deployedInGroup.filter(s => s.up).length
            // The bar is the current healthy share of deployed members; a group with nothing
            // deployed yet shows a neutral "not deployed" rather than a red 0%.
            const hasDeployed = deployedInGroup.length > 0
            const pct = hasDeployed ? Math.round((upInGroup / deployedInGroup.length) * 100) : 0
            const color = GROUP_COLORS[group] ?? 'var(--text-tertiary)'
            return (
              <div key={`health-${group}`} className={styles.healthGroup}>
                <div className={styles.healthGroupHeader}>
                  <span className={styles.groupName}>{group.replace('-', ' ')}</span>
                  <span className={styles.groupSummary}>
                    {hasDeployed ? `${upInGroup}/${deployedInGroup.length} ${t('zdravé', 'healthy')}` : t('nenasazeno', 'not deployed')}
                  </span>
                </div>
                <div className={styles.healthBar}>
                  <div className={styles.healthBarFill} style={{ width: hasDeployed ? `${pct}%` : '0%', background: pct === 100 ? color : 'var(--danger)' }} />
                </div>
              </div>
            )
          })}
        </div>
      </section>

      {/* Service groups */}
      <section className={styles.serviceGroups} aria-label={t('Služby podle skupiny', 'Services by group')}>
        {groups.map(group => {
          const groupSvcs = statuses.filter(s => s.group === group)
          if (groupSvcs.length === 0) return null
          const deployedInGroup = groupSvcs.filter(s => s.deployed)
          const upInGroup = deployedInGroup.filter(s => s.up).length
          const plannedInGroup = groupSvcs.length - deployedInGroup.length
          const allHealthy = deployedInGroup.length > 0 && upInGroup === deployedInGroup.length
          const color = GROUP_COLORS[group] ?? 'var(--text-tertiary)'
          const deploymentSummary = `${upInGroup}/${deployedInGroup.length} ${t('nasazeno', 'up')}${plannedInGroup > 0 ? ` · ${plannedInGroup} ${t('plán', 'planned')}` : ''}`
          // Sort deployed members first so the live ones lead each group card.
          const ordered = [...groupSvcs].sort((a, b) => Number(b.deployed) - Number(a.deployed))
          return (
            <article key={group} className={`card ${styles.serviceGroup}`}>
              <div className={styles.serviceGroupHeader}>
                <div className={styles.serviceGroupTitle}>
                  <span className={styles.groupDot} style={{ background: color }} aria-hidden="true" />
                  <h2 className={styles.sectionLabel}>
                    {group.replace('-', ' ')}
                  </h2>
                </div>
                <StatusBadge
                  status={allHealthy ? 'HEALTHY' : deployedInGroup.length === 0 ? 'NOT_DEPLOYED' : 'DEGRADED'}
                  label={deploymentSummary}
                  tone={allHealthy ? 'success' : deployedInGroup.length === 0 ? 'neutral' : 'warning'}
                  className={styles.groupStatus}
                />
              </div>
              <div className={styles.serviceChips}>
                {ordered.map(s => {
                  // Tri-state chip: live-up (green), deployed-down (red, a real outage),
                  // not-deployed (muted/neutral — planned, never an alarm).
                  const state = !s.deployed ? 'planned' : s.up ? 'healthy' : 'unhealthy'
                  return (
                    <div key={s.name} title={!s.deployed ? t('Nenasazeno v tomto prostředí', 'Not deployed in this environment') : s.up ? t('BĚŽÍ', 'UP') : t('NEBĚŽÍ', 'DOWN')} className={`${styles.serviceChip} ${styles[`serviceChip${state.charAt(0).toUpperCase()}${state.slice(1)}`]}`}>
                      <span className={styles.serviceDot} aria-hidden="true" />
                      {s.label}
                      {s.deployed && s.latencyMs ? <span className={styles.serviceLatency}>{s.latencyMs}ms</span> : null}
                    </div>
                  )
                })}
              </div>
            </article>
          )
        })}
      </section>

      {/* Only show destinations the operator can already access; dashboard shortcuts must not create 403 traps. */}
      <section className={`card ${styles.quickAccess}`} aria-labelledby="quick-access-heading">
        <h2 id="quick-access-heading" className={styles.sectionLabel}>{t('Další nástroje', 'More tools')}</h2>
        <div className={styles.quickLinks}>
          {[
            { href: '/accounts', label: t('Účty', 'Accounts'), icon: CreditCard, color: 'var(--accent)', permission: 'accounts:view' },
            { href: '/transactions', label: t('Transakce', 'Transactions'), icon: ArrowLeftRight, color: 'var(--success)', permission: 'transactions:view' },
            { href: '/infrastructure', label: t('Infrastruktura', 'Infrastructure'), icon: Server, color: 'var(--danger)', permission: 'system:view' },
            { href: '/parties', label: t('Strany', 'Parties'), icon: Users, color: 'var(--info)', permission: 'parties:view' },
            { href: '/payments', label: t('Platby', 'Payments'), icon: DollarSign, color: 'var(--warning)', permission: 'payments:view' },
            { href: '/audit', label: t('Auditní záznam', 'Audit log'), icon: Activity, color: 'var(--accent)', permission: 'audit:view' },
            { href: '/regulatory', label: t('Regulace', 'Regulatory'), icon: BarChart3, color: 'var(--danger)', permission: 'regulatory:view' },
            { href: '/docs/api', label: t('API katalog', 'API catalog'), icon: Globe, color: 'var(--info)', permission: 'docs:view' },
            { href: '/docs/compliance', label: t('Shoda', 'Compliance'), icon: ShieldCheck, color: 'var(--success)', permission: 'compliance:view' },
          ].filter(link => hasPermission(roles, link.permission as Permission)).map(({ href, label, icon: Icon, color }) => (
            <Link key={href} href={href} className={styles.quickLink} style={{ '--quick-link-accent': color } as CSSProperties}>
              <span className={styles.quickLinkContent}>
                <Icon size={14} style={{ color }} />
                {label}
              </span>
            </Link>
          ))}
        </div>
      </section>
    </div>
  )
}
