// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

import { useCallback, useEffect, useState } from 'react'
import Link from 'next/link'
import { useParams } from 'next/navigation'
import { ArrowLeft, Bot, CheckCircle2, CircleDot, FileText, Flag, GitMerge, RefreshCw, Sparkles, TriangleAlert, UsersRound } from 'lucide-react'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { DataUnavailable } from '@/components/feedback/DataUnavailable'
import type { UnavailableKind } from '@/components/feedback/DataUnavailable'
import { deriveCaseDecisionBrief } from '@/lib/governance/caseDecisionBrief'
import { caseStatusPresentation } from '@/lib/governance/caseStatusPresentation'
import type { CaseStatus } from '@/lib/governance/caseStatusPresentation'
import { PageHeader } from '@/components/ui/PageHeader'
import { CaseRuntimeTimeline, CaseRuntimeTopology } from '@/components/agent/CaseRuntimeViews'
import type { RuntimeEvidenceView } from '@/components/agent/CaseRuntimeViews'

type EntryType = 'CASE_OPENED' | 'CONTRIBUTION' | 'PROPOSAL_EMITTED' | 'SHADOW_RECORDED' | 'POLICY_DECISION' | 'SIGNAL_INVOKED' | 'SIGNAL_CONSUMED' | 'CONTRIBUTION_PERSISTED'

interface ThreadEntry {
  type: EntryType
  atEpochMs: number
  actor?: string
  summary?: string
  evidenceRefs?: string[]
  draftVersion?: number
  superseded?: boolean
  contested?: boolean
  proposalId?: string
  proposalType?: string
  shadow?: boolean
  tokensUsed?: number
  signalId?: string
  capability?: string
  rolloutId?: string
  runtimeEvidence: RuntimeEvidenceView
}

interface CaseThread {
  caseId: string
  caseClass: string
  dispositionTarget: string
  status: CaseStatus
  openedAtEpochMs: number
  deadlineAtEpochMs: number
  contestedRate: number
  budgetTokens: number
  budgetContributions: number
  observedAtEpochMs: number
  dataFromEpochMs: number
  dataToEpochMs: number
  lastSuccessfulLoadEpochMs: number
  coverageStatus: string
  historySource: string
  retentionPolicy: string
  entries: ThreadEntry[]
}

type DetailEnvelope =
  | { available: true; thread: CaseThread }
  | { available: false; reason: string }

function statusVisual(status: CaseStatus): { icon: typeof CircleDot; fg: string; bg: string } {
  switch (status) {
    case 'OPEN':
      return { icon: CircleDot, fg: 'var(--blue)', bg: 'var(--info-bg)' }
    case 'CONVERGING':
      return { icon: GitMerge, fg: 'var(--accent-text)', bg: 'var(--accent-bg)' }
    case 'CONTESTED':
      return { icon: TriangleAlert, fg: 'var(--warning-text)', bg: 'var(--warning-bg)' }
    case 'SYNTHESIZED':
      return { icon: Sparkles, fg: 'var(--accent-text)', bg: 'var(--accent-bg)' }
    case 'CLOSED':
      return { icon: CheckCircle2, fg: 'var(--success-text)', bg: 'var(--success-bg)' }
  }
}

