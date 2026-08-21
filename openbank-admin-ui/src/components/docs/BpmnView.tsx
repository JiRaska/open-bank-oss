// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

// ---------------------------------------------------------------------------
// BpmnView — renders the BPMN business-process diagrams (BpmnProcessSchema).
// Pure presentation: ALL content comes from the `processes` prop (loaded +
// validated server-side from src/content/bpmn/<slug>.yaml). The process tabs,
// the SVG flow diagram, the live service-coverage table and the legend live
// here; the data does not. Adding a process = adding a YAML manifest.
//
// Async is first-class: a flow with `kind: 'async'` is drawn as a dashed event
// edge carrying its Kafka `topic`; a step's `emits`/`consumes` topics surface in
// the coverage table; an `event` node is a message catch/throw point. This is
// what the old hardcoded page could not express (it had a single dashed edge and
// no event nodes), so the heavily event-driven reality (outbox + Kafka,
// ADR-0003/0050) was invisible.
// ---------------------------------------------------------------------------

import { useState } from 'react'
import { GitBranch, RefreshCw, CheckCircle2, XCircle, AlertCircle } from 'lucide-react'
import type { BpmnProcess, BpmnStep } from '@/lib/docs/bpmn/schema'
import { DocsPageHeader } from '@/components/docs/DocsPageHeader'
import { useLanguage } from '@/lib/i18n/LanguageContext'

const LANE_BG: Record<string, string> = {
  compliance: '#fef2f2', core: '#eff6ff', psd2: '#fffbeb', payment: '#faf5ff',
  kyc: '#fef2f2', identity: '#f0fdf4', platform: '#f8fafc', cards: '#f5f3ff', aml: '#fef2f2',
}
const LANE_BORDER: Record<string, string> = {
  compliance: '#fca5a5', core: '#93c5fd', psd2: '#fde68a', payment: '#c4b5fd',
  kyc: '#fca5a5', identity: '#86efac', platform: '#e2e8f0', cards: '#c4b5fd', aml: '#fca5a5',
}

const ASYNC_COLOR = '#8b5cf6'

