// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

// ---------------------------------------------------------------------------
// ProcessView — renders a "one process, three lenses" manifest (ProcessSchema).
// Pure presentation: ALL content comes from the `proc` prop (loaded + validated
// server-side from src/content/processes/<slug>.yaml). The reality/target toggle,
// lens tabs, tech-node selection and weighted compliance gauge live here; the
// data does not. Adding a process = adding a YAML manifest, not a new page.
// ---------------------------------------------------------------------------

import { useState } from 'react'
import type { ElementType } from 'react'
import { ShieldCheck, Info, CheckCircle2, CircleDashed, Circle, X } from 'lucide-react'
import { Mermaid } from '@/components/docs/Mermaid'
import { DocsPageHeader } from '@/components/docs/DocsPageHeader'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { overallScore, type Process, type Status, type TechNode } from '@/lib/docs/process/schema'

type Mode = 'reality' | 'target'
type Lens = 'story' | 'token' | 'tech'

const STATUS_META: Record<Status, { label: string; color: string; bg: string; border: string; Icon: ElementType }> = {
  live:    { label: 'Live (dev sandbox)',             color: '#059669', bg: '#ecfdf5', border: '#6ee7b7', Icon: CheckCircle2 },
  partial: { label: 'Partial (deployed, incomplete)', color: '#d97706', bg: '#fffbeb', border: '#fcd34d', Icon: CircleDashed },
  planned: { label: 'Planned (roadmap)',              color: '#94a3b8', bg: '#f8fafc', border: '#cbd5e1', Icon: Circle },
}

// Title icons a manifest may name (lucide-react). Extend as new processes need.
const ICON_MAP: Record<string, ElementType> = { ShieldCheck }

function StatusDot({ status }: { status: Status }) {
  const m = STATUS_META[status]
  return <m.Icon size={13} aria-hidden="true" style={{ color: m.color, flexShrink: 0 }} />
}

function StatusChip({ status }: { status: Status }) {
  const m = STATUS_META[status]
  return (
    <span style={{
      display: 'inline-flex', alignItems: 'center', gap: '4px', fontSize: '11px', fontWeight: 600,
      padding: '2px 8px', borderRadius: '20px', background: m.bg, color: m.color, border: `1px solid ${m.border}`,
    }}>
      <StatusDot status={status} />{m.label}
    </span>
  )
}

