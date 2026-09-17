// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

'use client'

import { useCallback, useEffect, useRef, useState, type ReactNode } from 'react'
import { Activity, ExternalLink, GitBranch, RefreshCw, Scale, Workflow } from 'lucide-react'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { parseTempoSearch, type TraceSummary } from '@/lib/observability/tempo-evidence'
import styles from './OperationalEvidence.module.css'

type LoadState<T> = { loading: boolean; data: T | null; failed: boolean }
type PyrraSummary = {
  available: boolean
  configured: number
  monitored: number
  objectives: { name: string; budgetRemaining: number | null; availability: number | null }[]
}
type TemporalStatus = {
  available: boolean
  temporalDeployed: boolean | null
  metrics: null | { workflows: { scheduled1h: number; completed1h: number; failed1h: number; timedOut1h: number } }
}

const empty = <T,>(): LoadState<T> => ({ loading: true, data: null, failed: false })

async function jsonFrom<T>(request: () => Promise<Response>): Promise<T | null> {
  try {
    const response = await request()
    return response.ok ? await response.json() as T : null
  } catch {
    return null
  }
}

function statusClass(status: 'good' | 'warn' | 'bad' | 'neutral') {
  return `${styles.pill} ${styles[status]}`
}

function sourceLabel(status: 'good' | 'warn' | 'bad' | 'neutral', t: (cs: string, en: string) => string) {
  if (status === 'good') return t('V pořádku', 'Healthy')
  if (status === 'warn') return t('Pozor', 'Watch')
  if (status === 'bad') return t('Zásah', 'Action')
  return t('Bez signálu', 'No signal')
}

