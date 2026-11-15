// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Presentational building blocks for the Test Intelligence explainer pages
// (/system/tests/mobile, /system/tests/web). Server-safe: no hooks, no client JS.
// Every visible string arrives through props already translated by the page, so the
// i18n guard sees the t(cs, en) calls where the copy lives.

import type { ReactNode } from 'react'

export type GateMode = 'blocks' | 'advisory' | 'nightly' | 'planned'

export interface ModeLabels {
  blocks: string
  advisory: string
  nightly: string
  planned: string
}

const MODE_STYLE: Record<GateMode, { color: string; bg: string; border: string }> = {
  blocks: { color: 'var(--success-text)', bg: 'var(--success-bg)', border: 'var(--success-border)' },
  advisory: { color: 'var(--warning-text)', bg: 'var(--warning-bg)', border: 'var(--warning-border)' },
  nightly: { color: 'var(--accent-text)', bg: 'var(--accent-bg)', border: 'var(--accent-border)' },
  planned: { color: 'var(--text-tertiary)', bg: 'var(--surface-2)', border: 'var(--border)' },
}

export function ModeBadge({ mode, labels }: { mode: GateMode; labels: ModeLabels }) {
  const s = MODE_STYLE[mode]
  return (
    <span style={{
      display: 'inline-flex', alignItems: 'center', gap: 4, fontSize: 11, fontWeight: 600,
      padding: '2px 8px', borderRadius: 999, color: s.color, background: s.bg,
      border: `1px solid ${s.border}`, whiteSpace: 'nowrap',
    }}>{labels[mode]}</span>
  )
}

export function ModeLegend({ labels, title }: { labels: ModeLabels; title: string }) {
  return (
    <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8, alignItems: 'center', fontSize: 12, color: 'var(--text-secondary)' }}>
      <span>{title}</span>
      {(['blocks', 'nightly', 'advisory', 'planned'] as GateMode[]).map(m => <ModeBadge key={m} mode={m} labels={labels} />)}
    </div>
  )
}

export function ExplainerSection({ title, subtitle, children, id }: { title: string; subtitle?: string; children: ReactNode; id?: string }) {
  return (
    <section id={id} style={{ marginTop: 28 }}>
      <h2 style={{ fontSize: 17, fontWeight: 700, color: 'var(--text-primary)', margin: 0 }}>{title}</h2>
      {subtitle && <p style={{ fontSize: 13, color: 'var(--text-secondary)', margin: '4px 0 14px', lineHeight: 1.55, maxWidth: 860 }}>{subtitle}</p>}
      {children}
    </section>
  )
}

export function KeyNumbers({ items }: { items: { value: string; label: string; hint?: string }[] }) {
  return (
    <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(170px, 1fr))', gap: 12 }}>
      {items.map(item => (
        <div key={item.label} className="stat-card" style={{ padding: 14 }}>
          <div style={{ fontSize: 26, fontWeight: 700, color: 'var(--accent-text)', lineHeight: 1.1 }}>{item.value}</div>
          <div style={{ fontSize: 13, fontWeight: 600, color: 'var(--text-primary)', marginTop: 4 }}>{item.label}</div>
          {item.hint && <div style={{ fontSize: 12, color: 'var(--text-tertiary)', marginTop: 2, lineHeight: 1.4 }}>{item.hint}</div>}
        </div>
      ))}
    </div>
  )
}

export interface PyramidLayer {
  label: string
  tools: string
  mode: GateMode
}

/**
 * Test pyramid, widest (cheapest, most numerous) at the bottom. `layers` is given top-first.
 * Drawn as SVG trapezoids so the shape itself carries the "many cheap, few expensive" message.
 */
export function StackPyramid({ layers, title, cheapLabel, costlyLabel }: {
  layers: PyramidLayer[]
  title: string
  cheapLabel: string
  costlyLabel: string
}) {
  const W = 760
  const rowH = 46
  const gap = 6
  const H = layers.length * (rowH + gap) + 20
  const minW = 200
  const maxW = 560
  const cx = 300
  return (
    <div style={{ overflowX: 'auto', maxWidth: 860 }}>
    <svg viewBox={`0 0 ${W} ${H}`} role="img" style={{ width: '100%', minWidth: 600, height: 'auto', display: 'block', background: 'var(--surface-2)', borderRadius: 12, border: '1px solid var(--border)' }}>
      <title>{title}</title>
      <defs>
        <marker id="pyr-arrow" viewBox="0 0 10 10" refX="5" refY="5" markerWidth="6" markerHeight="6" orient="auto-start-reverse">
          <path d="M0,0 L10,5 L0,10 z" fill="var(--text-tertiary)" />
        </marker>
      </defs>
      {layers.map((layer, i) => {
        const n = layers.length
        const topW = minW + ((maxW - minW) * i) / n
        const botW = minW + ((maxW - minW) * (i + 1)) / n
        const y = 10 + i * (rowH + gap)
        const s = MODE_STYLE[layer.mode]
        const pts = [
          [cx - topW / 2, y], [cx + topW / 2, y], [cx + botW / 2, y + rowH], [cx - botW / 2, y + rowH],
        ].map(p => p.join(',')).join(' ')
        return (
          <g key={layer.label}>
            <polygon points={pts} fill={s.bg} stroke={s.border} strokeWidth={1.5} />
            <text x={cx} y={y + 19} textAnchor="middle" fontSize={13} fontWeight={700} fill="var(--text-primary)">{layer.label}</text>
            <text x={cx} y={y + 35} textAnchor="middle" fontSize={11} fill="var(--text-secondary)">{layer.tools}</text>
          </g>
        )
      })}
      <line x1={650} y1={H - 16} x2={650} y2={16} stroke="var(--text-tertiary)" strokeWidth={1.5} markerEnd="url(#pyr-arrow)" />
      <text x={664} y={24} fontSize={11} fill="var(--text-secondary)">{costlyLabel}</text>
      <text x={664} y={H - 14} fontSize={11} fill="var(--text-secondary)">{cheapLabel}</text>
    </svg>
    </div>
  )
}