export function ProcessView({ proc }: { proc: Process }) {
  const { t } = useLanguage()
  const [mode, setMode] = useState<Mode>('reality')
  const [lens, setLens] = useState<Lens>('story')
  const [selected, setSelected] = useState<TechNode | null>(null)

  const TitleIcon = ICON_MAP[proc.icon] ?? ShieldCheck
  const overall = overallScore(proc.controls)
  const visibleStory = mode === 'reality' ? proc.story.filter(s => s.status === 'live') : proc.story
  const gaugeColor = overall < 40 ? '#dc2626' : overall < 70 ? '#d97706' : '#059669'

  return (
    <div>
      <DocsPageHeader
        crumbs={<>
            <span>OpenBank</span><span className="breadcrumb-sep">/</span>
            <span>Docs</span><span className="breadcrumb-sep">/</span>
            <span className="breadcrumb-current">{proc.title}</span>
          </>}
        title={proc.title}
        subtitle={proc.subtitle}
        icon={<TitleIcon aria-hidden="true" size={18} style={{ color: 'var(--accent)' }} />}
      />

      {/* Honesty banner + reality/target toggle */}
      <div className="card" style={{ padding: '12px 16px', marginBottom: '16px', display: 'flex', alignItems: 'center', gap: '16px', flexWrap: 'wrap' }}>
        <div role="group" aria-label={t('Režim zobrazení procesu', 'Process view mode')} style={{ display: 'inline-flex', borderRadius: '8px', border: '1px solid var(--border)', overflow: 'hidden' }}>
          {(['reality', 'target'] as Mode[]).map(m => (
            <button key={m} type="button" aria-pressed={mode === m} onClick={() => setMode(m)} style={{
              padding: '6px 14px', fontSize: '13px', fontWeight: 600, border: 'none', cursor: 'pointer',
              fontFamily: 'inherit',
              background: mode === m ? 'var(--accent)' : 'var(--surface)',
              color: mode === m ? '#fff' : 'var(--text-secondary)',
            }}>{m === 'reality' ? 'Realita (dnes)' : 'Cíl (CNB/EBA)'}</button>
          ))}
        </div>
        {(Object.keys(STATUS_META) as Status[]).map(s => (
          <div key={s} style={{ display: 'flex', alignItems: 'center', gap: '6px' }}>
            <StatusDot status={s} />
            <span style={{ fontSize: '12px', fontWeight: 600, color: 'var(--text-primary)' }}>{STATUS_META[s].label}</span>
          </div>
        ))}
        <div style={{ display: 'flex', alignItems: 'center', gap: '6px', marginLeft: 'auto', fontSize: '11px', color: 'var(--text-secondary)' }}>
          <Info size={13} aria-hidden="true" />
          Skóre = produkční compliance cíl. Dev sandbox je záměrně mimo scope pro některé prod controls (TLS, secrets — řeší infra vrstva).
        </div>
      </div>

      {/* Lens tabs */}
      <div role="group" aria-label={t('Čočka zobrazení procesu', 'Process view lens')} style={{ display: 'flex', gap: '8px', flexWrap: 'wrap', marginBottom: '16px' }}>
        {([['story', '① Příběh'], ['token', '② Tokeny'], ['tech', '③ Technologie']] as [Lens, string][]).map(([id, label]) => (
          <button key={id} type="button" aria-pressed={lens === id} onClick={() => setLens(id)} style={{
            padding: '8px 16px', fontSize: '13px', fontWeight: 600, borderRadius: '8px',
            border: `1px solid ${lens === id ? 'var(--accent)' : 'var(--border)'}`,
            background: lens === id ? 'var(--accent)' : 'var(--surface)',
            color: lens === id ? '#fff' : 'var(--text-secondary)', cursor: 'pointer', fontFamily: 'inherit',
          }}>{label}</button>
        ))}
      </div>

      <div style={{ display: 'grid', gridTemplateColumns: 'minmax(0, 1fr) 340px', gap: '16px', alignItems: 'start' }}>
        {/* Lens content */}
        <div className="card" style={{ padding: '24px' }}>
          {lens === 'story' && (
            <ol style={{ margin: 0, paddingLeft: '20px', display: 'flex', flexDirection: 'column', gap: '12px' }}>
              {visibleStory.map((s, i) => (
                <li key={i} style={{ fontSize: '14px', color: 'var(--text-primary)', lineHeight: 1.5 }}>
                  <span style={{ marginRight: '8px' }}>{s.text}</span>
                  {s.status !== 'live' && <StatusChip status={s.status} />}
                </li>
              ))}
            </ol>
          )}

          {lens === 'token' && (
            <div>
              <Mermaid chart={mode === 'reality' ? proc.token.reality : proc.token.target} />
              <div style={{ marginTop: '20px' }}>
                <div style={{ fontSize: '11px', fontWeight: 700, textTransform: 'uppercase', letterSpacing: '0.06em', color: 'var(--text-tertiary)', marginBottom: '8px' }}>
                  Anatomie access tokenu (JWT)
                </div>
                <div style={{ display: 'grid', gap: '6px' }}>
                  {proc.token.claims.map(c => (
                    <div key={c.claim} style={{ display: 'flex', gap: '10px', fontSize: '12px', alignItems: 'baseline' }}>
                      <code style={{ fontFamily: 'JetBrains Mono, monospace', background: 'var(--surface-2)', padding: '2px 6px', borderRadius: '4px', color: 'var(--text-primary)', minWidth: '160px' }}>{c.claim}</code>
                      <span style={{ color: 'var(--text-secondary)' }}>{c.desc}</span>
                    </div>
                  ))}
                </div>
                <p style={{ fontSize: '12px', color: 'var(--text-secondary)', marginTop: '12px', lineHeight: 1.6 }}>
                  Roli čte UI z access tokenu, ale <strong>autoritou je backend</strong> přes JWKS validaci — ne cookie.
                </p>
              </div>
            </div>
          )}

          {lens === 'tech' && (
            <div style={{ display: 'flex', flexDirection: 'column', gap: '12px' }}>
              {proc.tech.map(z => (
                <div key={z.title} style={{ border: `1.5px solid ${z.accent}`, borderRadius: '12px', padding: '14px 16px', background: `${z.accent}08` }}>
                  <div style={{ fontSize: '11px', fontWeight: 800, letterSpacing: '0.06em', color: z.accent, textTransform: 'uppercase', marginBottom: '10px' }}>{z.title}</div>
                  <div style={{ display: 'flex', flexWrap: 'wrap', gap: '8px' }}>
                    {z.nodes.map(n => {
                      const m = STATUS_META[n.status]
                      const active = selected?.id === n.id
                      return (
                        <button key={n.id} type="button" aria-label={n.name} aria-pressed={active} onClick={() => setSelected(active ? null : n)} title={n.desc} style={{
                          display: 'flex', alignItems: 'center', gap: '6px', padding: '6px 10px', borderRadius: '8px',
                          cursor: 'pointer', background: m.bg, border: `1px solid ${active ? m.color : m.border}`,
                          boxShadow: active ? `0 0 0 2px ${m.color}55` : 'none',
                          color: 'var(--text-primary)', fontSize: '12px', fontWeight: 600, fontFamily: 'inherit', textAlign: 'left',
                        }}>
                          <StatusDot status={n.status} />{n.name}
                        </button>
                      )
                    })}
                  </div>
                </div>
              ))}
              {selected && (
                <div className="card" style={{ padding: '14px 16px', borderLeft: `3px solid ${STATUS_META[selected.status].color}` }}>
                  <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '6px' }}>
                    <span style={{ fontSize: '14px', fontWeight: 700, color: 'var(--text-primary)' }}>{selected.name}</span>
                    <button type="button" aria-label={t('Zavřít technologické detaily', 'Close technology details')} onClick={() => setSelected(null)} style={{ background: 'none', border: 'none', cursor: 'pointer', color: 'var(--text-secondary)', padding: 0 }}><X size={15} aria-hidden="true" /></button>
                  </div>
                  <div style={{ marginBottom: '8px' }}><StatusChip status={selected.status} /></div>
                  <p style={{ fontSize: '13px', color: 'var(--text-secondary)', lineHeight: 1.6, margin: 0 }}>{selected.desc}</p>
                </div>
              )}
              <p style={{ fontSize: '11px', color: 'var(--text-tertiary)', margin: 0 }}>
                Technologická čočka je vždy poctivý overlay reálného stavu — barva = co skutečně běží, ne co je v plánu.
              </p>
            </div>
          )}
        </div>

        {/* Compliance scorecard */}
        <div className="card" style={{ padding: '20px', position: 'sticky', top: '16px' }}>
          <div style={{ fontSize: '12px', fontWeight: 700, textTransform: 'uppercase', letterSpacing: '0.06em', color: 'var(--text-tertiary)', marginBottom: '12px' }}>
            Compliance (produkční cíl)
          </div>
          <div style={{ display: 'flex', alignItems: 'center', gap: '14px', marginBottom: '16px' }}>
            <div style={{ fontSize: '40px', fontWeight: 800, color: gaugeColor, lineHeight: 1 }}>{overall}%</div>
            <div style={{ fontSize: '12px', color: 'var(--text-secondary)', lineHeight: 1.4 }}>
              vážený compliance score vůči CNB/EBA/PSD2/DORA. Cíl: 100 %.
            </div>
          </div>
          <div style={{ display: 'flex', flexDirection: 'column', gap: '12px' }}>
            {proc.controls.map(c => (
              <div key={c.name} title={c.why}>
                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'baseline', gap: '8px', marginBottom: '3px' }}>
                  <span style={{ fontSize: '12px', fontWeight: 600, color: 'var(--text-primary)' }}>{c.name}</span>
                  <span style={{ fontSize: '12px', fontWeight: 700, color: STATUS_META[c.status].color }}>{c.pct}%</span>
                </div>
                <div style={{ height: '6px', borderRadius: '3px', background: 'var(--surface-2)', overflow: 'hidden', marginBottom: '3px' }}>
                  <div style={{ width: `${c.pct}%`, height: '100%', background: STATUS_META[c.status].color }} />
                </div>
                <div style={{ fontSize: '10px', color: 'var(--text-tertiary)' }}>{c.regulation}</div>
                <div style={{ fontSize: '11px', color: 'var(--text-secondary)', lineHeight: 1.4, marginTop: '2px' }}>{c.why}</div>
              </div>
            ))}
          </div>
        </div>
      </div>
    </div>
  )
}