export function OperationalEvidence() {
  const { t } = useLanguage()
  const [pyrra, setPyrra] = useState<LoadState<PyrraSummary>>(empty)
  const [temporal, setTemporal] = useState<LoadState<TemporalStatus>>(empty)
  const [tempo, setTempo] = useState<LoadState<TraceSummary[]>>(empty)
  const [refreshing, setRefreshing] = useState(false)
  const loadGeneration = useRef(0)

  const load = useCallback(async () => {
    const generation = ++loadGeneration.current
    const isCurrent = () => generation === loadGeneration.current
    setRefreshing(true)
    try {
      const now = Math.floor(Date.now() / 1000)
      await Promise.allSettled([
        (async () => {
          const data = await jsonFrom<PyrraSummary>(() => fetch('/api/pyrra/summary', { signal: AbortSignal.timeout(9000) }))
          if (isCurrent()) setPyrra({ loading: false, data, failed: data?.available !== true })
        })(),
        (async () => {
          const data = await jsonFrom<TemporalStatus>(() => fetch('/api/temporal/status', { signal: AbortSignal.timeout(9000) }))
          if (isCurrent()) setTemporal({ loading: false, data, failed: data?.available !== true })
        })(),
        (async () => {
          const payload = await jsonFrom<unknown>(() => fetch(`/api/tempo/api/search?limit=20&start=${now - 3600}&end=${now}`, { signal: AbortSignal.timeout(9000) }))
          const data = payload === null ? null : parseTempoSearch(payload)
          if (isCurrent()) setTempo({ loading: false, data, failed: data === null })
        })(),
      ])
    } finally {
      if (isCurrent()) setRefreshing(false)
    }
  }, [])

  useEffect(() => {
    const initialLoad = window.setTimeout(load, 0)
    return () => {
      window.clearTimeout(initialLoad)
      loadGeneration.current += 1
    }
  }, [load])

  const budgets = pyrra.data?.objectives.flatMap(objective => objective.budgetRemaining === null ? [] : [objective]) ?? []
  const worstBudget = budgets.toSorted((a, b) => (a.budgetRemaining ?? 0) - (b.budgetRemaining ?? 0))[0]
  const budgetPct = worstBudget?.budgetRemaining == null ? null : Math.round(worstBudget.budgetRemaining * 100)
  const pyrraStatus = budgetPct === null ? 'neutral' : budgetPct < 0 ? 'bad' : budgetPct < 25 ? 'warn' : 'good'

  const workflows = temporal.data?.metrics?.workflows
  const workflowProblems = workflows ? workflows.failed1h + workflows.timedOut1h : null
  const temporalStatus = workflowProblems === null ? 'neutral' : workflowProblems > 0 ? 'bad' : workflows!.completed1h === 0 ? 'warn' : 'good'

  const slowest = tempo.data?.filter(trace => typeof trace.durationMs === 'number').toSorted((a, b) => (b.durationMs ?? 0) - (a.durationMs ?? 0))[0]
  const tempoStatus = tempo.failed || !slowest ? 'neutral' : (slowest.durationMs ?? 0) >= 1000 ? 'warn' : 'good'

  return (
    <section className={styles.shell} aria-labelledby="operational-evidence-title">
      <div className={styles.header}>
        <div className={styles.heading}>
          <div className={styles.icon}><Activity size={18} aria-hidden="true" /></div>
          <div>
            <div className={styles.eyebrow}>{t('Rozhodovací přehled', 'Decision brief')}</div>
            <div className={styles.title} id="operational-evidence-title">{t('Spolehlivost zákaznické cesty', 'Customer journey reliability')}</div>
            <div className={styles.subtitle}>{t('Rozpočet spolehlivosti, průchod workflow a nejpomalejší reálný požadavek.', 'Reliability budget, workflow completion and the slowest real request.')}</div>
          </div>
        </div>
        <button type="button" className="btn btn-secondary" onClick={load} disabled={refreshing} aria-busy={refreshing} aria-label={t('Obnovit provozní důkazy', 'Refresh operational evidence')}>
          <RefreshCw size={13} aria-hidden="true" className={refreshing ? 'animate-spin' : undefined} /> {t('Obnovit', 'Refresh')}
        </button>
      </div>
      <div className={styles.grid}>
        <EvidenceCard
          icon={<Scale size={15} aria-hidden="true" />} source="Pyrra" hint={t('30denní SLO', '30-day SLO')}
          loading={pyrra.loading} status={pyrraStatus} statusLabel={sourceLabel(pyrraStatus, t)}
          value={budgetPct === null ? '—' : `${budgetPct}%`}
          label={pyrra.failed
            ? t('Důkazy z Pyrra nejsou dostupné; zbývající rozpočet nelze ověřit.', 'Pyrra evidence is unavailable; the remaining budget cannot be verified.')
            : budgetPct === null
              ? t('SLO jsou nakonfigurovaná, zatím ale nemají dost provozních vzorků.', 'SLOs are configured but do not have enough traffic samples yet.')
            : t(`Nejnižší zbývající error budget: ${worstBudget?.name ?? ''}.`, `Lowest remaining error budget: ${worstBudget?.name ?? ''}.`)}
          href="/tools/pyrra" link={t('Otevřít SLO', 'Open SLOs')}
        />
        <EvidenceCard
          icon={<Workflow size={15} aria-hidden="true" />} source="Temporal" hint={t('Poslední hodina', 'Last hour')}
          loading={temporal.loading} status={temporalStatus} statusLabel={sourceLabel(temporalStatus, t)}
          value={workflowProblems === null ? '—' : workflowProblems === 0 ? `${workflows?.completed1h ?? 0} OK` : `${workflowProblems} ${t('selhání', 'failed')}`}
          label={workflowProblems === null
            ? t('Temporal neposkytuje ověřitelný stav workflow.', 'Temporal is not providing a verifiable workflow status.')
            : t(`${workflows?.scheduled1h ?? 0} spuštěno · ${workflows?.completed1h ?? 0} dokončeno.`, `${workflows?.scheduled1h ?? 0} started · ${workflows?.completed1h ?? 0} completed.`)}
          href="/temporal" link={t('Otevřít workflow', 'Open workflows')}
        />
        <EvidenceCard
          icon={<GitBranch size={15} aria-hidden="true" />} source="Tempo" hint={t('Poslední hodina', 'Last hour')}
          loading={tempo.loading} status={tempoStatus} statusLabel={sourceLabel(tempoStatus, t)}
          value={slowest?.durationMs === undefined ? '—' : slowest.durationMs >= 1000 ? `${(slowest.durationMs / 1000).toFixed(2)} s` : `${Math.round(slowest.durationMs)} ms`}
          label={tempo.failed
            ? t('Důkazy z Tempo nejsou dostupné; dobu požadavků nelze ověřit.', 'Tempo evidence is unavailable; request duration cannot be verified.')
            : slowest
              ? t(`Nejpomalejší trasa: ${slowest.rootTraceName ?? slowest.rootServiceName ?? slowest.traceID}.`, `Slowest trace: ${slowest.rootTraceName ?? slowest.rootServiceName ?? slowest.traceID}.`)
            : t('Tempo je dostupné, ale v poslední hodině není trasa s měřitelnou délkou.', 'Tempo is available, but no trace with measurable duration was found in the last hour.')}
          href="/observability/traces" link={t('Prozkoumat trasu', 'Explore trace')}
        />
      </div>
    </section>
  )
}

function EvidenceCard({ icon, source, hint, loading, status, statusLabel, value, label, href, link }: {
  icon: ReactNode; source: string; hint: string; loading: boolean
  status: 'good' | 'warn' | 'bad' | 'neutral'; statusLabel: string
  value: string; label: string; href: string; link: string
}) {
  return (
    <article className={styles.card}>
      <div className={styles.cardTop}>
        <div><div className={styles.source}>{icon}{source}</div><div className={styles.sourceHint}>{hint}</div></div>
        <span className={statusClass(status)}>{statusLabel}</span>
      </div>
      {loading ? <div className={styles.skeleton} /> : <div className={styles.value}>{value}</div>}
      <div className={styles.label}>{loading ? ' ' : label}</div>
      <a className={styles.link} href={href}>{link}<ExternalLink size={11} aria-hidden="true" /></a>
    </article>
  )
}
