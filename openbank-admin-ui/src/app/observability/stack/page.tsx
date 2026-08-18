// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

import Link from 'next/link'
import {
  Database, ScrollText, Zap, Flame, Smartphone, BellRing, Target, ArrowRight,
  Globe, Bot,
} from 'lucide-react'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { PageHeader } from '@/components/ui/PageHeader'

// Static explainer — the LGTM(P) + GlitchTip correlation layer (ADR-0087), extended with
// SLO-as-code, on-call, durable S3 retention and mobile RUM (ADR-0088 / ADR-0089). No backend
// calls: this page documents how telemetry flows and links, so the whole team understands the
// single pane and the on-call chain. Bilingual per the admin-ui rules.

type Pillar = { icon: React.ReactNode; name: string; tag: string; store: string; color: string }

export default function ObservabilityStackPage() {
  const { t } = useLanguage()

  const pillars: Pillar[] = [
    {
      icon: <Database size={18} />, name: 'Prometheus', store: 'Prometheus',
      color: '#e6522c',
      tag: t('Metriky — rate/error/latence, business čítače, exempláry do trace.',
             'Metrics — rate/error/latency, business counters, exemplars into traces.'),
    },
    {
      icon: <ScrollText size={18} />, name: 'Loki', store: t('Loki 3.6 · S3', 'Loki 3.6 · S3'),
      color: '#f5a623',
      tag: t('Logy — strukturované JSON s trace_id; trvanlivé v S3, retence 7 dní (DORA).',
             'Logs — structured JSON with trace_id; durable in S3, 7-day retention (DORA).'),
    },
    {
      icon: <Zap size={18} />, name: 'Tempo', store: t('Tempo · S3', 'Tempo · S3'),
      color: '#6366f1',
      tag: t('Traces — celá cesta requestu; RED metriky + service mapa; trvanlivé v S3 (7 d).',
             'Traces — full request path; RED metrics + service map; durable in S3 (7 d).'),
    },
    {
      icon: <Flame size={18} />, name: 'Pyroscope', store: 'Pyroscope',
      color: '#059669',
      tag: t('Profily — CPU/alloc flame-graph; skok ze span do profilu.',
             'Profiles — CPU/alloc flame graph; jump from a span to its profile.'),
    },
    {
      icon: <Target size={18} />, name: 'Pyrra', store: t('Pyrra · SLO-as-code', 'Pyrra · SLO-as-code'),
      color: '#0ea5e9',
      tag: t('SLO jako kód — deklarativní cíle generují multi-window burn-rate pravidla + error budget.',
             'SLO as code — declarative objectives generate multi-window burn-rate rules + error budget.'),
    },
    {
      icon: <BellRing size={18} />, name: t('On-call', 'On-call'), store: t('GoAlert + ntfy', 'GoAlert + ntfy'),
      color: '#3b82f6',
      tag: t('Eskalace, ack a dedup nad alerty; rozvrhy on-call; page doručí self-hosted ntfy.',
             'Escalation, ack & dedup over alerts; on-call schedules; pages delivered by self-hosted ntfy.'),
    },
    {
      icon: <Smartphone size={18} />, name: t('Mobil', 'Mobile'), store: t('GlitchTip + OTel RUM', 'GlitchTip + OTel RUM'),
      color: '#ef4444',
      tag: t('Pády (GlitchTip, Sentry-protokol) + RUM výkon (OpenTelemetry) — sdílený trace_id, tap→ledger.',
             'Crashes (GlitchTip, Sentry protocol) + RUM performance (OpenTelemetry) — shared trace_id, tap→ledger.'),
    },
    {
      icon: <Globe size={18} />, name: t('Syntetika', 'Synthetics'), store: t('Blackbox + k6', 'Blackbox + k6'),
      color: '#0891b2',
      tag: t('Black-box proby veřejných endpointů (admin/customer/kc/api.open-bank.tech) + scriptované k6 cesty (p95 < 2 s) — dostupnost a SLA zvenčí, do Promethea.',
             'Black-box probes of public endpoints (admin/customer/kc/api.open-bank.tech) + scripted k6 journeys (p95 < 2 s) — availability & SLA from the outside, into Prometheus.'),
    },
    {
      icon: <Bot size={18} />, name: t('AI RCA', 'AI RCA'), store: t('HolmesGPT', 'HolmesGPT'),
      color: '#7c3aed',
      tag: t('Při alertu zkoumá metriky, logy, traces i stav k8s a navrhne pravděpodobnou příčinu — zkracuje MTTR.',
             'On an alert, investigates metrics, logs, traces and k8s state and proposes a likely root cause — shortens MTTR.'),
    },
  ]

  const links: { from: string; to: string; how: string }[] = [
    { from: 'Prometheus', to: 'Tempo', how: t('exemplar na metrice → konkrétní trace', 'exemplar on a metric → the exact trace') },
    { from: 'Tempo', to: 'Loki', how: t('span → jeho logy přes trace_id', 'span → its logs via trace_id') },
    { from: 'Loki', to: 'Tempo', how: t('log → trace (derived field na trace_id)', 'log → trace (derived field on trace_id)') },
    { from: 'Tempo', to: 'Prometheus', how: t('span → RED metriky (span-metrics)', 'span → RED metrics (span-metrics)') },
    { from: 'Tempo', to: 'Pyroscope', how: t('span → CPU/alloc flame-graph', 'span → CPU/alloc flame graph') },
    { from: 'GlitchTip', to: 'Tempo', how: t('crash → backend trace (correlationId)', 'crash → backend trace (correlationId)') },
    { from: 'Pyrra', to: 'Prometheus', how: t('SLO → burn-rate pravidla', 'SLO → burn-rate rules') },
    { from: t('Syntetika', 'Synthetics'), to: 'Prometheus', how: t('blackbox/k6 → dostupnost + latence zvenčí', 'blackbox/k6 → availability + latency from outside') },
    { from: 'Alertmanager', to: 'GoAlert', how: t('critical route → page on-call', 'critical route → page on-call') },
    { from: 'Alertmanager', to: 'HolmesGPT', how: t('alert → AI vyšetření příčiny', 'alert → AI root-cause investigation') },
    { from: 'GoAlert', to: 'ntfy', how: t('eskalace → doručení pagu', 'escalation → page delivery') },
  ]

  const flow: { n: string; title: string; detail: string }[] = [
    { n: '1', title: t('Request přijde', 'Request arrives'),
      detail: t('Edge přidělí trace_id + X-Correlation-ID a propaguje je (W3C TraceContext) do všech downstream volání.',
                'The edge assigns trace_id + X-Correlation-ID and propagates them (W3C TraceContext) to every downstream call.') },
    { n: '2', title: t('Služby zpracují', 'Services process'),
      detail: t('Party → SCA → payment → ledger. Quarkus auto-instrumentace tvoří spany; JSON log nese trace_id v mdc.',
                'Party → SCA → payment → ledger. Quarkus auto-instrumentation makes spans; the JSON log carries trace_id in mdc.') },
    { n: '3', title: t('Sběr a trvanlivost', 'Collection & durability'),
      detail: t('Alloy posílá logy do Loki, Tempo přijímá spany a generuje span-metriky do Promethea. Loki i Tempo ukládají do S3 (retence 7 dní).',
                'Alloy ships logs to Loki; Tempo ingests spans and generates span-metrics into Prometheus. Loki and Tempo persist to S3 (7-day retention).') },
    { n: '4', title: t('Jeden pohled v Grafaně', 'One pane in Grafana'),
      detail: t('Vidíš spike v error-rate → klik na exemplar → otevře se trace v Tempu → odtud na logy, metriky i flame-graph.',
                'See an error-rate spike → click the exemplar → the trace opens in Tempo → pivot to logs, metrics and the flame graph.') },
    { n: '5', title: t('SLO a on-call', 'SLO & on-call'),
      detail: t('Pyrra hlídá error budget; burn-rate alert → Alertmanager → GoAlert (eskalace, ack, dedup) → page přes ntfy — dřív než dojde budget.',
                'Pyrra watches the error budget; a burn-rate alert → Alertmanager → GoAlert (escalation, ack, dedup) → page via ntfy — before the budget is gone.') },
    { n: '6', title: t('Mobil: pády i výkon', 'Mobile: crashes & performance'),
      detail: t('Pád v appce (GlitchTip) se napojí přes correlationId; RUM spany (OpenTelemetry) sdílí trace_id a uzavírají trace od dotyku po ledger (ingest přes zpevněnou bránu, ADR-0089).',
                'An app crash (GlitchTip) links via correlationId; RUM spans (OpenTelemetry) share the trace_id and close the trace from tap to ledger (ingest via the hardened gateway, ADR-0089).') },
  ]

  return (
    <div style={{ maxWidth: '1200px', margin: '0 auto' }}>
      <PageHeader
        breadcrumb={<div className="breadcrumb"><span>OpenBank</span><span className="breadcrumb-sep">/</span><span className="breadcrumb-current">{t('Observabilita', 'Observability')}</span></div>}
        icon={<Globe size={20} aria-hidden="true" />}
        title={t('Jak funguje náš observability stack', 'How our observability stack works')}
        subtitle={t('Čtyři pilíře (metriky, logy, traces, profily) + mobilní pády se sbíhají do jednoho plátna v Grafaně; na to navazuje SLO-as-code (Pyrra), on-call s eskalací (GoAlert → ntfy), trvanlivé úložiště v S3, mobilní RUM, syntetické proby zvenčí (blackbox + k6) a AI root-cause analýza nad alerty (HolmesGPT). Páteří je trace_id a X-Correlation-ID — z každého bodu se proklikneš do souvisejícího kontextu. Vše čistě open-source, self-hosted, ve VPC.', 'Four pillars (metrics, logs, traces, profiles) + mobile crashes converge into one pane in Grafana; on top sit SLO-as-code (Pyrra), on-call with escalation (GoAlert → ntfy), durable S3 storage, mobile RUM, external synthetic probes (blackbox + k6) and AI root-cause analysis over alerts (HolmesGPT). The spine is trace_id and X-Correlation-ID — from any point you click through to the related context. All pure OSS, self-hosted, in-VPC.')}
      />

      {/* Pillar cards */}
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(240px, 1fr))', gap: '14px', marginBottom: '28px' }}>
        {pillars.map((p) => (
          <div key={p.name} className="card" style={{ padding: '16px' }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: '10px', marginBottom: '10px' }}>
              <div style={{ width: '34px', height: '34px', borderRadius: '8px', background: `${p.color}1a`, color: p.color, display: 'flex', alignItems: 'center', justifyContent: 'center', flexShrink: 0 }}>
                {p.icon}
              </div>
              <div>
                <div style={{ fontSize: '14px', fontWeight: 700 }}>{p.name}</div>
                <div style={{ fontSize: '11px', color: 'var(--text-secondary)' }}>{p.store}</div>
              </div>
            </div>
            <div style={{ fontSize: '12px', color: 'var(--text-secondary)', lineHeight: 1.5 }}>{p.tag}</div>
          </div>
        ))}
      </div>

      {/* Correlation diagram */}
      <div className="card" style={{ padding: '18px', marginBottom: '28px' }}>
        <h2 style={{ fontSize: '15px', fontWeight: 700, margin: '0 0 14px' }}>
          {t('Single pane + on-call — co se kam propojuje', 'Single pane + on-call — what links to what')}
        </h2>
        <svg viewBox="0 0 1000 590" style={{ width: '100%', height: 'auto', display: 'block' }} role="img"
             aria-label={t('Diagram toku telemetrie a on-call řetězce', 'Telemetry flow and on-call chain diagram')}>
          <defs>
            <marker id="ob-ah" markerWidth="9" markerHeight="9" refX="7" refY="3" orient="auto">
              <path d="M0,0 L7,3 L0,6" fill="none" stroke="var(--text-secondary)" strokeWidth="1.4" />
            </marker>
            <marker id="ob-at" markerWidth="9" markerHeight="9" refX="7" refY="3" orient="auto">
              <path d="M0,0 L7,3 L0,6" fill="none" stroke="#0d9488" strokeWidth="1.6" />
            </marker>
            <marker id="ob-aa" markerWidth="9" markerHeight="9" refX="7" refY="3" orient="auto">
              <path d="M0,0 L7,3 L0,6" fill="none" stroke="#ef6c00" strokeWidth="1.6" />
            </marker>
          </defs>

          {/* Sources */}
          <SvgBox x={90} y={30} w={320} h={62} title={t('Backend — Quarkus flotila', 'Backend — Quarkus fleet')}
                  sub={t('OTLP traces · metriky · JSON logy', 'OTLP traces · metrics · JSON logs')} accent="#6366f1" />
          <SvgBox x={590} y={30} w={320} h={62} title={t('Mobilní app (KMP)', 'Mobile app (KMP)')}
                  sub={t('Sentry-KMP crash · OTel RUM', 'Sentry-KMP crash · OTel RUM')} accent="#ef4444" />

          {/* Ingest */}
          <SvgBox x={90} y={150} w={320} h={62} title={t('OTel Collector / Alloy', 'OTel Collector / Alloy')}
                  sub={t('tail-sampling · PII redakce', 'tail-sampling · PII redaction')} accent="#6366f1" />
          <SvgBox x={590} y={150} w={155} h={62} title="GlitchTip"
                  sub={t('crash · in-cluster', 'crash · in-cluster')} accent="#ef4444" small />
          <SvgBox x={755} y={150} w={155} h={62} title={t('RUM brána', 'RUM gateway')}
                  sub={t('OIDC · ADR-0089', 'OIDC · ADR-0089')} accent="#ef4444" small dashed />

          {/* Stores */}
          <SvgBox x={70} y={270} w={195} h={58} title="Prometheus" sub={t('metriky + exempláry', 'metrics + exemplars')} accent="#e6522c" small />
          <SvgBox x={285} y={270} w={195} h={58} title="Loki 3.6" sub={t('logy + trace_id · S3', 'logs + trace_id · S3')} accent="#f5a623" small />
          <SvgBox x={500} y={270} w={195} h={58} title="Tempo" sub={t('traces + span-metrics · S3', 'traces + span-metrics · S3')} accent="#6366f1" small />
          <SvgBox x={715} y={270} w={195} h={58} title="Pyroscope" sub={t('profily', 'profiles')} accent="#059669" small />

          {/* Grafana */}
          <SvgBox x={90} y={380} w={820} h={58} title={t('Grafana — single pane', 'Grafana — single pane')}
                  sub={t('dashboardy · Explore · SLO (Pyrra)', 'dashboards · Explore · SLO (Pyrra)')} accent="#0d9488" filled />

          {/* On-call lane */}
          <SvgBox x={90} y={498} w={240} h={56} title="Alertmanager" sub={t('critical route', 'critical route')} accent="#ef6c00" small />
          <SvgBox x={380} y={498} w={240} h={56} title="GoAlert" sub={t('eskalace · ack · dedup', 'escalation · ack · dedup')} accent="#ef6c00" small />
          <SvgBox x={670} y={498} w={240} h={56} title="ntfy" sub={t('page on-call', 'page on-call')} accent="#ef6c00" small />

          {/* structural arrows */}
          <line x1={250} y1={92} x2={250} y2={148} stroke="var(--text-secondary)" strokeWidth={1.4} markerEnd="url(#ob-ah)" />
          <line x1={690} y1={92} x2={668} y2={148} stroke="var(--text-secondary)" strokeWidth={1.4} markerEnd="url(#ob-ah)" />
          <line x1={800} y1={92} x2={825} y2={148} stroke="var(--text-secondary)" strokeWidth={1.4} markerEnd="url(#ob-ah)" />
          {/* RUM gateway -> collector (telemetry joins backend) */}
          <line x1={832} y1={212} x2={420} y2={195} stroke="var(--text-secondary)" strokeWidth={1.4} strokeDasharray="4 3" markerEnd="url(#ob-ah)" />
          {/* collector fan-out */}
          {[167, 382, 597, 812].map((x, i) => (
            <line key={i} x1={250} y1={214} x2={x} y2={268} stroke="var(--text-secondary)" strokeWidth={1.4} markerEnd="url(#ob-ah)" />
          ))}
          {/* stores -> grafana (teal = correlated) */}
          {[167, 382, 597, 812].map((x, i) => (
            <line key={i} x1={x} y1={328} x2={x} y2={378} stroke="#0d9488" strokeWidth={1.6} strokeDasharray="5 3" markerEnd="url(#ob-at)" />
          ))}
          {/* glitchtip -> grafana (teal L) */}
          <path d="M667,212 L667,238 L958,238 L958,360 L820,360 L820,378" fill="none" stroke="#0d9488" strokeWidth={1.6} strokeDasharray="5 3" markerEnd="url(#ob-at)" />
          {/* on-call path (amber): Prometheus -> Alertmanager -> GoAlert -> ntfy, routed down the left margin to avoid Grafana */}
          <path d="M150,328 L40,328 L40,526 L88,526" fill="none" stroke="#ef6c00" strokeWidth={1.6} markerEnd="url(#ob-aa)" />
          <line x1={330} y1={526} x2={378} y2={526} stroke="#ef6c00" strokeWidth={1.6} markerEnd="url(#ob-aa)" />
          <line x1={620} y1={526} x2={668} y2={526} stroke="#ef6c00" strokeWidth={1.6} markerEnd="url(#ob-aa)" />

          {/* legend */}
          <line x1={90} y1={578} x2={116} y2={578} stroke="#0d9488" strokeWidth={1.6} strokeDasharray="5 3" />
          <text x={124} y={582} fontSize={12} fill="var(--text-secondary)">
            {t('Teal = klikací korelace (trace_id + X-Correlation-ID)', 'Teal = click-through correlation (trace_id + X-Correlation-ID)')}
          </text>
          <line x1={560} y1={578} x2={586} y2={578} stroke="#ef6c00" strokeWidth={1.6} />
          <text x={594} y={582} fontSize={12} fill="var(--text-secondary)">
            {t('Oranžová = alert → on-call (GoAlert → ntfy)', 'Amber = alert → on-call (GoAlert → ntfy)')}
          </text>
        </svg>
      </div>

      {/* Correlation links + flow */}
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(320px, 1fr))', gap: '20px' }}>
        <div className="card" style={{ padding: '18px' }}>
          <h2 style={{ fontSize: '15px', fontWeight: 700, margin: '0 0 12px' }}>
            {t('Propojení — korelace i on-call', 'Links — correlation & on-call')}
          </h2>
          <div style={{ display: 'flex', flexDirection: 'column', gap: '8px' }}>
            {links.map((l, i) => (
              <div key={i} style={{ display: 'flex', alignItems: 'center', gap: '8px', fontSize: '12px', padding: '8px 10px', borderRadius: 'var(--r-md, 8px)', background: 'var(--surface-2)' }}>
                <span style={{ fontWeight: 700, minWidth: '92px' }}>{l.from}</span>
                <ArrowRight size={13} style={{ color: 'var(--text-secondary)', flexShrink: 0 }} />
                <span style={{ fontWeight: 700, minWidth: '84px' }}>{l.to}</span>
                <span style={{ color: 'var(--text-secondary)' }}>{l.how}</span>
              </div>
            ))}
          </div>
        </div>

        <div className="card" style={{ padding: '18px' }}>
          <h2 style={{ fontSize: '15px', fontWeight: 700, margin: '0 0 12px' }}>
            {t('Typický průběh: platba', 'Typical flow: a payment')}
          </h2>
          <div style={{ display: 'flex', flexDirection: 'column', gap: '14px' }}>
            {flow.map((s) => (
              <div key={s.n} style={{ display: 'flex', gap: '12px', alignItems: 'flex-start' }}>
                <div style={{ width: '24px', height: '24px', borderRadius: '50%', background: 'var(--accent, #6366f1)', color: '#fff', fontSize: '12px', fontWeight: 700, display: 'flex', alignItems: 'center', justifyContent: 'center', flexShrink: 0 }}>{s.n}</div>
                <div>
                  <div style={{ fontSize: '13px', fontWeight: 700 }}>{s.title}</div>
                  <div style={{ fontSize: '12px', color: 'var(--text-secondary)', marginTop: '2px', lineHeight: 1.5 }}>{s.detail}</div>
                </div>
              </div>
            ))}
          </div>
        </div>
      </div>

      {/* Back link */}
      <div style={{ marginTop: '24px' }}>
        <Link href="/observability" style={{ fontSize: '13px', color: 'var(--accent, #6366f1)', fontWeight: 600, textDecoration: 'none' }}>
          {t('← Zpět na metriky', '← Back to metrics')}
        </Link>
      </div>
    </div>
  )
}

function SvgBox({ x, y, w, h, title, sub, accent, small, filled, dashed }: {
  x: number; y: number; w: number; h: number; title: string; sub: string; accent: string; small?: boolean; filled?: boolean; dashed?: boolean
}) {
  return (
    <g>
      <rect x={x} y={y} width={w} height={h} rx={8}
            fill={filled ? `${accent}1a` : 'var(--surface)'} stroke={accent} strokeWidth={filled ? 2 : 1.4}
            strokeDasharray={dashed ? '5 3' : undefined} />
      <text x={x + w / 2} y={y + (small ? 24 : 26)} textAnchor="middle" fontSize={small ? 13 : 14} fontWeight={700} fill="var(--text-primary)">{title}</text>
      <text x={x + w / 2} y={y + (small ? 42 : 46)} textAnchor="middle" fontSize={11} fill="var(--text-secondary)">{sub}</text>
    </g>
  )
}
