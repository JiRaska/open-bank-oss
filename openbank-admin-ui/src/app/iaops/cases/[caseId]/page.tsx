// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

import { useCallback, useEffect, useState } from 'react'
import Link from 'next/link'
import { useParams } from 'next/navigation'
import { ArrowLeft, Bot, CheckCircle2, CircleDot, FileText, Flag, GitMerge, RefreshCw, Sparkles, TriangleAlert } from 'lucide-react'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { DataUnavailable } from '@/components/feedback/DataUnavailable'
import type { UnavailableKind } from '@/components/feedback/DataUnavailable'

type CaseStatus = 'OPEN' | 'CONVERGING' | 'CONTESTED' | 'SYNTHESIZED' | 'CLOSED'
type EntryType = 'CASE_OPENED' | 'CONTRIBUTION' | 'PROPOSAL_EMITTED'

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
}

interface CaseThread {
  caseId: string
  caseClass: string
  dispositionTarget: string
  status: CaseStatus
  openedAtEpochMs: number
  deadlineAtEpochMs: number
  contestedRate: number
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

  const locale = language === 'cs' ? 'cs-CZ' : 'en-GB'
  const fmt = useCallback(
    (epochMs: number) => new Date(epochMs).toLocaleString(locale, { dateStyle: 'short', timeStyle: 'short' }),
    [locale],
  )

  const statusLabel = useCallback(
    (status: CaseStatus): string => {
      switch (status) {
        case 'OPEN': return t('Otevřený', 'Open')
        case 'CONVERGING': return t('Konverguje', 'Converging')
        case 'CONTESTED': return t('Sporný', 'Contested')
        case 'SYNTHESIZED': return t('Syntetizovaný', 'Synthesized')
        case 'CLOSED': return t('Uzavřený', 'Closed')
      }
    },
    [t],
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
      <div style={{ marginBottom: '20px' }}>
        <div className="breadcrumb">
          <span>OpenBank</span><span className="breadcrumb-sep">/</span>
          <Link href="/iaops" className="breadcrumb-current" style={{ textDecoration: 'none' }}>{t('IAOps', 'IAOps')}</Link>
          <span className="breadcrumb-sep">/</span>
          <Link href="/iaops/cases" className="breadcrumb-current" style={{ textDecoration: 'none' }}>{t('Swarm case', 'Swarm cases')}</Link>
          <span className="breadcrumb-sep">/</span>
          <span className="breadcrumb-current">{caseId}</span>
        </div>
        <Link href="/iaops/cases" style={{ display: 'inline-flex', alignItems: 'center', gap: '6px', marginTop: '10px', fontSize: '12px', color: 'var(--text-secondary)', textDecoration: 'none' }}>
          <ArrowLeft size={13} /> {t('Zpět na seznam case', 'Back to case list')}
        </Link>
      </div>

      {loading && !thread ? (
        <div style={{ display: 'flex', alignItems: 'center', gap: '10px', padding: '40px', color: 'var(--text-tertiary)' }}>
          <RefreshCw size={16} style={{ animation: 'spin 0.8s linear infinite' }} />
          <span style={{ fontSize: '13px' }}>{t('Načítám vlákno…', 'Loading thread…')}</span>
        </div>
      ) : thread ? (
        <>
          <div style={{ display: 'flex', alignItems: 'center', gap: '12px', marginBottom: '16px', flexWrap: 'wrap' }}>
            <h1 style={{ fontFamily: 'var(--font-mono)', fontSize: '18px', fontWeight: 800, color: 'var(--text-primary)', margin: 0 }}>
              {thread.caseId}
            </h1>
            {(() => {
              const visual = statusVisual(thread.status)
              const Icon = visual.icon
              return (
                <span style={{
                  display: 'inline-flex', alignItems: 'center', gap: '5px',
                  fontSize: '11px', fontWeight: 700, padding: '4px 12px', borderRadius: '10px',
                  background: visual.bg, color: visual.fg,
                }}>
                  <Icon size={12} />
                  {statusLabel(thread.status)}
                </span>
              )
            })()}
          </div>

          <div style={{ display: 'flex', flexWrap: 'wrap', gap: '16px', marginBottom: '20px', fontSize: '11px', color: 'var(--text-tertiary)' }}>
            <span>{t('Třída', 'Class')}: <strong style={{ color: 'var(--text-secondary)' }}>{thread.caseClass}</strong></span>
            <span>{t('Cíl dispozice', 'Disposition target')}: <strong style={{ color: 'var(--text-secondary)' }}>{thread.dispositionTarget}</strong></span>
            <span>{t('Otevřeno', 'Opened')}: <strong style={{ color: 'var(--text-secondary)' }}>{fmt(thread.openedAtEpochMs)}</strong></span>
            <span>{t('Deadline', 'Deadline')}: <strong style={{ color: 'var(--text-secondary)' }}>{fmt(thread.deadlineAtEpochMs)}</strong></span>
            <span>{t('Míra sporu', 'Contested rate')}: <strong style={{ color: 'var(--text-secondary)' }}>{Math.round(thread.contestedRate * 100)} %</strong></span>
          </div>

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

          <div style={{ display: 'flex', flexDirection: 'column', gap: '12px' }}>
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
              if (entry.type === 'PROPOSAL_EMITTED') {
                return (
                  <div key={index} style={{
                    padding: '14px 18px', borderRadius: '12px',
                    background: 'var(--accent-bg)', border: '1px solid var(--accent-border)',
                  }}>
                    <div style={{ display: 'flex', alignItems: 'center', gap: '8px', flexWrap: 'wrap' }}>
                      <Sparkles size={14} style={{ color: 'var(--accent-text)' }} />
                      <span style={{ fontSize: '12px', fontWeight: 700, color: 'var(--accent-text)' }}>
                        {t('Koordinátor syntetizoval návrh', 'Coordinator synthesized a proposal')}
                      </span>
                      <span style={{ fontFamily: 'var(--font-mono)', fontSize: '11px', color: 'var(--text-secondary)' }}>{entry.proposalType ?? ''}</span>
                      <span style={{ marginLeft: 'auto', fontSize: '11px', color: 'var(--text-tertiary)' }}>{fmt(entry.atEpochMs)}</span>
                    </div>
                    {entry.summary && (
                      <p style={{ fontSize: '12px', color: 'var(--text-secondary)', margin: '8px 0 0', lineHeight: 1.5 }}>{entry.summary}</p>
                    )}
                    <Link href="/approvals" style={{ display: 'inline-flex', alignItems: 'center', gap: '5px', marginTop: '10px', fontSize: '11px', fontWeight: 700, color: 'var(--accent-text)', textDecoration: 'none' }}>
                      {t('Otevřít HITL frontu ke schválení', 'Open the HITL queue for approval')}
                    </Link>
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
          </div>
        </>
      ) : null}
    </div>
  )
}