export interface PipelineStage {
  title: string
  when: string
  items: { label: string; mode: GateMode }[]
}

/** Left-to-right lifecycle of a change: each stage lists what runs there and whether it blocks. */
export function PipelineStages({ stages, labels }: { stages: PipelineStage[]; labels: ModeLabels }) {
  return (
    <div style={{ display: 'flex', flexWrap: 'wrap', gap: 0, alignItems: 'stretch' }}>
      {stages.map((stage, i) => (
        <div key={stage.title} style={{ display: 'flex', alignItems: 'stretch', flex: '1 1 220px', minWidth: 220 }}>
          <div style={{ flex: 1, border: '1px solid var(--border)', borderRadius: 12, background: 'var(--surface-1)', padding: 12, margin: '0 0 10px' }}>
            <div style={{ fontSize: 11, fontWeight: 700, letterSpacing: 0.4, color: 'var(--accent-text)' }}>{String(i + 1).padStart(2, '0')}</div>
            <div style={{ fontSize: 14, fontWeight: 700, color: 'var(--text-primary)' }}>{stage.title}</div>
            <div style={{ fontSize: 12, color: 'var(--text-tertiary)', marginBottom: 8 }}>{stage.when}</div>
            <ul style={{ listStyle: 'none', padding: 0, margin: 0, display: 'grid', gap: 6 }}>
              {stage.items.map(item => (
                <li key={item.label} style={{ display: 'flex', justifyContent: 'space-between', gap: 8, alignItems: 'center', fontSize: 12, color: 'var(--text-secondary)' }}>
                  <span>{item.label}</span><ModeBadge mode={item.mode} labels={labels} />
                </li>
              ))}
            </ul>
          </div>
          {i < stages.length - 1 && <div aria-hidden="true" style={{ alignSelf: 'center', padding: '0 6px', color: 'var(--text-tertiary)', fontSize: 18 }}>›</div>}
        </div>
      ))}
    </div>
  )
}

/** A chain of boxes joined by arrows — used for the evidence/observability data path. */
export function FlowChain({ steps, title }: { steps: { label: string; detail: string }[]; title: string }) {
  const boxW = 150
  const gapW = 34
  const W = steps.length * boxW + (steps.length - 1) * gapW + 20
  const H = 110
  return (
    <div style={{ overflowX: 'auto' }}>
      <svg viewBox={`0 0 ${W} ${H}`} role="img" style={{ minWidth: Math.min(W, 640), width: '100%', height: 'auto', background: 'var(--surface-2)', borderRadius: 12, border: '1px solid var(--border)' }}>
        <title>{title}</title>
        <defs>
          <marker id="flow-arrow" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto">
            <path d="M0,0 L10,5 L0,10 z" fill="var(--accent)" />
          </marker>
        </defs>
        {steps.map((step, i) => {
          const x = 10 + i * (boxW + gapW)
          return (
            <g key={step.label}>
              <rect x={x} y={18} width={boxW} height={74} rx={10} fill="var(--surface-1)" stroke="var(--accent-border)" />
              <text x={x + boxW / 2} y={44} textAnchor="middle" fontSize={12} fontWeight={700} fill="var(--text-primary)">{step.label}</text>
              <foreignObject x={x + 6} y={52} width={boxW - 12} height={38}>
                <div style={{ fontSize: 10, lineHeight: 1.3, color: 'var(--text-secondary)', textAlign: 'center' }}>{step.detail}</div>
              </foreignObject>
              {i < steps.length - 1 && <line x1={x + boxW + 2} y1={55} x2={x + boxW + gapW - 4} y2={55} stroke="var(--accent)" strokeWidth={2} markerEnd="url(#flow-arrow)" />}
            </g>
          )
        })}
      </svg>
    </div>
  )
}

export interface LayerExplanation {
  name: string
  tool: string
  what: string
  why: string
  when: string
  mode: GateMode
  caught?: string
}

