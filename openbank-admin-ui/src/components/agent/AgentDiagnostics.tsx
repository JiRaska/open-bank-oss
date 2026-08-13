// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import Link from 'next/link'
import {
  Activity, ArrowRight, BriefcaseBusiness, Cpu, Database, GitMerge,
  Hand, Network, ShieldCheck, Sparkles, Users, Wrench,
} from 'lucide-react'
import { AgentPortrait, getAgentPersona } from './AgentIdentity'
import type { AgentDiagnostic, AgentDiagnosticKey, AgentMeshSummary } from '@/lib/governance/agentDiagnostics'
import styles from './AgentDiagnostics.module.css'

type Language = 'cs' | 'en'

const ICONS = {
  data: Database,
  tools: Wrench,
  guardrails: ShieldCheck,
  domain: BriefcaseBusiness,
  tokens: Cpu,
  cadence: Activity,
} satisfies Record<AgentDiagnosticKey, typeof Database>

const COPY: Record<AgentDiagnosticKey, {
  cs: { anatomy: string; label: string; explanation: string; unit: string }
  en: { anatomy: string; label: string; explanation: string; unit: string }
}> = {
  data: {
    cs: { anatomy: 'Oči', label: 'Datový rozhled', explanation: 'Kolik datových oblastí smí podle charteru číst.', unit: 'oblastí' },
    en: { anatomy: 'Eyes', label: 'Data horizon', explanation: 'How many data areas the charter allows it to read.', unit: 'areas' },
  },
  tools: {
    cs: { anatomy: 'Ruce', label: 'Akční dosah', explanation: 'Počet explicitně povolených nástrojů a operací.', unit: 'nástrojů' },
    en: { anatomy: 'Hands', label: 'Action reach', explanation: 'Explicitly allowed tools and operations.', unit: 'tools' },
  },
  guardrails: {
    cs: { anatomy: 'Štít', label: 'Síla brzd', explanation: 'Zakázané operace plus okamžiky povinného lidského zásahu.', unit: 'brzd' },
    en: { anatomy: 'Shield', label: 'Guardrail strength', explanation: 'Denied operations plus mandatory human gates.', unit: 'controls' },
  },
  domain: {
    cs: { anatomy: 'Výbava', label: 'Doménová výbava', explanation: 'Vlastněné služby a explicitně povolené engineering skilly.', unit: 'položek' },
    en: { anatomy: 'Gear', label: 'Domain kit', explanation: 'Owned services and explicitly allowed engineering skills.', unit: 'items' },
  },
  tokens: {
    cs: { anatomy: 'Jádro', label: 'Výpočetní výdrž', explanation: 'Maximální tokenový rozpočet jednoho běhu.', unit: 'tokenů/běh' },
    en: { anatomy: 'Core', label: 'Compute endurance', explanation: 'Maximum token budget for one run.', unit: 'tokens/run' },
  },
  cadence: {
    cs: { anatomy: 'Motor', label: 'Provozní tempo', explanation: 'Nejvyšší deklarovaný počet běhů za den.', unit: 'běhů/den' },
    en: { anatomy: 'Engine', label: 'Operating tempo', explanation: 'Highest declared number of runs per day.', unit: 'runs/day' },
  },
}

function compact(value: number, language: Language): string {
  return new Intl.NumberFormat(language === 'cs' ? 'cs-CZ' : 'en-US', {
    notation: value >= 10_000 ? 'compact' : 'standard',
    maximumFractionDigits: 1,
  }).format(value)
}