function BpmnDiagram({ process }: { process: BpmnProcess }) {
  const stepMap = Object.fromEntries(process.steps.map((s) => [s.id, s]))

  const renderNode = (step: BpmnStep) => {
    const { x, y, type, label } = step
    if (type === 'start') return (
      <g key={step.id}>
        <circle cx={x} cy={y} r={18} fill="#22c55e" stroke="#16a34a" strokeWidth="2" />
        <text x={x} y={y + 32} textAnchor="middle" fontSize="9" fill="var(--text-secondary)">{label}</text>
      </g>
    )
    if (type === 'end') return (
      <g key={step.id}>
        <circle cx={x} cy={y} r={18} fill="#1e40af" stroke="#1e3a8a" strokeWidth="3" />
        <text x={x} y={y + 32} textAnchor="middle" fontSize="9" fill="var(--text-secondary)">{label}</text>
      </g>
    )
    if (type === 'end-err') return (
      <g key={step.id}>
        <circle cx={x} cy={y} r={18} fill="#dc2626" stroke="#991b1b" strokeWidth="3" />
        <text x={x} y={y + 32} textAnchor="middle" fontSize="9" fill="var(--text-secondary)">{label}</text>
      </g>
    )
    if (type === 'gateway') return (
      <g key={step.id}>
        <polygon points={`${x},${y - 20} ${x + 20},${y} ${x},${y + 20} ${x - 20},${y}`}
          fill="#fef3c7" stroke="#d97706" strokeWidth="2" />
        <text x={x} y={y + 34} textAnchor="middle" fontSize="9" fill="var(--text-secondary)">{label}</text>
      </g>
    )
    // event — intermediate message event (catch/throw): double ring + envelope
    if (type === 'event') return (
      <g key={step.id}>
        <circle cx={x} cy={y} r={18} fill="#faf5ff" stroke={ASYNC_COLOR} strokeWidth="2" strokeDasharray="4,2" />
        <circle cx={x} cy={y} r={13} fill="none" stroke={ASYNC_COLOR} strokeWidth="1" />
        <path d={`M${x - 7},${y - 4} h14 v8 h-14 z M${x - 7},${y - 4} l7,5 l7,-5`}
          fill="none" stroke={ASYNC_COLOR} strokeWidth="1" />
        <text x={x} y={y + 32} textAnchor="middle" fontSize="9" fill="var(--text-secondary)">{label}</text>
      </g>
    )
    // task
    const bg = step.lane ? LANE_BG[step.lane] || '#f8fafc' : '#f8fafc'
    const border = step.lane ? LANE_BORDER[step.lane] || '#e2e8f0' : '#e2e8f0'
    const words = label.split(' ')
    const eventMarker = step.emits?.length || step.consumes?.length
    return (
      <g key={step.id}>
        <rect x={x - 50} y={y - 22} width={100} height={44} rx="6"
          fill={bg} stroke={border} strokeWidth="1.5" />
        {words.map((w, i) => (
          <text key={i} x={x} y={y - 4 + i * 13} textAnchor="middle" fontSize="9" fontWeight="600" fill="#374151">{w}</text>
        ))}
        {eventMarker ? (
          // small envelope badge: this activity publishes/consumes domain events
          <g>
            <rect x={x + 38} y={y - 30} width={16} height={12} rx="2" fill="#faf5ff" stroke={ASYNC_COLOR} strokeWidth="1" />
            <path d={`M${x + 39},${y - 29} l7,5 l7,-5`} fill="none" stroke={ASYNC_COLOR} strokeWidth="1" />
          </g>
        ) : null}
      </g>
    )
  }

  return (
    <svg viewBox="0 0 1120 300" style={{ width: '100%', height: 'auto', display: 'block' }}>
      <defs>
        <marker id="bpmn-arrow" markerWidth="8" markerHeight="8" refX="6" refY="3" orient="auto">
          <path d="M0,0 L0,6 L8,3 z" fill="#6b7280" />
        </marker>
        <marker id="bpmn-arrow-async" markerWidth="8" markerHeight="8" refX="6" refY="3" orient="auto">
          <path d="M0,0 L0,6 L8,3 z" fill={ASYNC_COLOR} />
        </marker>
      </defs>

      {/* Flows */}
      {process.flows.map((f, i) => {
        const from = stepMap[f.from]
        const to = stepMap[f.to]
        if (!from || !to) return null
        const mx = (from.x + to.x) / 2
        const my = (from.y + to.y) / 2
        const isAsync = f.kind === 'async'
        // async edges show their Kafka topic; sync edges show the branch label
        const caption = isAsync ? f.topic || f.label : f.label
        return (
          <g key={i}>
            <line x1={from.x} y1={from.y} x2={to.x} y2={to.y}
              stroke={isAsync ? ASYNC_COLOR : '#9ca3af'}
              strokeWidth="1.5"
              strokeDasharray={isAsync ? '5,3' : undefined}
              markerEnd={isAsync ? 'url(#bpmn-arrow-async)' : 'url(#bpmn-arrow)'} />
            {caption && (
              <text x={mx} y={my - 5} textAnchor="middle"
                fontSize={isAsync ? 7 : 8}
                fontFamily={isAsync ? 'JetBrains Mono, monospace' : undefined}
                fill={isAsync ? ASYNC_COLOR : '#6b7280'}>{caption}</text>
            )}
          </g>
        )
      })}

      {/* Nodes */}
      {process.steps.map(renderNode)}
    </svg>
  )
}

