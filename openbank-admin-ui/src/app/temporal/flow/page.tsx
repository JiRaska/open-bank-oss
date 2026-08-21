// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'
import { useState, useEffect } from 'react'
import { Workflow, RefreshCw, Play, Pause, AlertTriangle } from 'lucide-react'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { FlowParticle } from '@/components/topology/FlowParticle'
import { useFlowAnimation } from '@/components/topology/useFlowAnimation'
import { ArrowMarker } from '@/components/topology/TopologyDefs'
import { MONEY_WORKFLOWS } from '@/lib/temporal/workflows'
import { PageHeader } from '@/components/ui/PageHeader'

// ---------------------------------------------------------------------------
// Temporal workflow flow (ADR-0100). The third animated topology view: each
// money-path saga is drawn as a linear step-chain (step → step → … → compensation)
// with a token flowing through, using the shared topology engine. The saga STEPS
// are the documented workflow definitions (curated, from lib/temporal/workflows);
// the throughput METRICS are LIVE but namespace-aggregate (Prometheus via
// /api/temporal/status) — not per-run replay. The subtitle says so. Per-run
// history replay would need a Temporal-history API proxy (follow-up).
// ---------------------------------------------------------------------------

interface TemporalMetrics {
  workflows: { scheduled1h: number; completed1h: number; failed1h: number; timedOut1h: number }
  latency: { activityScheduleToStartMs: number | null; workflowTaskScheduleToStartMs: number | null; serverRequestP99Ms: number | null }
  namespaces: string[]
}
interface StatusData { available: boolean; temporalDeployed: boolean; metrics: TemporalMetrics | null }

const WIDTH = 1180
const PAD = 30
const BOX_H = 40
const ROW_Y = 46
const SVG_H = 88

