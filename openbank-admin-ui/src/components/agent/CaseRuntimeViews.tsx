// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

'use client'

import { ArrowRight, CheckCircle2, CircleDot, Database, ShieldCheck, TriangleAlert } from 'lucide-react'
import { useLanguage } from '@/lib/i18n/LanguageContext'

export type RuntimeStage = 'AUTHORIZED' | 'DENIED' | 'INVOKED' | 'CONSUMED' | 'RECORDED' | 'PERSISTED' | 'EMITTED' | 'PUBLISHED_TO_BROKER' | 'PUBLISH_FAILED' | 'SHADOW_RECORDED'

export interface RuntimeEvidenceView {
  evidenceId: string
  source: string
  stage: RuntimeStage
  observedAtEpochMs: number
  correlationId: string
  detail: string
}

export interface RuntimeEntryView {
  type: string
  atEpochMs: number
  actor?: string
  proposalType?: string
  signalId?: string
  capability?: string
  rolloutId?: string
  runtimeEvidence: RuntimeEvidenceView
}

export interface RuntimeCaseView {
  caseId: string
  historySource: string
  retentionPolicy: string
  observedAtEpochMs: number
  dataFromEpochMs: number
  dataToEpochMs: number
  lastSuccessfulLoadEpochMs: number
  coverageStatus: string
  entries: RuntimeEntryView[]
}

function stageTone(stage: RuntimeStage): { color: string; bg: string; Icon: typeof CircleDot } {
  if (stage === 'PUBLISH_FAILED' || stage === 'DENIED') return { color: 'var(--danger)', bg: 'var(--danger-bg)', Icon: TriangleAlert }
  if (stage === 'PUBLISHED_TO_BROKER') return { color: 'var(--success)', bg: 'var(--success-bg)', Icon: CheckCircle2 }
  return { color: 'var(--accent-text)', bg: 'var(--accent-bg)', Icon: CircleDot }
}

function evidenceAge(epochMs: number): string {
  const seconds = Math.max(0, Math.floor((Date.now() - epochMs) / 1000))
  if (seconds < 60) return `${seconds}s`
  const minutes = Math.floor(seconds / 60)
  if (minutes < 60) return `${minutes}m`
  const hours = Math.floor(minutes / 60)
  if (hours < 24) return `${hours}h`
  return `${Math.floor(hours / 24)}d`
}

function EvidenceDetails({ evidence, locale }: { evidence: RuntimeEvidenceView; locale: string }) {
  const { t } = useLanguage()
  const tone = stageTone(evidence.stage)
  return (
    <details style={{ marginTop: '8px' }}>
      <summary style={{ cursor: 'pointer', color: tone.color, fontSize: '10px', fontWeight: 800 }}>
        {evidence.stage} · {evidence.evidenceId}
      </summary>
      <dl style={{ display: 'grid', gridTemplateColumns: 'max-content 1fr', gap: '4px 10px', margin: '8px 0 0', fontSize: '10px' }}>
        <dt style={{ color: 'var(--text-tertiary)' }}>{t('Zdroj', 'Source')}</dt><dd style={{ margin: 0, fontFamily: 'var(--font-mono)' }}>{evidence.source}</dd>
        <dt style={{ color: 'var(--text-tertiary)' }}>{t('Pozorováno', 'Observed')}</dt><dd style={{ margin: 0 }}>{new Date(evidence.observedAtEpochMs).toLocaleString(locale)}</dd>
        <dt style={{ color: 'var(--text-tertiary)' }}>{t('Stáří', 'Age')}</dt><dd style={{ margin: 0 }}>{evidenceAge(evidence.observedAtEpochMs)}</dd>
        <dt style={{ color: 'var(--text-tertiary)' }}>{t('Korelace', 'Correlation')}</dt><dd style={{ margin: 0, fontFamily: 'var(--font-mono)' }}>{evidence.correlationId}</dd>
        <dt style={{ color: 'var(--text-tertiary)' }}>{t('Význam', 'Meaning')}</dt><dd style={{ margin: 0 }}>{evidence.detail}</dd>
      </dl>
    </details>
  )
}

export function CaseRuntimeTimeline({ thread, locale }: { thread: RuntimeCaseView; locale: string }) {
  const { t } = useLanguage()
  return (
    <section aria-label={t('Časová osa runtime důkazů', 'Runtime evidence timeline')} style={{ display: 'flex', flexDirection: 'column', gap: '10px' }}>
      {thread.entries.map((entry, index) => {
        const tone = stageTone(entry.runtimeEvidence.stage)
        const Icon = tone.Icon
        return (
          <article key={`${entry.runtimeEvidence.evidenceId}-${index}`} style={{ padding: '12px 14px', borderRadius: '12px', border: '1px solid var(--border)', background: 'var(--surface)' }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: '8px', flexWrap: 'wrap' }}>
              <Icon size={13} style={{ color: tone.color }} />
              <strong style={{ fontSize: '11px' }}>{entry.type}</strong>
              <span style={{ fontSize: '10px', color: 'var(--text-secondary)' }}>{entry.actor ?? 'case-coordinator'}</span>
              {entry.capability && <span style={{ fontFamily: 'var(--font-mono)', fontSize: '10px', color: 'var(--accent-text)' }}>{entry.capability}</span>}
              <time style={{ marginLeft: 'auto', fontSize: '10px', color: 'var(--text-tertiary)' }}>{new Date(entry.atEpochMs).toLocaleString(locale)}</time>
            </div>
            {(entry.signalId || entry.rolloutId) && (
              <div style={{ marginTop: '6px', fontFamily: 'var(--font-mono)', fontSize: '9px', color: 'var(--text-tertiary)' }}>
                {entry.signalId && <>signal {entry.signalId}</>}{entry.signalId && entry.rolloutId && ' · '}{entry.rolloutId && <>rollout {entry.rolloutId}</>}
              </div>
            )}
            <EvidenceDetails evidence={entry.runtimeEvidence} locale={locale} />
          </article>
        )
      })}
    </section>
  )
}

