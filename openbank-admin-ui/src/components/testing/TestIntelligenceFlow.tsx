// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

'use client'

import { useState } from 'react'
import { Bot, Box, BrainCircuit, GitPullRequest, Radar, ShieldCheck } from 'lucide-react'
import type { TestIntelligenceReport } from '@/lib/types/test-intelligence'

const nodes = [
  { id: 'change', x: 70, y: 104, label: 'Change', sub: 'commit + service', icon: GitPullRequest },
  { id: 'ci', x: 235, y: 58, label: 'CI evidence', sub: 'JUnit · Kover · Pact', icon: Box },
  { id: 'sandbox', x: 235, y: 150, label: 'Sandbox', sub: 'k6 · synthetic journeys', icon: Radar },
  { id: 'normalize', x: 430, y: 104, label: 'Evidence model', sub: 'provenance · freshness', icon: ShieldCheck },
  { id: 'agents', x: 620, y: 58, label: 'AI agents', sub: 'Fleck · Dora · RCA', icon: BrainCircuit },
  { id: 'decision', x: 620, y: 150, label: 'Human decision', sub: 'gates stay deterministic', icon: Bot },
]

const edges = [
  ['change', 'ci'], ['change', 'sandbox'], ['ci', 'normalize'], ['sandbox', 'normalize'],
  ['normalize', 'agents'], ['normalize', 'decision'], ['agents', 'decision'],
]