export default function TemporalFlowPage() {
  const { t, language } = useLanguage()
  const [status, setStatus] = useState<StatusData | null>(null)
  const [flow, setFlow] = useFlowAnimation()
  const [isChecking, setIsChecking] = useState(false)

  const load = async () => {
    setIsChecking(true)
    try {
      const res = await fetch('/api/temporal/status', { cache: 'no-store' })
      if (res.ok) setStatus(await res.json())
      else setStatus({ available: false, temporalDeployed: false, metrics: null })
    } catch {
      setStatus({ available: false, temporalDeployed: false, metrics: null })
    } finally { setIsChecking(false) }
  }
  useEffect(() => { load() }, [])

  const m = status?.metrics ?? null
  const deployed = !!status?.temporalDeployed
  const failing = (m?.workflows.failed1h ?? 0) > 0 || (m?.workflows.timedOut1h ?? 0) > 0
  const num = (v: number | null | undefined) => (v == null ? '—' : String(v))

  const metricCards: { label: string; value: string; tone?: 'good' | 'bad' | 'neutral' }[] = [
    { label: t('Naplánováno (1h)', 'Scheduled (1h)'), value: num(m?.workflows.scheduled1h), tone: 'neutral' },
    { label: t('Dokončeno (1h)', 'Completed (1h)'), value: num(m?.workflows.completed1h), tone: 'good' },
    { label: t('Selhalo (1h)', 'Failed (1h)'), value: num(m?.workflows.failed1h), tone: (m?.workflows.failed1h ?? 0) > 0 ? 'bad' : 'neutral' },
    { label: t('Timeout (1h)', 'Timed out (1h)'), value: num(m?.workflows.timedOut1h), tone: (m?.workflows.timedOut1h ?? 0) > 0 ? 'bad' : 'neutral' },
    { label: t('Latence aktivit', 'Activity latency'), value: m?.latency.activityScheduleToStartMs != null ? `${m.latency.activityScheduleToStartMs} ms` : '—', tone: 'neutral' },
  ]
  const toneColor = (tone?: string) => (tone === 'good' ? 'var(--success)' : tone === 'bad' ? 'var(--danger)' : 'var(--text-primary)')

  return (
    <div style={{ padding: '28px 32px', maxWidth: '1400px' }}>
      <PageHeader
        breadcrumb={<div className="breadcrumb">
            <span>OpenBank</span><span className="breadcrumb-sep">/</span>
            <span>Temporal</span><span className="breadcrumb-sep">/</span>
            <span className="breadcrumb-current">{t('Tok workflow', 'Workflow Flow')}</span>
          </div>}
        title={t('Tok Temporal workflow', 'Temporal Workflow Flow')}
        icon={<Workflow aria-hidden="true" size={18} style={{ color: 'var(--accent)' }} />}
        subtitle={t('Money-path ságy jako animované řetězce kroků (krok → krok → kompenzace). Kroky jsou zdokumentované definice ság; metriky jsou živé, ale agregátní za namespace (ne přehrání jednotlivých běhů).',
               'Money-path sagas as animated step-chains (step → step → compensation). The steps are the documented saga definitions; the metrics are live but namespace-aggregate (not per-run replay).')}
      />
      {/* Controls */}
      <div style={{ display: 'flex', justifyContent: 'flex-end', alignItems: 'center', marginBottom: '14px', gap: '8px' }}>
        <button type="button" onClick={() => setFlow(v => !v)} aria-pressed={flow} aria-label={t(flow ? 'Pozastavit tok workflow' : 'Spustit tok workflow', flow ? 'Pause workflow flow' : 'Start workflow flow')} title={t('Přepnout tok', 'Toggle flow')}
          style={{
            display: 'flex', alignItems: 'center', gap: '5px', padding: '5px 10px', fontSize: '12px', fontWeight: 600,
            borderRadius: '20px', cursor: 'pointer', fontFamily: 'inherit',
            border: `1px solid ${flow ? 'var(--accent)' : 'var(--border)'}`,
            background: flow ? 'var(--accent)' : 'var(--surface)', color: flow ? '#fff' : 'var(--text-secondary)',
          }}>
          {flow ? <Pause size={13} aria-hidden="true" /> : <Play size={13} aria-hidden="true" />}{t('Tok', 'Flow')}
        </button>
        <button type="button" onClick={load} disabled={isChecking} aria-busy={isChecking} aria-label={t('Obnovit stav Temporal', 'Refresh Temporal status')} className="btn btn-secondary" style={{ display: 'flex', alignItems: 'center', gap: '6px', fontSize: '12px' }}>
          <RefreshCw size={14} aria-hidden="true" className={isChecking ? 'animate-spin' : ''} />
          {isChecking ? t('Načítám…', 'Loading...') : t('Obnovit', 'Refresh')}
        </button>
      </div>

      {/* Live metrics strip (aggregate) */}
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(150px, 1fr))', gap: '12px', marginBottom: '8px' }}>
        {metricCards.map((c, i) => (
          <div key={i} className="card" style={{ padding: '14px 16px' }}>
            <div style={{ fontSize: '11px', color: 'var(--text-tertiary)', marginBottom: '4px' }}>{c.label}</div>
            <div style={{ fontSize: '22px', fontWeight: 700, color: toneColor(c.tone), fontFamily: 'JetBrains Mono, monospace' }}>{c.value}</div>
          </div>
        ))}
      </div>
      <div style={{ fontSize: '11px', color: 'var(--text-tertiary)', margin: '4px 2px 18px', display: 'flex', alignItems: 'center', gap: '6px' }}>
        {deployed
          ? <>{t('Živé agregátní metriky z Prometheu · namespace', 'Live aggregate metrics from Prometheus · namespaces')}: {status?.metrics?.namespaces?.join(', ') || '—'}</>
          : <><AlertTriangle size={12} style={{ color: 'var(--warning)' }} /> {t('Temporal není nasazený zde — ságy zobrazeny staticky, živé metriky nedostupné.', 'Temporal is not deployed here — sagas shown statically, live metrics unavailable.')}</>}
      </div>

      {/* One animated saga chain per money-path workflow */}
      <div style={{ display: 'flex', flexDirection: 'column', gap: '14px' }}>
        {MONEY_WORKFLOWS.map((w, wi) => {
          const steps = language === 'cs' ? w.stepsCs : w.stepsEn
          const n = steps.length
          const availW = WIDTH - PAD * 2
          const boxW = Math.min(210, (availW - (n - 1) * 26) / n)
          const gap = n > 1 ? (availW - boxW) / (n - 1) : 0
          const cx = (i: number) => PAD + boxW / 2 + i * gap
          const Icon = w.icon
          return (
            <div key={wi} className="card" style={{ padding: '14px 16px' }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: '10px', marginBottom: '4px' }}>
                <div style={{ width: '26px', height: '26px', borderRadius: '7px', background: `${w.color}1a`, color: w.color, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
                  <Icon size={15} />
                </div>
                <div style={{ fontSize: '13px', fontWeight: 700, color: 'var(--text-primary)' }}>{t(w.serviceCs, w.serviceEn)}</div>
                <code style={{ fontSize: '11px', color: 'var(--text-tertiary)' }}>{w.workflowCs}</code>
                <span style={{ marginLeft: 'auto', fontSize: '10px', color: 'var(--text-tertiary)', fontFamily: 'monospace' }}>{t(w.flagCs, w.flagEn)}</span>
              </div>
              <svg viewBox={`0 0 ${WIDTH} ${SVG_H}`} style={{ width: '100%', height: 'auto', display: 'block' }}>
                <defs>
                  <ArrowMarker id={`tf-arrow-${wi}`} color={w.color} />
                  <ArrowMarker id={`tf-arrow-comp-${wi}`} color="#dc2626" />
                </defs>
                {/* segments between consecutive steps */}
                {steps.slice(0, -1).map((_, i) => {
                  const isCompEdge = i === n - 2 // edge into the last (compensation) step
                  const x1 = cx(i) + boxW / 2, x2 = cx(i + 1) - boxW / 2, y = ROW_Y
                  const color = isCompEdge ? '#dc2626' : w.color
                  const pid = `tf-${wi}-${i}`
                  const showComp = isCompEdge ? failing : true
                  return (
                    <g key={i}>
                      <path id={pid} d={`M ${x1} ${y} L ${x2 - 8} ${y}`} fill="none" stroke={color}
                        strokeWidth={1.6} strokeDasharray={isCompEdge ? '5,4' : undefined}
                        opacity={isCompEdge && !failing ? 0.4 : 0.85}
                        markerEnd={`url(#tf-arrow-${isCompEdge ? `comp-${wi}` : wi})`} />
                      {flow && showComp && <FlowParticle pathId={pid} color={color} dur={1.5 + (i % 3) * 0.2} begin={i * 0.35} r={2.6} />}
                    </g>
                  )
                })}
                {/* step boxes */}
                {steps.map((label, i) => {
                  const isComp = i === n - 1
                  const color = isComp ? '#dc2626' : w.color
                  const x = cx(i) - boxW / 2
                  const short = label.length > 30 ? label.slice(0, 29) + '…' : label
                  return (
                    <g key={i}>
                      <title>{`${i + 1}. ${label}`}</title>
                      <rect x={x} y={ROW_Y - BOX_H / 2} width={boxW} height={BOX_H} rx={8}
                        fill="var(--surface)" stroke={color} strokeWidth={1.4} strokeDasharray={isComp ? '4,3' : undefined} />
                      <circle cx={x + 13} cy={ROW_Y - BOX_H / 2 + 13} r={8} fill={color} />
                      <text x={x + 13} y={ROW_Y - BOX_H / 2 + 16} fontSize="9" fill="#fff" textAnchor="middle" fontWeight="700">{isComp ? 'C' : i + 1}</text>
                      <text x={x + 26} y={ROW_Y + 4} fontSize="9.5" fill="var(--text-primary)" fontWeight="500">{short}</text>
                    </g>
                  )
                })}
              </svg>
            </div>
          )
        })}
      </div>

      {/* Legend */}
      <div style={{ marginTop: '14px', display: 'flex', gap: '18px', flexWrap: 'wrap', fontSize: '11px', color: 'var(--text-tertiary)' }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: '6px' }}>
          <svg width="30" height="10"><line x1="0" y1="5" x2="30" y2="5" stroke="#6366f1" strokeWidth="1.6" /></svg>
          {t('Šťastná cesta ságy', 'Saga happy path')}
        </div>
        <div style={{ display: 'flex', alignItems: 'center', gap: '6px' }}>
          <svg width="30" height="10"><line x1="0" y1="5" x2="30" y2="5" stroke="#dc2626" strokeWidth="1.6" strokeDasharray="5,3" /></svg>
          {t('Kompenzace (jen při selhání)', 'Compensation (only on failure)')}
        </div>
        <div>{t('Krok „C" = kompenzační aktivita', 'Step “C” = compensation activity')}</div>
      </div>
    </div>
  )
}