interface Edge {
  from: string
  to: string
  evidence: RuntimeEvidenceView
}

function runtimeEdges(thread: RuntimeCaseView): Edge[] {
  return thread.entries.flatMap(entry => {
    if (entry.type === 'POLICY_DECISION' && entry.actor && entry.runtimeEvidence.stage === 'AUTHORIZED') return [{ from: entry.actor, to: 'OPA case policy', evidence: entry.runtimeEvidence }]
    if (entry.type === 'SIGNAL_INVOKED' && entry.actor) return [{ from: entry.actor, to: 'Temporal signal client', evidence: entry.runtimeEvidence }]
    if (entry.type === 'SIGNAL_CONSUMED' && entry.actor) return [{ from: entry.actor, to: 'case workflow', evidence: entry.runtimeEvidence }]
    if (entry.type === 'CONTRIBUTION_PERSISTED' && entry.actor) return [{ from: entry.actor, to: 'durable case read model', evidence: entry.runtimeEvidence }]
    if (entry.type === 'CONTRIBUTION' && entry.actor) return [{ from: entry.actor, to: 'case-coordinator', evidence: entry.runtimeEvidence }]
    if (entry.type === 'PROPOSAL_EMITTED') return [{ from: 'case-coordinator', to: 'proposal event broker', evidence: entry.runtimeEvidence }]
    if (entry.type === 'SHADOW_RECORDED') return [{ from: 'case-coordinator', to: 'shadow evidence only', evidence: entry.runtimeEvidence }]
    return []
  })
}

export function CaseRuntimeTopology({ thread, locale }: { thread: RuntimeCaseView; locale: string }) {
  const { t } = useLanguage()
  const edges = runtimeEdges(thread)
  return (
    <section aria-label={t('Topologie podložená runtime důkazy', 'Evidence-backed runtime topology')}>
      <div style={{ padding: '10px 12px', marginBottom: '12px', borderRadius: '10px', background: 'var(--info-bg)', border: '1px solid var(--border)', fontSize: '11px', color: 'var(--text-secondary)' }}>
        <ShieldCheck size={13} style={{ verticalAlign: '-2px', marginRight: '6px', color: 'var(--blue)' }} />
        {t(
          'Plné hrany níže existují pouze díky trvalému runtime pozorování. Samotná deklarace v charteru plnou hranu nikdy nevytvoří.',
          'Solid edges below exist only because a durable runtime observation is present. Charter declarations alone never create a solid edge.',
        )}
      </div>
      {edges.length === 0 ? (
        <div style={{ padding: '24px', textAlign: 'center', border: '1px dashed var(--border)', borderRadius: '12px', color: 'var(--text-tertiary)', fontSize: '12px' }}>
          {t('V tomto případu zatím nejsou žádné pozorované hrany spolupráce.', 'No observed collaboration edges in this case yet.')}
        </div>
      ) : (
        <div style={{ display: 'flex', flexDirection: 'column', gap: '10px' }}>
          {edges.map((edge, index) => (
            <article key={`${edge.evidence.evidenceId}-${index}`} style={{ padding: '13px 15px', borderRadius: '12px', border: '1px solid var(--accent-border)', background: 'var(--surface)' }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: '9px', flexWrap: 'wrap', fontSize: '12px' }}>
                <strong style={{ fontFamily: 'var(--font-mono)' }}>{edge.from}</strong>
                <span style={{ height: '2px', width: '40px', background: 'var(--accent-text)' }} />
                <ArrowRight size={14} style={{ color: 'var(--accent-text)', marginLeft: '-14px' }} />
                <strong style={{ fontFamily: 'var(--font-mono)' }}>{edge.to}</strong>
                <span style={{ marginLeft: 'auto', padding: '2px 7px', borderRadius: '8px', background: 'var(--accent-bg)', color: 'var(--accent-text)', fontSize: '9px', fontWeight: 850 }}>{edge.evidence.stage}</span>
              </div>
              <EvidenceDetails evidence={edge.evidence} locale={locale} />
            </article>
          ))}
        </div>
      )}
      <div style={{ display: 'flex', gap: '8px', alignItems: 'center', marginTop: '12px', fontSize: '10px', color: 'var(--text-tertiary)' }}>
        <Database size={11} /> {thread.historySource} · {t('retence', 'retention')}: {thread.retentionPolicy} · {t('coverage', 'coverage')}: {thread.coverageStatus.toLowerCase().replaceAll('_', ' ')} · {new Date(thread.dataFromEpochMs).toLocaleString(locale)} → {new Date(thread.dataToEpochMs).toLocaleString(locale)} · {t('poslední úspěšné načtení', 'last successful load')} {new Date(thread.lastSuccessfulLoadEpochMs).toLocaleString(locale)}
      </div>
    </section>
  )
}