export function TestIntelligenceFlow({ report }: { report?: TestIntelligenceReport | null }) {
  const [selected, setSelected] = useState('normalize')
  const byId = Object.fromEntries(nodes.map(node => [node.id, node]))
  const activeJourneys = report?.syntheticJourneys.filter(item => item.status === 'active').length ?? 0
  const evidenced = report?.totals.componentsWithExecutionEvidence ?? 0
  const total = report?.totals.components ?? 0
  const attention = (report?.totals.failingEvidence ?? 0) + (report?.totals.missingEvidence ?? 0) + (report?.totals.staleEvidence ?? 0)
  const telemetry: Record<string, string> = {
    change: report ? new Date(report.collectedAt).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }) : 'awaiting',
    ci: report ? `${evidenced}/${total}` : 'awaiting', sandbox: report ? `${activeJourneys} active` : 'awaiting',
    normalize: report ? `schema v${report.schemaVersion}` : 'schema v1', agents: 'HITL',
    decision: report ? `${attention} attention` : 'awaiting',
  }
  return <section className="ti-flow" aria-labelledby="test-intelligence-flow-title">
    <div className="ti-flow-head"><div><span>OPENBANK QUALITY NERVOUS SYSTEM</span><h2 id="test-intelligence-flow-title">Evidence moves. Facts stay immutable.</h2></div><div className={`ti-live ${attention ? 'attention' : ''}`}><i />{report ? (attention ? ' ATTENTION' : ' HEALTHY') : ' LIVE MODEL'}</div></div>
    <svg viewBox="0 0 700 210" role="img" aria-label="Animated flow from a code change through CI and sandbox evidence into normalization, AI agents and human decisions">
      <defs><filter id="ti-glow"><feGaussianBlur stdDeviation="3" result="blur"/><feMerge><feMergeNode in="blur"/><feMergeNode in="SourceGraphic"/></feMerge></filter></defs>
      {edges.map(([from, to], index) => {
        const a = byId[from]; const b = byId[to]
        return <g key={`${from}-${to}`}><line className="ti-edge" x1={a.x + 49} y1={a.y} x2={b.x - 49} y2={b.y} />
          <circle className="ti-pulse" r="3" style={{ animationDelay: `${index * -0.42}s` }}><animateMotion dur="2.9s" repeatCount="indefinite" path={`M ${a.x + 49} ${a.y} L ${b.x - 49} ${b.y}`} /></circle></g>
      })}
      {nodes.map(node => {
        const active = selected === node.id
        return <g key={node.id} role="button" aria-label={`${node.label}: ${node.sub}`} onClick={() => setSelected(node.id)} onKeyDown={event => { if (event.key === 'Enter' || event.key === ' ') setSelected(node.id) }} tabIndex={0} className="ti-node" transform={`translate(${node.x - 57} ${node.y - 27})`}>
          <rect width="114" height="54" rx="11" className={active ? 'selected' : ''}/><foreignObject x="9" y="9" width="20" height="20"><node.icon size={18} /></foreignObject>
          <text x="34" y="21" className="title">{node.label}</text><text x="12" y="40" className="sub">{node.sub}</text><text x="102" y="11" textAnchor="end" className="value">{telemetry[node.id]}</text>
        </g>
      })}
    </svg>
    <div className="ti-detail"><strong>{byId[selected].label}</strong><span>{byId[selected].sub}</span><span>{selected === 'agents' ? 'Agents explain, cluster and propose. They never rewrite measured evidence or approve their own remediation.' : selected === 'decision' ? 'Required controls remain explicit; missing evidence cannot be averaged away.' : 'Select a node to inspect its responsibility in the testing system.'}</span></div>
    <style jsx>{`
      .ti-flow{position:relative;overflow:hidden;margin-bottom:20px;padding:18px;border:1px solid color-mix(in srgb,var(--accent) 25%,var(--border));border-radius:16px;background:radial-gradient(circle at 48% 20%,color-mix(in srgb,var(--accent) 12%,transparent),transparent 45%),linear-gradient(135deg,color-mix(in srgb,var(--surface-1) 92%,#07111f),var(--surface-1));box-shadow:0 18px 50px color-mix(in srgb,var(--accent) 8%,transparent)}
      .ti-flow:before{content:"";position:absolute;inset:0;opacity:.22;background-image:linear-gradient(var(--border-subtle) 1px,transparent 1px),linear-gradient(90deg,var(--border-subtle) 1px,transparent 1px);background-size:24px 24px;pointer-events:none}
      .ti-flow-head{position:relative;display:flex;justify-content:space-between;gap:16px;align-items:flex-start}.ti-flow-head span{font-size:9px;letter-spacing:.16em;color:var(--accent);font-weight:700}.ti-flow-head h2{font-size:17px;margin:4px 0 0}.ti-live{font-size:9px;color:#16a34a;letter-spacing:.12em;padding:6px 9px;border:1px solid color-mix(in srgb,#16a34a 35%,transparent);border-radius:999px;background:color-mix(in srgb,#16a34a 7%,transparent)}.ti-live.attention{color:#d97706;border-color:color-mix(in srgb,#d97706 40%,transparent);background:color-mix(in srgb,#d97706 8%,transparent)}.ti-live i{display:inline-block;width:7px;height:7px;margin-right:6px;border-radius:50%;background:currentColor;box-shadow:0 0 10px currentColor;animation:breathe 1.7s ease-in-out infinite}
      svg{position:relative;width:100%;min-height:190px}.ti-edge{stroke:color-mix(in srgb,var(--accent) 28%,var(--border));stroke-width:1.2;stroke-dasharray:3 4}.ti-pulse{fill:var(--accent);filter:url(#ti-glow)}.ti-node{cursor:pointer;outline:none}.ti-node rect{fill:color-mix(in srgb,var(--surface-2) 94%,var(--accent));stroke:var(--border);stroke-width:1;transition:.2s}.ti-node rect.selected,.ti-node:hover rect{stroke:var(--accent);fill:color-mix(in srgb,var(--accent) 11%,var(--surface-2));filter:url(#ti-glow)}.ti-node foreignObject{color:var(--accent)}.ti-node .title{fill:var(--text-primary);font-size:10px;font-weight:700}.ti-node .sub{fill:var(--text-tertiary);font-size:8px}.ti-node .value{fill:var(--accent);font-size:6.5px;font-weight:700;text-transform:uppercase}.ti-detail{position:relative;display:grid;grid-template-columns:120px 170px 1fr;gap:12px;padding-top:12px;border-top:1px solid var(--border);font-size:11px;color:var(--text-secondary)}.ti-detail strong{color:var(--text-primary)}
      @keyframes breathe{50%{opacity:.4;transform:scale(.72)}}
      @media(max-width:720px){.ti-flow{padding:13px}.ti-flow-head h2{font-size:15px}.ti-live{display:none}svg{min-width:650px}.ti-flow{overflow-x:auto}.ti-detail{grid-template-columns:1fr}.ti-detail span:nth-child(2){color:var(--accent)}}
      @media(prefers-reduced-motion:reduce){.ti-pulse,.ti-live i{animation:none}.ti-pulse{display:none}}
    `}</style>
  </section>
}