export default function IaopsCaseThreadPage() {
  const params = useParams<{ caseId: string }>()
  const caseId = params.caseId
  const { t, language } = useLanguage()
  const [thread, setThread] = useState<CaseThread | null>(null)
  const [loading, setLoading] = useState(true)
  const [unavailable, setUnavailable] = useState<{ kind: UnavailableKind } | null>(null)
  const [view, setView] = useState<'thread' | 'timeline' | 'topology'>('thread')

  const locale = language === 'cs' ? 'cs-CZ' : 'en-GB'
  const fmt = useCallback(
    (epochMs: number) => new Date(epochMs).toLocaleString(locale, { dateStyle: 'short', timeStyle: 'short' }),
    [locale],
  )

  const load = useCallback(async () => {
    setLoading(true)
    setUnavailable(null)
    try {
      const res = await fetch(`/api/iaops/cases/${encodeURIComponent(caseId)}`, { cache: 'no-store' })
      if (res.status === 401) { setUnavailable({ kind: 'unauthorized' }); return }
      if (res.status === 404) { setUnavailable({ kind: 'not_found' }); return }
      if (!res.ok) { setUnavailable({ kind: 'error' }); return }
      const body = await res.json() as DetailEnvelope
      if (!body.available) {
        setUnavailable({ kind: body.reason === 'unreachable' ? 'unreachable' : 'error' })
        return
      }
      setThread(body.thread)
    } catch {
      setUnavailable({ kind: 'unreachable' })
    } finally {
      setLoading(false)
    }
  }, [caseId])

  useEffect(() => { load() }, [load])

  if (unavailable) {
    return (
      <DataUnavailable
        kind={unavailable.kind}
        service="case-coordinator-agent"
        feature={t('vlákno swarm case', 'swarm case thread')}
        lang={language}
      />
    )
  }

  return (
    <div style={{ padding: '28px 32px', maxWidth: '900px', animation: 'fadeIn 0.2s ease-out' }}>
      <PageHeader
        breadcrumb={<div className="breadcrumb"><span>OpenBank</span><span className="breadcrumb-sep">/</span><Link href="/iaops" className="breadcrumb-current" style={{ textDecoration: 'none' }}>{t('IAOps', 'IAOps')}</Link><span className="breadcrumb-sep">/</span><Link href="/iaops/cases" className="breadcrumb-current" style={{ textDecoration: 'none' }}>{t('Swarm case', 'Swarm cases')}</Link><span className="breadcrumb-sep">/</span><span className="breadcrumb-current">{caseId}</span></div>}
        icon={<GitMerge size={20} aria-hidden="true" />}
        title={caseId}
        subtitle={t('Detail sdíleného case vlákna; data jsou pouze pro čtení.', 'Shared case thread detail; data is read-only.')}
        actions={<Link href="/iaops/cases" className="btn btn-secondary btn-sm"><ArrowLeft size={13} aria-hidden="true" /> {t('Zpět na seznam case', 'Back to case list')}</Link>}
      />

      {loading && !thread ? (
        <div style={{ display: 'flex', alignItems: 'center', gap: '10px', padding: '40px', color: 'var(--text-tertiary)' }}>
          <RefreshCw size={16} style={{ animation: 'spin 0.8s linear infinite' }} />
          <span style={{ fontSize: '13px' }}>{t('Načítám vlákno…', 'Loading thread…')}</span>
        </div>
      ) : thread ? (
        <>
          <div style={{ display: 'flex', alignItems: 'center', gap: '12px', marginBottom: '16px', flexWrap: 'wrap' }}>
            {(() => {
              const visual = statusVisual(thread.status)
              const Icon = visual.icon
              const presentation = caseStatusPresentation(thread.status, language)
              return (
                <div>
                  <span style={{
                    display: 'inline-flex', alignItems: 'center', gap: '5px',
                    fontSize: '11px', fontWeight: 700, padding: '4px 12px', borderRadius: '10px',
                    background: visual.bg, color: visual.fg,
                  }}>
                    <Icon size={12} />
                    {presentation.label}
                  </span>
                  <div style={{ marginTop: '4px', fontSize: '11px', color: 'var(--text-tertiary)' }}>{presentation.detail}</div>
                </div>
              )
            })()}
          </div>

          <div style={{ display: 'flex', flexWrap: 'wrap', gap: '16px', marginBottom: '20px', fontSize: '11px', color: 'var(--text-tertiary)' }}>
            <span>{t('Třída', 'Class')}: <strong style={{ color: 'var(--text-secondary)' }}>{thread.caseClass}</strong></span>
            <span>{t('Cíl dispozice', 'Disposition target')}: <strong style={{ color: 'var(--text-secondary)' }}>{thread.dispositionTarget}</strong></span>
            <span>{t('Otevřeno', 'Opened')}: <strong style={{ color: 'var(--text-secondary)' }}>{fmt(thread.openedAtEpochMs)}</strong></span>
            <span>{t('Deadline', 'Deadline')}: <strong style={{ color: 'var(--text-secondary)' }}>{fmt(thread.deadlineAtEpochMs)}</strong></span>
            <span>{t('Míra sporu', 'Contested rate')}: <strong style={{ color: 'var(--text-secondary)' }}>{Math.round(thread.contestedRate * 100)} %</strong></span>
            <span>{t('Rozpočet', 'Budget')}: <strong style={{ color: 'var(--text-secondary)' }}>{thread.budgetTokens.toLocaleString(locale)} tokens / {thread.budgetContributions} contributions</strong></span>
          </div>

          <nav aria-label={t('Pohled na case', 'Case view')} style={{ display: 'flex', gap: '6px', marginBottom: '16px' }}>
            {(['thread', 'timeline', 'topology'] as const).map(candidate => (
              <button key={candidate} type="button" onClick={() => setView(candidate)} aria-pressed={view === candidate} style={{ padding: '6px 11px', borderRadius: '9px', border: `1px solid ${view === candidate ? 'var(--accent-border)' : 'var(--border)'}`, background: view === candidate ? 'var(--accent-bg)' : 'var(--surface)', color: view === candidate ? 'var(--accent-text)' : 'var(--text-secondary)', fontSize: '11px', fontWeight: 750, cursor: 'pointer', textTransform: 'capitalize' }}>
                {candidate}
              </button>
            ))}
          </nav>

          {(() => {
            const brief = deriveCaseDecisionBrief(thread)
            const stage = brief.stage === 'proposal_recorded'
              ? {
                  title: t('Návrh zaznamenán ve vlákně', 'Proposal recorded in the thread'),
                  detail: t('Koordinátor vytvořil proposal event. Stav doručení a lidského rozhodnutí tato stránka nesleduje.', 'The coordinator created a proposal event. This page does not track delivery or the human decision.'),
                  tone: 'var(--accent-text)', bg: 'var(--accent-bg)', border: 'var(--accent-border)',
                }
              : brief.stage === 'shadow_recorded'
                ? {
                    title: t('Shadow výsledek zaznamenán', 'Shadow result recorded'),
                    detail: t('Jde pouze o pilotní důkaz. Výsledek nebyl odeslán do HITL fronty ani nepředstavuje lidské rozhodnutí.', 'This is pilot evidence only. It was not sent to the HITL queue and is not a human decision.'),
                    tone: 'var(--info-text)', bg: 'var(--info-bg)', border: 'var(--border)',
                  }
              : brief.stage === 'needs_convergence'
                ? {
                    title: t('Neshoda zůstává viditelná', 'Dissent remains visible'),
                    detail: t('Příspěvky si odporují; koordinátor zatím nepředstírá shodu ani nevydal návrh.', 'Inputs conflict; the coordinator does not pretend there is agreement or emit a proposal yet.'),
                    tone: 'var(--warning-text)', bg: 'var(--warning-bg)', border: 'var(--warning-border)',
                  }
                : {
                    title: brief.contributorCount > 0 ? t('Koordinátor porovnává podklady', 'The coordinator is comparing inputs') : t('Čeká se na řízené podklady', 'Waiting for governed inputs'),
                    detail: t('Tato stránka shrnuje jen příspěvky, které už ve vlákně jsou — nic sama nedoplňuje.', 'This page summarises only contributions already in the thread — it adds nothing itself.'),
                    tone: 'var(--blue)', bg: 'var(--info-bg)', border: 'var(--border)',
                  }

            return (
              <section aria-labelledby="case-decision-brief-title" style={{ marginBottom: '18px', padding: '16px 18px', borderRadius: '14px', background: 'var(--surface)', border: '1px solid var(--border)' }}>
                <div style={{ display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between', gap: '12px', flexWrap: 'wrap' }}>
                  <div>
                    <div style={{ display: 'inline-flex', alignItems: 'center', gap: '6px', color: 'var(--accent-text)', fontSize: '10px', fontWeight: 800, letterSpacing: '.08em', textTransform: 'uppercase' }}>
                      <UsersRound size={13} /> {t('Rozhodovací přehled', 'Decision brief')}
                    </div>
                    <h2 id="case-decision-brief-title" style={{ margin: '5px 0 0', color: 'var(--text-primary)', fontSize: '15px', letterSpacing: '-.015em' }}>{stage.title}</h2>
                  </div>
                  {brief.stage === 'proposal_recorded' && (
                    <Link href="/approvals" style={{ display: 'inline-flex', alignItems: 'center', gap: '5px', padding: '7px 10px', borderRadius: '8px', color: 'var(--accent-text)', background: 'var(--accent-bg)', fontSize: '11px', fontWeight: 750, textDecoration: 'none' }}>
                      {t('Přejít do HITL fronty', 'Go to HITL queue')}
                    </Link>
                  )}
                </div>
                <p style={{ margin: '8px 0 14px', color: 'var(--text-secondary)', fontSize: '12px', lineHeight: 1.5 }}>{stage.detail}</p>
                <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(140px, 1fr))', gap: '8px' }}>
                  <div style={{ padding: '9px 10px', borderRadius: '10px', background: stage.bg, border: `1px solid ${stage.border}` }}>
                    <div style={{ color: 'var(--text-tertiary)', fontSize: '10px', fontWeight: 700 }}>{t('Další krok', 'Next step')}</div>
                    <div style={{ marginTop: '3px', color: stage.tone, fontSize: '11px', fontWeight: 800 }}>{stage.title}</div>
                  </div>
                  <div style={{ padding: '9px 10px', borderRadius: '10px', background: 'var(--surface-2)', border: '1px solid var(--border)' }}>
                    <div style={{ color: 'var(--text-tertiary)', fontSize: '10px', fontWeight: 700 }}>{t('Role s příspěvkem', 'Contributing roles')}</div>
                    <div style={{ marginTop: '3px', color: 'var(--text-primary)', fontSize: '11px', fontWeight: 800 }}>{brief.contributorCount}</div>
                  </div>
                  <div style={{ padding: '9px 10px', borderRadius: '10px', background: 'var(--surface-2)', border: '1px solid var(--border)' }}>
                    <div style={{ color: 'var(--text-tertiary)', fontSize: '10px', fontWeight: 700 }}>{t('Odkazy na důkazy', 'Evidence references')}</div>
                    <div style={{ marginTop: '3px', color: 'var(--text-primary)', fontSize: '11px', fontWeight: 800 }}>{brief.evidenceRefCount}</div>
                  </div>
                  <div style={{ padding: '9px 10px', borderRadius: '10px', background: brief.contestedContributionCount > 0 ? 'var(--warning-bg)' : 'var(--surface-2)', border: `1px solid ${brief.contestedContributionCount > 0 ? 'var(--warning-border)' : 'var(--border)'}` }}>
                    <div style={{ color: 'var(--text-tertiary)', fontSize: '10px', fontWeight: 700 }}>{t('Označené nesouhlasy', 'Marked dissent')}</div>
                    <div style={{ marginTop: '3px', color: brief.contestedContributionCount > 0 ? 'var(--warning-text)' : 'var(--text-primary)', fontSize: '11px', fontWeight: 800 }}>{brief.contestedContributionCount}</div>
                  </div>
                </div>
              </section>
            )
          })()}

          {thread.status === 'CONTESTED' && (
            <div style={{
              display: 'flex', alignItems: 'center', gap: '8px', padding: '10px 14px', borderRadius: '10px',
              background: 'var(--warning-bg)', border: '1px solid var(--warning-border)',
              color: 'var(--warning-text)', fontSize: '12px', fontWeight: 600, marginBottom: '16px',
            }}>
              <TriangleAlert size={14} />
              {t(
                'Vlákno je sporné — příspěvky agentů si odporují a koordinátor ještě nesyntetizoval závěr.',
                'This thread is contested — agent contributions conflict and the coordinator has not synthesized a conclusion yet.',
              )}
            </div>
          )}

          {view === 'timeline' && <CaseRuntimeTimeline thread={thread} locale={locale} />}
          {view === 'topology' && <CaseRuntimeTopology thread={thread} locale={locale} />}
          {view === 'thread' && <div style={{ display: 'flex', flexDirection: 'column', gap: '12px' }}>
            {thread.entries.map((entry, index) => {
              if (entry.type === 'CASE_OPENED') {
                return (
                  <div key={index} style={{ display: 'flex', alignItems: 'center', gap: '10px', padding: '8px 4px', color: 'var(--text-tertiary)', fontSize: '12px' }}>
                    <Flag size={14} style={{ color: 'var(--accent-text)' }} />
                    <span>
                      <strong style={{ color: 'var(--text-secondary)' }}>{entry.actor ?? 'case-coordinator'}</strong>
                      {' '}{t('otevřel case', 'opened the case')}{' · '}{fmt(entry.atEpochMs)}
                    </span>
                  </div>
                )
              }
              if (entry.type === 'PROPOSAL_EMITTED' || entry.type === 'SHADOW_RECORDED') {
                const shadow = entry.type === 'SHADOW_RECORDED' || entry.shadow === true
                return (
                  <div key={index} style={{
                    padding: '14px 18px', borderRadius: '12px',
                    background: 'var(--accent-bg)', border: '1px solid var(--accent-border)',
                  }}>
                    <div style={{ display: 'flex', alignItems: 'center', gap: '8px', flexWrap: 'wrap' }}>
                      <Sparkles size={14} style={{ color: 'var(--accent-text)' }} />
                      <span style={{ fontSize: '12px', fontWeight: 700, color: 'var(--accent-text)' }}>
                        {shadow ? t('Shadow výsledek zaznamenán', 'Shadow result recorded') : t('Návrh zaznamenán ve vlákně', 'Proposal recorded in the thread')}
                      </span>
                      <span style={{ fontFamily: 'var(--font-mono)', fontSize: '11px', color: 'var(--text-secondary)' }}>{entry.proposalType ?? ''}</span>
                      <span style={{ marginLeft: 'auto', fontSize: '11px', color: 'var(--text-tertiary)' }}>{fmt(entry.atEpochMs)}</span>
                    </div>
                    {entry.summary && (
                      <p style={{ fontSize: '12px', color: 'var(--text-secondary)', margin: '8px 0 0', lineHeight: 1.5 }}>{entry.summary}</p>
                    )}
                    {!shadow && <Link href="/approvals" style={{ display: 'inline-flex', alignItems: 'center', gap: '5px', marginTop: '10px', fontSize: '11px', fontWeight: 700, color: 'var(--accent-text)', textDecoration: 'none' }}>
                      {t('Procházet HITL frontu', 'Browse the HITL queue')}
                    </Link>}
                  </div>
                )
              }
              if (entry.type === 'POLICY_DECISION' || entry.type === 'SIGNAL_INVOKED' || entry.type === 'SIGNAL_CONSUMED' || entry.type === 'CONTRIBUTION_PERSISTED') {
                const denied = entry.runtimeEvidence.stage === 'DENIED'
                return (
                  <div key={index} style={{ padding: '12px 16px', borderRadius: '12px', background: denied ? 'var(--danger-bg)' : 'var(--info-bg)', border: `1px solid ${denied ? 'var(--danger)' : 'var(--border)'}` }}>
                    <div style={{ display: 'flex', gap: '8px', alignItems: 'center', flexWrap: 'wrap' }}>
                      {denied ? <TriangleAlert size={13} style={{ color: 'var(--danger)' }} /> : <CircleDot size={13} style={{ color: 'var(--blue)' }} />}
                      <strong style={{ fontSize: '11px', color: denied ? 'var(--danger)' : 'var(--text-primary)' }}>{entry.type}</strong>
                      <span style={{ fontFamily: 'var(--font-mono)', fontSize: '10px', color: 'var(--text-secondary)' }}>{entry.actor ?? '—'}</span>
                      {entry.capability && <span style={{ fontFamily: 'var(--font-mono)', fontSize: '10px', color: 'var(--accent-text)' }}>{entry.capability}</span>}
                      <span style={{ marginLeft: 'auto', fontSize: '10px', color: 'var(--text-tertiary)' }}>{fmt(entry.atEpochMs)}</span>
                    </div>
                    <div style={{ marginTop: '6px', fontFamily: 'var(--font-mono)', fontSize: '9px', color: 'var(--text-tertiary)' }}>
                      {entry.signalId && <>signal {entry.signalId}</>}{entry.signalId && entry.rolloutId && ' · '}{entry.rolloutId && <>rollout {entry.rolloutId}</>}
                    </div>
                    {entry.summary && <p style={{ margin: '6px 0 0', fontSize: '11px', color: 'var(--text-secondary)' }}>{entry.summary}</p>}
                  </div>
                )
              }
              const contested = entry.contested === true
              const superseded = entry.superseded === true
              return (
                <div key={index} style={{
                  padding: '14px 18px', borderRadius: '12px',
                  background: 'var(--surface)',
                  border: `1px solid ${contested ? 'var(--warning-border)' : 'var(--border)'}`,
                  opacity: superseded ? 0.55 : 1,
                }}>
                  <div style={{ display: 'flex', alignItems: 'center', gap: '8px', flexWrap: 'wrap' }}>
                    <Bot size={14} style={{ color: 'var(--accent-text)' }} />
                    <span style={{ fontFamily: 'var(--font-mono)', fontSize: '12px', fontWeight: 700, color: 'var(--text-primary)' }}>{entry.actor ?? '—'}</span>
                    {entry.draftVersion != null && (
                      <span style={{ fontSize: '10px', fontWeight: 700, padding: '2px 8px', borderRadius: '8px', background: 'var(--info-bg)', color: 'var(--blue)' }}>
                        {t(`draft v${entry.draftVersion}`, `draft v${entry.draftVersion}`)}
                      </span>
                    )}
                    {contested && (
                      <span style={{ display: 'inline-flex', alignItems: 'center', gap: '4px', fontSize: '10px', fontWeight: 700, padding: '2px 8px', borderRadius: '8px', background: 'var(--warning-bg)', color: 'var(--warning-text)' }}>
                        <TriangleAlert size={10} />
                        {t('sporný', 'contested')}
                      </span>
                    )}
                    {superseded && (
                      <span style={{ fontSize: '10px', fontWeight: 700, padding: '2px 8px', borderRadius: '8px', background: 'var(--surface)', border: '1px solid var(--border)', color: 'var(--text-tertiary)' }}>
                        {t('předběhnuto novějším draftem', 'superseded by a newer draft')}
                      </span>
                    )}
                    <span style={{ marginLeft: 'auto', fontSize: '11px', color: 'var(--text-tertiary)' }}>{fmt(entry.atEpochMs)}</span>
                  </div>
                  {entry.summary && (
                    <p style={{ fontSize: '12px', color: 'var(--text-secondary)', margin: '8px 0 0', lineHeight: 1.5 }}>{entry.summary}</p>
                  )}
                  {entry.evidenceRefs && entry.evidenceRefs.length > 0 && (
                    <div style={{ display: 'flex', flexWrap: 'wrap', gap: '6px', marginTop: '10px' }}>
                      {entry.evidenceRefs.map((ref) => (
                        <span key={ref} style={{
                          display: 'inline-flex', alignItems: 'center', gap: '4px',
                          fontFamily: 'var(--font-mono)', fontSize: '10px', padding: '3px 8px', borderRadius: '8px',
                          background: 'var(--surface)', border: '1px solid var(--border)', color: 'var(--text-secondary)',
                        }}>
                          <FileText size={10} />
                          {ref}
                        </span>
                      ))}
                    </div>
                  )}
                </div>
              )
            })}
          </div>}
        </>
      ) : null}
    </div>
  )
}