/** One card per layer: what / with what / why / when, plus a real defect it caught when there is one. */
export function LayerCards({ layers, labels, captions }: {
  layers: LayerExplanation[]
  labels: ModeLabels
  captions: { what: string; why: string; when: string; caught: string }
}) {
  return (
    <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(280px, 1fr))', gap: 12 }}>
      {layers.map(layer => (
        <article key={layer.name} style={{ border: '1px solid var(--border)', borderRadius: 12, background: 'var(--surface-1)', padding: 14, display: 'grid', gap: 8, alignContent: 'start' }}>
          <header style={{ display: 'flex', justifyContent: 'space-between', gap: 8, alignItems: 'flex-start' }}>
            <div>
              <h3 style={{ fontSize: 14, fontWeight: 700, color: 'var(--text-primary)', margin: 0 }}>{layer.name}</h3>
              <div style={{ fontSize: 12, color: 'var(--accent-text)', fontFamily: 'JetBrains Mono, monospace', marginTop: 2 }}>{layer.tool}</div>
            </div>
            <ModeBadge mode={layer.mode} labels={labels} />
          </header>
          <Row caption={captions.what} text={layer.what} />
          <Row caption={captions.why} text={layer.why} />
          <Row caption={captions.when} text={layer.when} />
          {layer.caught && (
            <div style={{ fontSize: 12, lineHeight: 1.45, color: 'var(--success-text)', background: 'var(--success-bg)', border: '1px solid var(--success-border)', borderRadius: 8, padding: '6px 8px' }}>
              <strong>{captions.caught}</strong> {layer.caught}
            </div>
          )}
        </article>
      ))}
    </div>
  )
}

function Row({ caption, text }: { caption: string; text: string }) {
  return (
    <div style={{ fontSize: 12, lineHeight: 1.5, color: 'var(--text-secondary)' }}>
      <span style={{ fontWeight: 600, color: 'var(--text-primary)' }}>{caption}</span> {text}
    </div>
  )
}

/** The falsification loop: a gate is trusted only after it was seen failing on a planted defect. */
export function FalsificationLoop({ steps, title, center }: { steps: [string, string, string, string]; title: string; center: string }) {
  const pos = [[380, 40], [640, 130], [380, 220], [120, 130]] as const
  return (
    <div style={{ overflowX: 'auto' }}>
    <svg viewBox="0 0 760 262" role="img" style={{ width: '100%', minWidth: 600, height: 'auto', background: 'var(--surface-2)', borderRadius: 12, border: '1px solid var(--border)' }}>
      <title>{title}</title>
      <defs>
        <marker id="fals-arrow" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto">
          <path d="M0,0 L10,5 L0,10 z" fill="var(--accent)" />
        </marker>
      </defs>
      <ellipse cx={380} cy={130} rx={250} ry={88} fill="none" stroke="var(--accent-border)" strokeDasharray="5 5" />
      <text x={380} y={126} textAnchor="middle" fontSize={13} fontWeight={700} fill="var(--accent-text)">{center}</text>
      {steps.map((label, i) => {
        const [x, y] = pos[i]
        const tone = i === 1 ? MODE_STYLE.advisory : MODE_STYLE.blocks
        return (
          <g key={label}>
            <rect x={x - 105} y={y - 22} width={210} height={44} rx={10} fill={tone.bg} stroke={tone.border} />
            <foreignObject x={x - 100} y={y - 19} width={200} height={38}>
              <div style={{ fontSize: 11, lineHeight: 1.3, color: 'var(--text-primary)', textAlign: 'center', fontWeight: 600 }}>{`${i + 1}. ${label}`}</div>
            </foreignObject>
          </g>
        )
      })}
      <path d="M485,48 Q600,60 625,106" fill="none" stroke="var(--accent)" strokeWidth={2} markerEnd="url(#fals-arrow)" />
      <path d="M625,154 Q600,205 487,214" fill="none" stroke="var(--accent)" strokeWidth={2} markerEnd="url(#fals-arrow)" />
      <path d="M275,214 Q160,205 135,154" fill="none" stroke="var(--accent)" strokeWidth={2} markerEnd="url(#fals-arrow)" />
      <path d="M135,106 Q160,60 273,48" fill="none" stroke="var(--accent)" strokeWidth={2} markerEnd="url(#fals-arrow)" />
    </svg>
    </div>
  )
}

/** Honest status list: what is live, what is landing, what is not built. */
export function StatusList({ items, labels }: { items: { label: string; detail: string; mode: GateMode }[]; labels: ModeLabels }) {
  return (
    <ul style={{ listStyle: 'none', padding: 0, margin: 0, display: 'grid', gap: 8 }}>
      {items.map(item => (
        <li key={item.label} style={{ display: 'grid', gridTemplateColumns: 'auto 1fr', gap: 10, alignItems: 'start', border: '1px solid var(--border)', borderRadius: 10, padding: '8px 10px', background: 'var(--surface-1)' }}>
          <ModeBadge mode={item.mode} labels={labels} />
          <div style={{ fontSize: 12, lineHeight: 1.5, color: 'var(--text-secondary)' }}>
            <span style={{ fontWeight: 600, color: 'var(--text-primary)' }}>{item.label}</span> {item.detail}
          </div>
        </li>
      ))}
    </ul>
  )
}
