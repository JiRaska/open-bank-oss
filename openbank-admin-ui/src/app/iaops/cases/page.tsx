// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

import { useCallback, useEffect, useState } from 'react'
import Link from 'next/link'
import { ArrowLeft, CheckCircle2, CircleDot, GitMerge, RefreshCw, Sparkles, TriangleAlert } from 'lucide-react'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { DataUnavailable } from '@/components/feedback/DataUnavailable'
import type { UnavailableKind } from '@/components/feedback/DataUnavailable'
import { CASE_STATUSES, caseStatusPresentation } from '@/lib/governance/caseStatusPresentation'
import type { CaseStatus } from '@/lib/governance/caseStatusPresentation'
import { PageHeader } from '@/components/ui/PageHeader'

const PAGE_SIZE = 25
const MAX_LIMIT = 200

interface CaseSummary {
  caseId: string
  caseClass: string
  dispositionTarget: string
  status: CaseStatus
  openedAtEpochMs: number
  deadlineAtEpochMs: number
  contestedRate: number
  contributionCount: number
}

type ListEnvelope =
  | { available: true; cases: CaseSummary[] }
  | { available: false; reason: string; cases: [] }

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

export default function IaopsCasesPage() {
  const { t, language } = useLanguage()
  const [statusFilter, setStatusFilter] = useState<CaseStatus | null>(null)
  const [limit, setLimit] = useState(PAGE_SIZE)
  const [cases, setCases] = useState<CaseSummary[]>([])
  const [loading, setLoading] = useState(true)
  const [unavailable, setUnavailable] = useState<{ kind: UnavailableKind } | null>(null)

  const locale = language === 'cs' ? 'cs-CZ' : 'en-GB'
  const fmt = useCallback(
    (epochMs: number) => new Date(epochMs).toLocaleString(locale, { dateStyle: 'short', timeStyle: 'short' }),
    [locale],
  )

  const load = useCallback(async () => {
    setLoading(true)
    setUnavailable(null)
    try {
      const params = new URLSearchParams({ limit: String(limit) })
      if (statusFilter) params.set('status', statusFilter)
      const res = await fetch(`/api/iaops/cases?${params.toString()}`, { cache: 'no-store' })
      if (res.status === 401) { setUnavailable({ kind: 'unauthorized' }); return }
      if (!res.ok) { setUnavailable({ kind: 'error' }); return }
      const body = await res.json() as ListEnvelope
      if (!body.available) {
        const kind: UnavailableKind =
          body.reason === 'not_deployed' ? 'not_deployed'
          : body.reason === 'unreachable' ? 'unreachable'
          : 'error'
        setUnavailable({ kind })
        return
      }
      setCases(body.cases)
    } catch {
      setUnavailable({ kind: 'unreachable' })
    } finally {
      setLoading(false)
    }
  }, [limit, statusFilter])

  useEffect(() => { load() }, [load])

  if (unavailable) {
    return (
      <DataUnavailable
        kind={unavailable.kind}
        service="case-coordinator-agent"
        feature={t('swarm case', 'swarm cases')}
        lang={language}
      />
    )
  }

  return (
    <div style={{ padding: '28px 32px', maxWidth: '1100px', animation: 'fadeIn 0.2s ease-out' }}>
      <PageHeader
        breadcrumb={<div className="breadcrumb"><span>OpenBank</span><span className="breadcrumb-sep">/</span><Link href="/iaops" className="breadcrumb-current" style={{ textDecoration: 'none' }}>{t('IAOps', 'IAOps')}</Link><span className="breadcrumb-sep">/</span><span className="breadcrumb-current">{t('Swarm case', 'Swarm cases')}</span></div>}
        icon={<GitMerge size={20} aria-hidden="true" />}
        title={t('Swarm case', 'Swarm cases')}
        subtitle={t('Sdílené případy, ve kterých agenti koordinovaně přispívají do jednoho vlákna (Temporal CaseWorkflow, ADR-0244). Jen pro čtení.', 'Shared cases agents coordinate on in a single thread (Temporal CaseWorkflow, ADR-0244). Read-only.')}
        actions={<Link href="/iaops" className="btn btn-secondary btn-sm"><ArrowLeft size={13} aria-hidden="true" /> {t('Zpět na IAOps', 'Back to IAOps')}</Link>}
      />

      <div role="group" aria-label={t('Filtrovat swarm cases podle stavu', 'Filter swarm cases by status')} style={{ display: 'flex', flexWrap: 'wrap', gap: '8px', marginBottom: '16px' }}>
        <button
          aria-pressed={statusFilter === null}
          onClick={() => { setStatusFilter(null); setLimit(PAGE_SIZE) }}
          style={{
            fontSize: '11px', fontWeight: 700, padding: '5px 12px', borderRadius: '14px', cursor: 'pointer',
            border: `1px solid ${statusFilter === null ? 'var(--accent-border)' : 'var(--border)'}`,
            background: statusFilter === null ? 'var(--accent-bg)' : 'var(--surface)',
            color: statusFilter === null ? 'var(--accent-text)' : 'var(--text-secondary)',
          }}
        >
          {t('Všechny', 'All')}
        </button>
        {CASE_STATUSES.map((status) => {
          const visual = statusVisual(status)
          const active = statusFilter === status
          const presentation = caseStatusPresentation(status, language)
          return (
            <button
              key={status}
              aria-pressed={active}
              onClick={() => { setStatusFilter(active ? null : status); setLimit(PAGE_SIZE) }}
              style={{
                display: 'inline-flex', alignItems: 'center', gap: '5px',
                fontSize: '11px', fontWeight: 700, padding: '5px 12px', borderRadius: '14px', cursor: 'pointer',
                border: `1px solid ${active ? visual.fg : 'var(--border)'}`,
                background: active ? visual.bg : 'var(--surface)',
              color: active ? visual.fg : 'var(--text-secondary)',
              }}
              title={presentation.detail}
            >
              <visual.icon size={12} />
              {presentation.label}
            </button>
          )
        })}
      </div>

      {loading ? (
        <div role="status" aria-live="polite" style={{ display: 'flex', alignItems: 'center', gap: '10px', padding: '40px', color: 'var(--text-tertiary)' }}>
          <RefreshCw size={16} aria-hidden="true" style={{ animation: 'spin 0.8s linear infinite' }} />
          <span style={{ fontSize: '13px' }}>{t('Načítám case…', 'Loading cases…')}</span>
        </div>
      ) : cases.length === 0 ? (
        <div style={{
          padding: '32px', borderRadius: '12px', textAlign: 'center',
          background: 'var(--surface)', border: '1px solid var(--border)',
        }}>
          <GitMerge size={22} style={{ color: 'var(--text-tertiary)', marginBottom: '8px' }} />
          <div style={{ fontSize: '13px', fontWeight: 600, color: 'var(--text-secondary)' }}>
            {t('Zatím nebyla otevřena žádná case.', 'No cases have been opened yet.')}
          </div>
          <div style={{ fontSize: '12px', color: 'var(--text-tertiary)', marginTop: '4px' }}>
            {t(
              'Až case-coordinator otevře první koordinační případ, objeví se tady jeho vlákno.',
              'Once case-coordinator opens its first coordination case, its thread will appear here.',
            )}
          </div>
        </div>
      ) : (
        <>
          <div style={{ display: 'flex', flexDirection: 'column', gap: '10px' }}>
            {cases.map((item) => {
              const visual = statusVisual(item.status)
              const Icon = visual.icon
              const presentation = caseStatusPresentation(item.status, language)
              return (
                <Link
                  key={item.caseId}
                  href={`/iaops/cases/${encodeURIComponent(item.caseId)}`}
                  style={{
                    display: 'block', padding: '14px 18px', borderRadius: '12px', textDecoration: 'none',
                    background: 'var(--surface)', border: '1px solid var(--border)',
                  }}
                >
                  <div style={{ display: 'flex', alignItems: 'center', gap: '10px', flexWrap: 'wrap' }}>
                    <span style={{
                      display: 'inline-flex', alignItems: 'center', gap: '5px',
                      fontSize: '11px', fontWeight: 700, padding: '3px 10px', borderRadius: '10px',
                      background: visual.bg, color: visual.fg,
                    }}>
                      <Icon size={12} />
                      {presentation.label}
                    </span>
                    <span style={{ fontFamily: 'var(--font-mono)', fontSize: '12px', fontWeight: 700, color: 'var(--text-primary)' }}>
                      {item.caseId}
                    </span>
                    {item.contestedRate > 0 && (
                      <span style={{ fontSize: '11px', fontWeight: 700, padding: '3px 8px', borderRadius: '10px', background: 'var(--warning-bg)', color: 'var(--warning-text)' }}>
                        {t(`${Math.round(item.contestedRate * 100)} % sporných`, `${Math.round(item.contestedRate * 100)}% contested`)}
                      </span>
                    )}
                    <span style={{ marginLeft: 'auto', fontSize: '11px', color: 'var(--text-tertiary)' }}>
                      {t(`${item.contributionCount} příspěvků`, `${item.contributionCount} contributions`)}
                    </span>
                  </div>
                  <div style={{ display: 'flex', flexWrap: 'wrap', gap: '14px', marginTop: '8px', fontSize: '11px', color: 'var(--text-tertiary)' }}>
                    <span>{t('Třída', 'Class')}: <strong style={{ color: 'var(--text-secondary)' }}>{item.caseClass}</strong></span>
                    <span>{t('Cíl dispozice', 'Disposition target')}: <strong style={{ color: 'var(--text-secondary)' }}>{item.dispositionTarget}</strong></span>
                    <span>{t('Otevřeno', 'Opened')}: <strong style={{ color: 'var(--text-secondary)' }}>{fmt(item.openedAtEpochMs)}</strong></span>
                    <span>{t('Deadline', 'Deadline')}: <strong style={{ color: 'var(--text-secondary)' }}>{fmt(item.deadlineAtEpochMs)}</strong></span>
                  </div>
                  <p style={{ margin: '8px 0 0', fontSize: '11px', color: 'var(--text-secondary)', lineHeight: 1.45 }}>{presentation.detail}</p>
                </Link>
              )
            })}
          </div>
          {cases.length >= limit && limit < MAX_LIMIT && (
            <button
              type="button"
              onClick={() => setLimit(Math.min(limit + PAGE_SIZE, MAX_LIMIT))}
              style={{
                marginTop: '14px', fontSize: '12px', fontWeight: 700, padding: '8px 18px', borderRadius: '10px',
                cursor: 'pointer', border: '1px solid var(--accent-border)',
                background: 'var(--accent-bg)', color: 'var(--accent-text)',
              }}
            >
              {t('Načíst další', 'Load more')}
            </button>
          )}
        </>
      )}
    </div>
  )
}