function ProcessLayerMap({ process }: { process: BpmnProcess }) {
  const { t, language } = useLanguage()
  const dateLocale = language === 'cs' ? 'cs-CZ' : 'en-GB'
  const [statuses, setStatuses] = useState<Record<string, 'up' | 'down' | 'loading' | 'unknown'>>({})
  const [lastRefresh, setLastRefresh] = useState<Date | null>(null)

  const stepsWithDetails = process.steps.filter(
    (s) => s.apis?.length || s.relatedServices?.length || s.emits?.length || s.consumes?.length,
  )
  const isChecking = Object.values(statuses).some((status) => status === 'loading')

  const checkServices = async () => {
    const servicesToCheck = Array.from(new Set(stepsWithDetails.flatMap((s) => s.relatedServices || [])))

    const newStatuses = { ...statuses }
    servicesToCheck.forEach((s) => (newStatuses[s] = 'loading'))
    setStatuses(newStatuses)

    try {
      const res = await fetch('/api/services/health')
      if (res.ok) {
        const data = await res.json()
        const healthMap: Record<string, 'up' | 'down'> = {}
        if (data && data.services) {
          data.services.forEach((s: { name: string; status: string; port: number }) => {
            const sname = s.name.replace(/^openbank-/, '')
            healthMap[sname] = s.status === 'UP' ? 'up' : 'down'
          })
        }
        setStatuses((prev) => {
          const updated = { ...prev }
          servicesToCheck.forEach((svc) => {
            updated[svc] = healthMap[svc] !== undefined ? healthMap[svc] : 'down'
          })
          return updated
        })
      } else {
        setStatuses((prev) => {
          const updated = { ...prev }
          servicesToCheck.forEach((svc) => (updated[svc] = 'down'))
          return updated
        })
      }
    } catch {
      setStatuses((prev) => {
        const updated = { ...prev }
        servicesToCheck.forEach((svc) => (updated[svc] = 'down'))
        return updated
      })
    }

    setLastRefresh(new Date())
  }

  return (
    <div style={{ marginTop: '24px' }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '16px' }}>
        <h3 style={{ fontSize: '14px', fontWeight: 600, color: 'var(--text-primary)', margin: 0 }}>
          {t('Pokrytí API a služeb (vrstvený pohled)', 'API & Service Coverage (layered view)')}
        </h3>
        <button
          type="button"
          onClick={checkServices}
          disabled={isChecking}
          aria-busy={isChecking}
          style={{
            display: 'flex', alignItems: 'center', gap: '6px',
            padding: '6px 12px', fontSize: '12px', fontWeight: 500,
            background: 'var(--surface-2)', border: '1px solid var(--border)',
            borderRadius: '6px', color: 'var(--text-secondary)', cursor: 'pointer',
          }}>
          <RefreshCw size={14} aria-hidden="true" />
          {lastRefresh ? `${t('Aktualizováno', 'Refreshed')} ${lastRefresh.toLocaleTimeString(dateLocale)}` : t('Ověřit stav', 'Check status')}
        </button>
      </div>

      <div style={{ display: 'grid', gap: '12px' }}>
        {stepsWithDetails.map((step) => {
          const events = [
            ...(step.emits || []).map((t) => ({ dir: 'emit' as const, topic: t })),
            ...(step.consumes || []).map((t) => ({ dir: 'consume' as const, topic: t })),
          ]
          return (
            <div key={step.id} style={{
              display: 'grid', gridTemplateColumns: '1.5fr 2fr 1.5fr', gap: '16px',
              padding: '12px', background: 'var(--surface)', border: '1px solid var(--border)',
              borderRadius: '8px', alignItems: 'center',
            }}>
              <div>
                <div style={{ fontSize: '11px', color: 'var(--text-tertiary)', textTransform: 'uppercase', marginBottom: '4px' }}>{t('Krok procesu', 'Process step')}</div>
                <div style={{ fontSize: '13px', fontWeight: 600, color: 'var(--text-primary)' }}>{step.label}</div>
                {step.lane && (
                  <div style={{ fontSize: '11px', color: 'var(--text-secondary)', marginTop: '2px' }}>{t('Doména', 'Lane')}: {step.lane}</div>
                )}
              </div>

              <div>
                <div style={{ fontSize: '11px', color: 'var(--text-tertiary)', textTransform: 'uppercase', marginBottom: '4px' }}>{t('API a události', 'APIs & events')}</div>
                {step.apis?.length ? step.apis.map((api) => (
                  <div key={api} style={{
                    fontSize: '12px', fontFamily: 'JetBrains Mono, monospace',
                    color: 'var(--text-secondary)', background: 'var(--surface-2)',
                    padding: '4px 8px', borderRadius: '4px', display: 'inline-block', marginBottom: '4px',
                  }}>
                    {api}
                  </div>
                )) : null}
                {events.map((e) => (
                  <div key={e.dir + e.topic} style={{
                    fontSize: '11px', fontFamily: 'JetBrains Mono, monospace',
                    color: ASYNC_COLOR, background: '#faf5ff', border: `1px solid ${ASYNC_COLOR}33`,
                    padding: '4px 8px', borderRadius: '4px', display: 'flex', alignItems: 'center', gap: '6px', marginBottom: '4px',
                  }}>
                    <span style={{ fontWeight: 700 }}>{e.dir === 'emit' ? '▲ emit' : '▼ consume'}</span>
                    {e.topic}
                  </div>
                ))}
                {!step.apis?.length && !events.length && (
                  <div style={{ fontSize: '12px', color: 'var(--text-tertiary)' }}>{t('API není namapováno', 'No API mapped')}</div>
                )}
              </div>

              <div>
                <div style={{ fontSize: '11px', color: 'var(--text-tertiary)', textTransform: 'uppercase', marginBottom: '4px' }}>{t('Služby a stav', 'Services & status')}</div>
                {step.relatedServices?.length ? step.relatedServices.map((svc) => {
                  const status = statuses[svc]
                  return (
                    <div key={svc} style={{ display: 'flex', alignItems: 'center', gap: '8px', marginBottom: '4px' }}>
                      <div style={{ fontSize: '12px', fontWeight: 500, color: 'var(--text-secondary)' }}>{svc}</div>
                      {status === 'up' && <span style={{ display: 'flex', alignItems: 'center', gap: '4px', fontSize: '11px', color: '#16a34a', fontWeight: 600, background: '#dcfce7', padding: '2px 6px', borderRadius: '4px' }}><CheckCircle2 size={12} aria-hidden="true" /> {t('AKTIVNÍ', 'UP')}</span>}
                      {status === 'down' && <span style={{ display: 'flex', alignItems: 'center', gap: '4px', fontSize: '11px', color: '#dc2626', fontWeight: 600, background: '#fee2e2', padding: '2px 6px', borderRadius: '4px' }}><XCircle size={12} aria-hidden="true" /> {t('NEDOSTUPNÉ', 'DOWN')}</span>}
                      {status === 'loading' && <span style={{ display: 'flex', alignItems: 'center', gap: '4px', fontSize: '11px', color: '#d97706', fontWeight: 600, background: '#fef3c7', padding: '2px 6px', borderRadius: '4px' }}><RefreshCw size={12} aria-hidden="true" className="animate-spin" /> {t('OVĚŘUJI', 'CHECKING')}</span>}
                      {status === 'unknown' && <span style={{ display: 'flex', alignItems: 'center', gap: '4px', fontSize: '11px', color: '#6b7280', fontWeight: 600, background: '#f3f4f6', padding: '2px 6px', borderRadius: '4px' }}><AlertCircle size={12} aria-hidden="true" /> {t('N/A', 'N/A')}</span>}
                    </div>
                  )
                }) : <div style={{ fontSize: '12px', color: 'var(--text-tertiary)' }}>{t('Služba není namapována', 'No service mapped')}</div>}
              </div>
            </div>
          )
        })}
      </div>
    </div>
  )
}