export function AgentBodyAnalysis({
  agentId,
  diagnostics,
  language,
}: {
  agentId: string
  diagnostics: AgentDiagnostic[]
  language: Language
}) {
  const persona = getAgentPersona(agentId, language)
  const t = (cs: string, en: string) => language === 'cs' ? cs : en

  return (
    <section className={styles.analysis} aria-labelledby="agent-body-analysis-title">
      <div className={styles.sectionHeading}>
        <div>
          <span className={styles.eyebrow}><Sparkles size={13} /> {t('Diagnostický sken', 'Diagnostic scan')}</span>
          <h2 id="agent-body-analysis-title">{t('Analýza těla agenta', 'Agent body analysis')}</h2>
          <p>{t(
            'Barvy ukazují velikost deklarovaného provozního prostoru vůči nejsilnější hodnotě v dnešní flotile.',
            'Bars show the declared operating envelope relative to the largest value in today’s fleet.',
          )}</p>
        </div>
        <span className={styles.notScore}>{t('Není to skóre inteligence ani kvality', 'Not an intelligence or quality score')}</span>
      </div>

      <div className={styles.analysisGrid}>
        <div className={styles.scanStage} aria-hidden="true">
          <span className={styles.scanRing} />
          <span className={styles.scanLine} />
          <div className={styles.scanPortrait}><AgentPortrait agentId={agentId} /></div>
          <span className={`${styles.anatomyTag} ${styles.tagEyes}`}>{COPY.data[language].anatomy}</span>
          <span className={`${styles.anatomyTag} ${styles.tagHands}`}>{COPY.tools[language].anatomy}</span>
          <span className={`${styles.anatomyTag} ${styles.tagCore}`}>{COPY.tokens[language].anatomy}</span>
          <span className={`${styles.anatomyTag} ${styles.tagShield}`}>{COPY.guardrails[language].anatomy}</span>
          <strong className={styles.scanName}>{persona.name}</strong>
          <span className={styles.scanRole}>{persona.role}</span>
        </div>

        <div className={styles.metrics}>
          {diagnostics.map(diagnostic => {
            const copy = COPY[diagnostic.key][language]
            const Icon = ICONS[diagnostic.key]
            return (
              <div className={styles.metric} key={diagnostic.key}>
                <div className={styles.metricTopline}>
                  <span className={styles.metricIcon} data-tone={diagnostic.key}><Icon size={14} /></span>
                  <span className={styles.metricName}>{copy.label}</span>
                  <strong>{compact(diagnostic.value, language)} {copy.unit}</strong>
                </div>
                <div className={styles.meterTrack} role="progressbar"
                  aria-label={`${copy.label}: ${diagnostic.percent}%`}
                  aria-valuenow={diagnostic.percent} aria-valuemin={0} aria-valuemax={100}>
                  <span className={styles.meterFill} data-tone={diagnostic.key}
                    style={{ width: `${diagnostic.percent}%` }} />
                </div>
                <div className={styles.metricFoot}>
                  <span>{copy.explanation}</span>
                  <span>{t('Maximum flotily', 'Fleet maximum')}: {compact(diagnostic.fleetMax, language)}</span>
                </div>
              </div>
            )
          })}
        </div>
      </div>
    </section>
  )
}

type MeshStatus = 'governed' | 'planned' | 'human'

function MeshNode({ icon: Icon, title, detail, status, language }: {
  icon: typeof Network
  title: string
  detail: string
  status: MeshStatus
  language: Language
}) {
  const statusLabel = {
    governed: language === 'cs' ? 'v charteru' : 'chartered',
    planned: language === 'cs' ? 'plánováno' : 'planned',
    human: language === 'cs' ? 'lidská brána' : 'human gate',
  }[status]
  return (
    <div className={styles.meshNode} data-status={status}>
      <span className={styles.meshIcon}><Icon size={18} /></span>
      <span className={styles.meshStatus}>{statusLabel}</span>
      <strong>{title}</strong>
      <span>{detail}</span>
    </div>
  )
}

