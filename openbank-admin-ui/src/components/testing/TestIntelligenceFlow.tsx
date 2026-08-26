// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

'use client'

import { useMemo, useState } from 'react'
import {
  Activity, Bot, Boxes, BrainCircuit, CheckCircle2, FlaskConical,
  GitPullRequest, Gauge, Radar, Route, ShieldCheck, Sparkles, Users,
} from 'lucide-react'
import type { LucideIcon } from 'lucide-react'
import type { TestIntelligenceReport } from '@/lib/types/test-intelligence'
import { useLanguage } from '@/lib/i18n/LanguageContext'

type StageId = 'change' | 'prove' | 'runtime' | 'challenge' | 'observe' | 'reason' | 'decide'

type Stage = {
  id: StageId
  eyebrow: string
  title: string
  short: string
  proves: string
  doesNotProve: string
  icon: LucideIcon
  tone: string
}

export function TestIntelligenceFlow({ report }: { report?: TestIntelligenceReport | null }) {
  const { t } = useLanguage()
  const [selected, setSelected] = useState<StageId>('prove')

  const stages: Stage[] = useMemo(() => [
    {
      id: 'change', eyebrow: t('01 · ZMĚNA', '01 · CHANGE'), title: t('Záměr vstupuje', 'Intent enters'),
      short: t('Commit, vlastník a path scope určují CI plán.', 'Commit, ownership and path scope shape the CI plan.'),
      proves: t('Známe přesný zdroj, SHA, službu a peněžní dopad.', 'We know the exact source, SHA, service and money-path impact.'),
      doesNotProve: t('Path-scoped CI není per-test impact analýza; predikce zatím nesmí vybírat povinný gate.', 'Path-scoped CI is not per-test impact analysis; a prediction must not select a required gate yet.'),
      icon: GitPullRequest, tone: '#38bdf8',
    },
    {
      id: 'prove', eyebrow: t('02 · DŮKAZ', '02 · PROVE'), title: t('CI rozbíjí domněnky', 'CI breaks assumptions'),
      short: 'JUnit · Kover · Pact · TraceContract · mutation · Playwright',
      proves: t('Deterministické kontroly proběhly na konkrétním commitu.', 'Deterministic controls ran against one concrete commit.'),
      doesNotProve: t('Zelený mock neprokazuje skutečnou infrastrukturu ani provoz.', 'A green mock proves neither real infrastructure nor production behaviour.'),
      icon: FlaskConical, tone: '#22c55e',
    },
    {
      id: 'runtime', eyebrow: t('03 · REALITA', '03 · REALITY'), title: 'Testcontainers',
      short: 'PostgreSQL · Kafka · Keycloak · WireMock',
      proves: t('Závislosti skutečně startovaly a byly po pokusu ukončeny.', 'Dependencies really started and were stopped after the attempt.'),
      doesNotProve: t('Deklarace knihovny bez lifecycle evidence není spuštěný integrační test.', 'A library declaration without lifecycle evidence is not an executed integration test.'),
      icon: Boxes, tone: '#a78bfa',
    },
    {
      id: 'challenge', eyebrow: t('04 · ZÁTĚŽ', '04 · CHALLENGE'), title: t('Systém pod tlakem', 'System under pressure'),
      short: 'k6 · deterministic simulation · sandbox journeys',
      proves: t('Spuštěné prahy výkonu a aktivní cesty dávají důkaz jen pro konkrétní cílové prostředí.', 'Executed performance thresholds and active journeys provide evidence only for their concrete target environment.'),
      doesNotProve: t('Naplánovaný scénář bez běhu není pokrytí.', 'A planned journey without a run is not coverage.'),
      icon: Gauge, tone: '#fb923c',
    },
    {
      id: 'observe', eyebrow: t('05 · PROVOZ', '05 · OBSERVE'), title: t('Skutečná zkušenost', 'Real experience'),
      short: t('Syntetika · traces · mobilní RUM', 'Synthetics · traces · mobile RUM'),
      proves: t('Telemetrie dorazila z runtime; korelace s backendovou cestou vyžaduje konkrétní trace důkaz.', 'Runtime telemetry arrived; correlation to a backend journey requires concrete trace evidence.'),
      doesNotProve: t('Opt-in RUM není test verdict ani reprezentativní počet uživatelů.', 'Opt-in RUM is neither a test verdict nor a representative user count.'),
      icon: Radar, tone: '#2dd4bf',
    },
    {
      id: 'reason', eyebrow: t('06 · INTELIGENCE', '06 · INTELLIGENCE'), title: t('AI hledá souvislosti', 'AI finds relationships'),
      short: t('Flaky clustering · dopad · RCA · návrh', 'Flake clustering · impact · RCA · proposal'),
      proves: t('Agent vysvětlí rozpory a seřadí ověřitelné hypotézy.', 'An agent explains contradictions and ranks falsifiable hypotheses.'),
      doesNotProve: t('AI nesmí změnit měření, přeskočit gate ani schválit vlastní opravu.', 'AI cannot rewrite evidence, skip a gate or approve its own remediation.'),
      icon: BrainCircuit, tone: '#f472b6',
    },
    {
      id: 'decide', eyebrow: t('07 · ODPOVĚDNOST', '07 · ACCOUNTABILITY'), title: t('Člověk rozhoduje', 'Humans decide'),
      short: t('Policy · review · audit · release', 'Policy · review · audit · release'),
      proves: t('Rozhodnutí má vlastníka, pravidla a auditní stopu.', 'The decision has an owner, policy and audit trail.'),
      doesNotProve: t('Souhrnné skóre nikdy nesmí zprůměrovat chybějící důkaz.', 'An aggregate score must never average missing evidence away.'),
      icon: Users, tone: '#60a5fa',
    },
  ], [t])

  const current = stages.find(stage => stage.id === selected) ?? stages[1]
  const evidenced = report?.totals.componentsWithExecutionEvidence ?? 0
  const total = report?.totals.components ?? 0
  // `unknown`, `not-run` and `blocked` are unresolved evidence, never an implicit green.
  // The collector/runtime route keeps this total disjoint from failures, missing components and
  // stale observations, so the hero can expose every state that needs an operator's attention.
  const attention = (report?.totals.failingEvidence ?? 0) + (report?.totals.missingEvidence ?? 0)
    + (report?.totals.staleEvidence ?? 0) + (report?.totals.unresolvedEvidence ?? report?.totals.unknownEvidence ?? 0)
  const activeJourneys = report?.syntheticJourneys.filter(item => item.status === 'active').length ?? 0
  const runtimeProofs = report?.components.reduce((sum, component) => sum + component.testInfrastructure.observed.filter(event => event.lifecycle === 'started').length, 0) ?? 0
  const traceProofs = report?.components.filter(component => component.evidence.some(evidence => evidence.kind === 'trace' && evidence.state === 'passed')).length ?? 0
  const mobile = report?.clientExperiences?.find(client => client.id === 'openbank-app')
  const rumState = mobile?.rum.state ?? 'unknown'

  return <section className="ti-system" aria-labelledby="test-intelligence-flow-title">
    <div className="ti-aurora" aria-hidden="true" />
    <header className="ti-hero">
      <div>
        <span className="ti-kicker"><Sparkles size={12} />{t('ŽIVÁ ARCHITEKTURA KVALITY', 'LIVE QUALITY ARCHITECTURE')}</span>
        <h2 id="test-intelligence-flow-title">{t('Od změny k důvěře. Každý krok zanechá důkaz.', 'From change to confidence. Every step leaves evidence.')}</h2>
        <p>{t('Klikni na vrstvu a uvidíš nejen co dělá, ale hlavně co její zelená barva nikdy nesmí tvrdit.', 'Select a layer to see not only what it does, but what its green state must never claim.')}</p>
      </div>
      <div className={`ti-health ${attention ? 'attention' : ''}`}><i />
        <span>{report ? (attention ? t('VYŽADUJE POZORNOST', 'NEEDS ATTENTION') : t('DŮKAZY ZDRAVÉ', 'EVIDENCE HEALTHY')) : t('ČEKÁM NA DATA', 'AWAITING DATA')}</span>
        <strong>{report ? `${attention}` : '—'}</strong><small>{t('signálů k prověření', 'signals to inspect')}</small>
      </div>
    </header>

    <div className="ti-rail" role="group" aria-label={t('Sedm vrstev Test Intelligence', 'Seven Test Intelligence layers')}>
      <div className="ti-beam" aria-hidden="true"><i /></div>
      {stages.map((stage, index) => {
        const Icon = stage.icon
        const active = selected === stage.id
        return <button key={stage.id} type="button" aria-pressed={active} className={`ti-stage ${active ? 'active' : ''}`} style={{ '--stage': stage.tone, '--delay': `${index * 110}ms` } as React.CSSProperties} onClick={() => setSelected(stage.id)}>
          <span className="ti-stage-icon"><Icon size={19} /></span>
          <span className="ti-stage-copy"><small>{stage.eyebrow}</small><strong>{stage.title}</strong><em>{stage.short}</em></span>
          <span className="ti-stage-index">0{index + 1}</span>
        </button>
      })}
    </div>

    <div className="ti-explain" aria-live="polite">
      <div className="ti-explain-title" style={{ '--stage': current.tone } as React.CSSProperties}><current.icon size={22} /><div><small>{current.eyebrow}</small><strong>{current.title}</strong></div></div>
      <div className="ti-proof"><CheckCircle2 size={16} /><div><b>{t('Co tato vrstva prokazuje', 'What this layer proves')}</b><span>{current.proves}</span></div></div>
      <div className="ti-boundary"><ShieldCheck size={16} /><div><b>{t('Hranice důkazu', 'Evidence boundary')}</b><span>{current.doesNotProve}</span></div></div>
    </div>

    <div className="ti-signals" aria-label={t('Živé signály architektury', 'Live architecture signals')}>
      <div><FlaskConical size={15} /><span>{t('Fleet evidence', 'Fleet evidence')}</span><strong>{report ? `${evidenced}/${total}` : '—'}</strong></div>
      <div><Boxes size={15} /><span>{t('Starty runtime', 'Runtime starts')}</span><strong>{report ? runtimeProofs : '—'}</strong></div>
      <div><Route size={15} /><span>{t('Trace kontrakty', 'Trace contracts')}</span><strong>{report ? traceProofs : '—'}</strong></div>
      <div><Radar size={15} /><span>{t('Aktivní syntetika', 'Active synthetics')}</span><strong>{report ? activeJourneys : '—'}</strong></div>
      <div><Activity size={15} /><span>{t('Mobilní RUM', 'Mobile RUM')}</span><strong className={`state-${rumState}`}>{rumState}</strong></div>
      <div><Bot size={15} /><span>{t('Režim AI', 'AI mode')}</span><strong>HITL</strong></div>
    </div>

    <style jsx>{`
      .ti-system{--ink:#dceeff;position:relative;isolation:isolate;overflow:hidden;margin-bottom:24px;padding:24px;border:1px solid color-mix(in srgb,var(--accent) 34%,var(--border));border-radius:20px;background:linear-gradient(145deg,#07111e 0%,#0b1626 52%,#101426 100%);box-shadow:0 24px 70px rgba(2,8,23,.32);color:var(--ink)}
      .ti-system:before{content:"";position:absolute;inset:0;z-index:-1;opacity:.3;background-image:linear-gradient(rgba(125,211,252,.08) 1px,transparent 1px),linear-gradient(90deg,rgba(125,211,252,.08) 1px,transparent 1px);background-size:32px 32px;mask-image:linear-gradient(to bottom,#000,transparent 82%)}
      .ti-aurora{position:absolute;z-index:-1;width:520px;height:260px;left:35%;top:-180px;border-radius:50%;background:radial-gradient(circle,rgba(56,189,248,.3),rgba(168,85,247,.12) 42%,transparent 70%);filter:blur(20px);animation:aurora 9s ease-in-out infinite alternate}
      .ti-hero{display:grid;grid-template-columns:minmax(0,1fr) auto;gap:28px;align-items:start}.ti-kicker{display:inline-flex;align-items:center;gap:7px;font-size:10px;font-weight:800;letter-spacing:.18em;color:#7dd3fc}.ti-hero h2{max-width:820px;margin:8px 0 5px;font-size:clamp(20px,2.3vw,34px);line-height:1.08;letter-spacing:-.035em}.ti-hero p{max-width:760px;margin:0;color:#91a4ba;font-size:12px;line-height:1.55}
      .ti-health{display:grid;grid-template-columns:auto auto;column-gap:10px;align-items:center;min-width:155px;padding:11px 13px;border:1px solid rgba(34,197,94,.35);border-radius:13px;background:rgba(34,197,94,.07)}.ti-health i{grid-row:1/3;width:9px;height:9px;border-radius:50%;background:#22c55e;box-shadow:0 0 16px #22c55e;animation:breathe 1.7s ease-in-out infinite}.ti-health span{font-size:8px;font-weight:800;letter-spacing:.13em;color:#86efac}.ti-health strong{font-size:22px;line-height:1}.ti-health small{grid-column:2;color:#71849a;font-size:8px}.ti-health.attention{border-color:rgba(251,146,60,.42);background:rgba(251,146,60,.08)}.ti-health.attention i{background:#fb923c;box-shadow:0 0 16px #fb923c}.ti-health.attention span{color:#fdba74}
      .ti-rail{position:relative;display:grid;grid-template-columns:repeat(7,minmax(110px,1fr));gap:8px;margin:28px 0 14px}.ti-beam{position:absolute;left:5%;right:5%;top:27px;height:2px;overflow:hidden;background:linear-gradient(90deg,transparent,rgba(125,211,252,.28) 8%,rgba(167,139,250,.38) 50%,rgba(244,114,182,.3) 80%,transparent)}.ti-beam i{position:absolute;width:15%;height:100%;background:linear-gradient(90deg,transparent,#fff,transparent);filter:drop-shadow(0 0 7px #38bdf8);animation:travel 3.8s linear infinite}
      .ti-stage{--stage:#38bdf8;position:relative;display:flex;min-width:0;min-height:142px;flex-direction:column;align-items:flex-start;gap:10px;padding:13px 11px;text-align:left;border:1px solid rgba(148,163,184,.16);border-radius:14px;background:linear-gradient(180deg,rgba(15,29,47,.92),rgba(10,20,35,.9));color:inherit;cursor:pointer;transition:transform .2s,border-color .2s,background .2s;animation:stage-in .45s both;animation-delay:var(--delay)}.ti-stage:hover,.ti-stage.active{transform:translateY(-4px);border-color:color-mix(in srgb,var(--stage) 70%,transparent);background:linear-gradient(180deg,color-mix(in srgb,var(--stage) 14%,#0f1d2f),#0a1423);box-shadow:0 12px 34px color-mix(in srgb,var(--stage) 12%,transparent)}.ti-stage:focus-visible{outline:2px solid var(--stage);outline-offset:2px}.ti-stage-icon{position:relative;z-index:1;display:grid;width:38px;height:38px;place-items:center;border:1px solid color-mix(in srgb,var(--stage) 48%,transparent);border-radius:12px;background:color-mix(in srgb,var(--stage) 12%,#0b1626);color:var(--stage);box-shadow:0 0 20px color-mix(in srgb,var(--stage) 12%,transparent)}.ti-stage-copy{display:grid;gap:4px;min-width:0}.ti-stage-copy small{font-size:7px;font-weight:800;letter-spacing:.12em;color:var(--stage)}.ti-stage-copy strong{font-size:12px;line-height:1.2}.ti-stage-copy em{font-size:8px;line-height:1.35;color:#71849a;font-style:normal}.ti-stage-index{position:absolute;right:8px;top:7px;font:700 9px/1 monospace;color:rgba(148,163,184,.25)}
      .ti-explain{display:grid;grid-template-columns:210px 1fr 1fr;gap:1px;overflow:hidden;border:1px solid rgba(148,163,184,.16);border-radius:14px;background:rgba(148,163,184,.14)}.ti-explain>div{min-height:84px;padding:15px;background:#0b1626}.ti-explain-title{--stage:#38bdf8;display:flex;align-items:center;gap:11px;color:var(--stage)}.ti-explain-title div{display:grid;gap:4px}.ti-explain-title small{font-size:7px;font-weight:800;letter-spacing:.13em}.ti-explain-title strong{font-size:15px;color:var(--ink)}.ti-proof,.ti-boundary{display:flex;gap:10px;color:#22c55e}.ti-boundary{color:#fbbf24}.ti-proof div,.ti-boundary div{display:grid;align-content:center;gap:5px}.ti-proof b,.ti-boundary b{font-size:9px;letter-spacing:.08em}.ti-proof span,.ti-boundary span{font-size:10px;line-height:1.45;color:#91a4ba}
      .ti-signals{display:grid;grid-template-columns:repeat(6,1fr);gap:8px;margin-top:10px}.ti-signals>div{display:grid;grid-template-columns:auto 1fr;gap:2px 7px;align-items:center;padding:9px 11px;border-radius:10px;background:rgba(148,163,184,.07);color:#7dd3fc}.ti-signals span{font-size:8px;text-transform:uppercase;letter-spacing:.08em;color:#71849a}.ti-signals strong{grid-column:2;font-size:12px;color:var(--ink)}.ti-signals .state-passed{color:#4ade80}.ti-signals .state-failed{color:#f87171}.ti-signals .state-not-run,.ti-signals .state-unknown{color:#94a3b8}
      @keyframes travel{from{left:-15%}to{left:100%}}@keyframes breathe{50%{opacity:.35;transform:scale(.72)}}@keyframes aurora{to{transform:translateX(120px) scale(1.12)}}@keyframes stage-in{from{opacity:0;transform:translateY(10px)}to{opacity:1;transform:none}}
      @media(max-width:1050px){.ti-rail{grid-template-columns:repeat(4,1fr)}.ti-beam{display:none}.ti-explain{grid-template-columns:1fr 1fr}.ti-explain-title{grid-column:1/-1}.ti-signals{grid-template-columns:repeat(3,1fr)}}
      @media(max-width:700px){.ti-system{padding:16px}.ti-hero{grid-template-columns:1fr}.ti-health{display:none}.ti-rail{display:flex;overflow-x:auto;scroll-snap-type:x mandatory;padding-bottom:5px}.ti-stage{min-width:160px;scroll-snap-align:start}.ti-explain{grid-template-columns:1fr}.ti-explain-title{grid-column:auto}.ti-signals{grid-template-columns:1fr 1fr}}
      @media(prefers-reduced-motion:reduce){.ti-aurora,.ti-beam i,.ti-health i,.ti-stage{animation:none}.ti-stage{transition:none}}
    `}</style>
  </section>
}