export function BpmnView({ processes }: { processes: BpmnProcess[] }) {
  const { t } = useLanguage()
  const [active, setActive] = useState(processes[0]?.slug)
  const process = processes.find((p) => p.slug === active) ?? processes[0]
  if (!process) return null

  return (
    <div>
      <DocsPageHeader
        crumbs={<>
            <span>OpenBank</span><span className="breadcrumb-sep">/</span>
            <span>Docs</span><span className="breadcrumb-sep">/</span>
            <span className="breadcrumb-current">Business Processes</span>
          </>}
        title="Business Process Diagrams (BPMN 2.0)"
        subtitle="Klíčové bankovní procesy vizualizované pro business, compliance a IT týmy"
        icon={<GitBranch aria-hidden="true" size={18} style={{ color: 'var(--accent)' }} />}
      />

      {/* Process tabs */}
      <div role="group" aria-label={t('Výběr obchodního procesu', 'Business process selector')} style={{ display: 'flex', gap: '8px', flexWrap: 'wrap', marginBottom: '20px' }}>
        {processes.map((p) => (
          <button key={p.slug} type="button" aria-pressed={active === p.slug} onClick={() => setActive(p.slug)}
            style={{
              padding: '8px 16px', fontSize: '13px', fontWeight: 600, borderRadius: '8px',
              border: `1px solid ${active === p.slug ? 'var(--accent)' : 'var(--border)'}`,
              background: active === p.slug ? 'var(--accent)' : 'var(--surface)',
              color: active === p.slug ? '#fff' : 'var(--text-secondary)',
              cursor: 'pointer', fontFamily: 'inherit',
            }}>{p.name}</button>
        ))}
      </div>

      {/* Process detail */}
      <div className="card" style={{ padding: '24px', marginBottom: '16px' }}>
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', marginBottom: '20px', flexWrap: 'wrap', gap: '12px' }}>
          <div>
            <div style={{ fontSize: '16px', fontWeight: 700, color: 'var(--text-primary)', marginBottom: '4px' }}>{process.name}</div>
            <div style={{ fontSize: '13px', color: 'var(--text-secondary)' }}>{process.desc}</div>
          </div>
          <div style={{
            padding: '6px 12px', background: '#fef2f2', border: '1px solid #fecaca',
            borderRadius: '6px', fontSize: '11px', fontWeight: 600, color: '#dc2626',
          }}>
            📋 {process.regulation}
          </div>
        </div>

        <BpmnDiagram process={process} />

        <ProcessLayerMap process={process} />

        <div style={{ marginTop: '16px', paddingTop: '16px', borderTop: '1px solid var(--border)' }}>
          <div style={{ fontSize: '11px', fontWeight: 700, textTransform: 'uppercase', letterSpacing: '0.06em', color: 'var(--text-tertiary)', marginBottom: '8px' }}>
            Zapojené services
          </div>
          <div style={{ display: 'flex', gap: '8px', flexWrap: 'wrap' }}>
            {process.services.map((svc) => (
              <span key={svc} style={{
                padding: '4px 10px', background: 'var(--surface-2)', border: '1px solid var(--border)',
                borderRadius: '20px', fontSize: '12px', fontFamily: 'JetBrains Mono, monospace',
                color: 'var(--text-secondary)',
              }}>{svc}</span>
            ))}
          </div>
        </div>
      </div>

      {/* Legend */}
      <div className="card" style={{ padding: '16px' }}>
        <div style={{ fontSize: '12px', fontWeight: 700, color: 'var(--text-secondary)', marginBottom: '10px' }}>LEGENDA</div>
        <div style={{ display: 'flex', gap: '20px', flexWrap: 'wrap' }}>
          {[
            { shape: 'circle', color: '#22c55e', label: 'Start event' },
            { shape: 'circle', color: '#1e40af', label: 'End event' },
            { shape: 'circle', color: '#dc2626', label: 'Error end' },
            { shape: 'diamond', color: '#d97706', label: 'Gateway (rozhodnutí)' },
            { shape: 'rect', color: '#93c5fd', label: 'Task (Core Banking)' },
            { shape: 'rect', color: '#fca5a5', label: 'Task (Compliance)' },
            { shape: 'rect', color: '#fde68a', label: 'Task (PSD2)' },
            { shape: 'event', color: ASYNC_COLOR, label: 'Message event (catch/throw)' },
            { shape: 'async', color: ASYNC_COLOR, label: 'Async event / outbox (Kafka)' },
            { shape: 'status-up', color: '#16a34a', label: 'Service UP' },
            { shape: 'status-down', color: '#dc2626', label: 'Service DOWN' },
          ].map((item) => (
            <div key={item.label} style={{ display: 'flex', alignItems: 'center', gap: '8px', fontSize: '11px', color: 'var(--text-secondary)' }}>
              {item.shape.startsWith('status-') ? (
                item.shape === 'status-up' ? <CheckCircle2 size={14} color={item.color} /> : <XCircle size={14} color={item.color} />
              ) : item.shape === 'async' ? (
                <svg width="22" height="8"><line x1="0" y1="4" x2="22" y2="4" stroke={item.color} strokeWidth="1.5" strokeDasharray="5,3" /></svg>
              ) : item.shape === 'event' ? (
                <svg width="14" height="14"><circle cx="7" cy="7" r="6" fill="none" stroke={item.color} strokeWidth="1.5" strokeDasharray="3,2" /></svg>
              ) : (
                <div style={{
                  width: item.shape === 'diamond' ? '12px' : '14px',
                  height: item.shape === 'diamond' ? '12px' : '10px',
                  background: item.color,
                  borderRadius: item.shape === 'circle' ? '50%' : item.shape === 'rect' ? '2px' : '0',
                  transform: item.shape === 'diamond' ? 'rotate(45deg)' : undefined,
                  flexShrink: 0,
                }} />
              )}
              {item.label}
            </div>
          ))}
        </div>
      </div>
    </div>
  )
}