export function AgentMeshMap({ agentId, mesh, language }: {
  agentId: string
  mesh: AgentMeshSummary
  language: Language
}) {
  const t = (cs: string, en: string) => language === 'cs' ? cs : en
  const selected = getAgentPersona(agentId, language)
  const coordinator = mesh.coordinatorId ? getAgentPersona(mesh.coordinatorId, language) : null
  const canOpen = mesh.selectedCapabilities.includes('case.open')
  const participants = mesh.enabledParticipantIds.length

  return (
    <section className={styles.mesh} aria-labelledby="agent-mesh-title">
      <div className={styles.sectionHeading}>
        <div>
          <span className={styles.eyebrow}><Network size={13} /> {t('Spolupráce agentů', 'Agent collaboration')}</span>
          <h2 id="agent-mesh-title">{t('AI mesh — jak má tým spolupracovat', 'AI mesh — how the team is designed to collaborate')}</h2>
          <p>{t(
            'Jeden dlouho běžící Temporal case drží kontext, rozpočet i stop podmínku. Agenti přidávají důkazy, koordinátor skládá jeden návrh a člověk rozhoduje.',
            'One durable Temporal case holds context, budget and stop conditions. Agents add evidence, the coordinator produces one proposal, and a human decides.',
          )}</p>
        </div>
        <span className={mesh.state === 'connected' ? styles.meshLive : styles.meshFoundation}>
          {mesh.state === 'connected'
            ? t(`${participants} připojených specialistů`, `${participants} connected specialists`)
            : t('Dnes: základ mesh', 'Today: mesh foundation')}
        </span>
      </div>

      <div className={styles.meshFlow} role="img" aria-label={t(
        'Tok od podnětu přes koordinátora a specialisty k návrhu a lidskému rozhodnutí',
        'Flow from signal through coordinator and specialists to proposal and human decision',
      )}>
        <MeshNode icon={Sparkles} title={t('Podnět nebo nález', 'Signal or finding')}
          detail={canOpen ? `${selected.name} · case.open` : t(`${selected.name} zatím nemá case.open`, `${selected.name} does not yet hold case.open`)}
          status={canOpen ? 'governed' : 'planned'} language={language} />
        <ArrowRight className={styles.meshArrow} aria-hidden="true" />
        <MeshNode icon={Network} title={coordinator?.name ?? t('Koordinátor', 'Coordinator')}
          detail={t('Hlídá rozpočet, deadline a konvergenci', 'Owns budget, deadline and convergence')}
          status={mesh.coordinatorId ? 'governed' : 'planned'} language={language} />
        <ArrowRight className={styles.meshArrow} aria-hidden="true" />
        <MeshNode icon={Users} title={t('Pozvaní specialisté', 'Invited specialists')}
          detail={participants > 0
            ? t(`${participants} agentů smí join/contribute`, `${participants} agents may join/contribute`)
            : t(`0 z ${mesh.totalAgents}: capability zatím nepřidělena`, `0 of ${mesh.totalAgents}: capability not granted yet`)}
          status={participants > 0 ? 'governed' : 'planned'} language={language} />
        <ArrowRight className={styles.meshArrow} aria-hidden="true" />
        <MeshNode icon={GitMerge} title={t('Jeden společný návrh', 'One shared proposal')}
          detail={t('Citace důkazů, dissent zůstává viditelný', 'Evidence is cited and dissent stays visible')}
          status={mesh.synthesisEnabled ? 'governed' : 'planned'} language={language} />
        <ArrowRight className={styles.meshArrow} aria-hidden="true" />
        <MeshNode icon={Hand} title={t('Člověk rozhodne', 'Human decides')}
          detail={t('Mesh nikdy nezapisuje přímo do business služby', 'The mesh never writes directly to a business service')}
          status={mesh.humanGateEnabled ? 'human' : 'planned'} language={language} />
      </div>

      <div className={styles.meshFooter}>
        <div className={styles.meshTruth}>
          <ShieldCheck size={16} />
          <span>{participants === 0
            ? t(
                'Pravdivý stav: koordinace a syntéza jsou charterované, ale multi-agentní mesh se aktivuje až po přidání case.join / case.contribute konkrétním specialistům.',
                'Honest status: coordination and synthesis are chartered, but the multi-agent mesh activates only after specialists receive case.join / case.contribute.',
              )
            : t('Připojení specialisté jsou odvozeni přímo z case capabilities v agents.yaml.', 'Connected specialists are derived directly from case capabilities in agents.yaml.')}</span>
        </div>
        <div className={styles.caseClasses}>
          <span>{t('Typy případů', 'Case classes')}</span>
          {mesh.caseClasses.map(caseClass => <code key={caseClass}>{caseClass}</code>)}
        </div>
        <Link href="/iaops/cases" className={styles.meshLink}>
          {t('Otevřít živá case vlákna', 'Open live case threads')} <ArrowRight size={13} />
        </Link>
      </div>
    </section>
  )
}
