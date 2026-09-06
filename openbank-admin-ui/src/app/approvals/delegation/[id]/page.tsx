// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

import { useCallback, useEffect, useState } from 'react'
import Link from 'next/link'
import { useParams } from 'next/navigation'
import { ArrowLeft, CheckCircle2, Clock3, FileCheck2, RefreshCw, ShieldCheck, XCircle } from 'lucide-react'
import { AuthGuard } from '@/components/auth/AuthGuard'
import { DataUnavailable, type UnavailableKind } from '@/components/feedback/DataUnavailable'
import { PageHeader } from '@/components/ui/PageHeader'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { classifyBffFailure } from '@/lib/services/bff'

type LifecycleApproval = {
  id: string
  delegationId: string
  operation: 'SUSPEND' | 'REINSTATE' | 'REVOKE'
  requestedReason: string
  state: 'PROPOSED' | 'REJECTED' | 'EXECUTED'
  proposedBy: string
  proposedAt: string
  decidedBy: string | null
  decidedAt: string | null
  decisionReason: string | null
  executedAt: string | null
}

const STATUS = {
  PROPOSED: { color: '#b45309', bg: '#fffbeb', border: '#fcd34d', Icon: Clock3, cs: 'Čeká', en: 'Pending' },
  REJECTED: { color: '#b91c1c', bg: '#fef2f2', border: '#fecaca', Icon: XCircle, cs: 'Zamítnuto', en: 'Rejected' },
  EXECUTED: { color: '#047857', bg: '#ecfdf5', border: '#a7f3d0', Icon: CheckCircle2, cs: 'Provedeno', en: 'Executed' },
} as const

export default function DelegationApprovalDetailPage() {
  const { t, language } = useLanguage()
  const params = useParams<{ id: string }>()
  const id = params?.id
  const [approval, setApproval] = useState<LifecycleApproval | null>(null)
  const [loading, setLoading] = useState(true)
  const [unavailable, setUnavailable] = useState<UnavailableKind | null>(null)

  const load = useCallback(async () => {
    if (!id) return
    setLoading(true)
    setUnavailable(null)
    try {
      const response = await fetch(`/api/delegations/approvals/${id}`, {
        cache: 'no-store',
        signal: AbortSignal.timeout(8000),
      })
      if (!response.ok) {
        setApproval(null)
        setUnavailable(await classifyBffFailure(response))
        return
      }
      setApproval((await response.json()) as LifecycleApproval)
    } catch {
      setApproval(null)
      setUnavailable('unreachable')
    } finally {
      setLoading(false)
    }
  }, [id])

  useEffect(() => { void load() }, [load])

  const dateLocale = language === 'cs' ? 'cs-CZ' : 'en-GB'
  const formatTime = (value: string | null) => value
    ? new Date(value).toLocaleString(dateLocale, { dateStyle: 'medium', timeStyle: 'short' })
    : '—'

  return <AuthGuard permission="approvals:view">
    <div>
      <PageHeader
        breadcrumb={<div className="breadcrumb"><Link href="/approvals">{t('Schvalování', 'Approvals')}</Link><span className="breadcrumb-sep">/</span><span className="breadcrumb-current">{t('Delegace', 'Delegation')}</span></div>}
        icon={<FileCheck2 size={18} aria-hidden="true" />}
        title={t('Detail schválení delegace', 'Delegation approval detail')}
        subtitle={t(
          'Neměnná stopa návrhu, nezávislého rozhodnutí a případného provedení.',
          'Immutable evidence of the proposal, independent decision and any execution.',
        )}
        actions={<Link href="/approvals" className="btn btn-secondary"><ArrowLeft size={14} aria-hidden="true" />{t('Zpět do fronty', 'Back to queue')}</Link>}
      />

      {loading && <div className="card" role="status" aria-live="polite" style={{ padding: 24, marginTop: 16, color: 'var(--text-secondary)', display: 'flex', alignItems: 'center', gap: 10 }}>
        <RefreshCw size={16} aria-hidden="true" className="animate-spin" />
        {t('Načítám schvalovací stopu…', 'Loading approval evidence…')}
      </div>}

      {!loading && unavailable && <div className="card" style={{ marginTop: 16 }}>
        <DataUnavailable
          kind={unavailable}
          service="delegation-service"
          feature={t('schvalovací stopa delegace', 'delegation approval evidence')}
          lang={language}
        >
          <button type="button" className="btn btn-secondary" onClick={() => void load()}>
            <RefreshCw size={14} aria-hidden="true" />{t('Zkusit znovu', 'Retry')}
          </button>
        </DataUnavailable>
      </div>}

      {!loading && approval && <>
        <section className="card" aria-labelledby="approval-summary" style={{ padding: 18, marginTop: 16 }}>
          <div style={{ display: 'flex', justifyContent: 'space-between', gap: 12, alignItems: 'flex-start', flexWrap: 'wrap' }}>
            <div>
              <h2 id="approval-summary" style={{ fontSize: 16, margin: 0 }}>{operationLabel(approval.operation, language)}</h2>
              <div className="mono" style={{ fontSize: 11, color: 'var(--text-tertiary)', marginTop: 4 }}>{approval.id}</div>
            </div>
            <StatusBadge state={approval.state} lang={language} />
          </div>
          <p style={{ margin: '14px 0 0', fontSize: 13, color: 'var(--text-secondary)', lineHeight: 1.55 }}>
            {t(
              'Tato obrazovka je pouze pro čtení. Rozhodnutí ani změnu delegace z admin portálu neposílá.',
              'This screen is read-only. It cannot submit a decision or change the delegation.',
            )}
          </p>
          <p style={{ margin: '6px 0 0', fontSize: 12, color: 'var(--text-tertiary)', lineHeight: 1.5 }}>
            {t(
              'Tento záznam sám nepotvrzuje doručení změny do produktových projekcí; jejich stav ověřte v auditní časové ose delegace.',
              'This record alone does not prove delivery to product projections; verify their state in the delegation audit timeline.',
            )}
          </p>
          <dl style={{ display: 'grid', gridTemplateColumns: 'minmax(150px, 220px) 1fr', gap: '10px 16px', margin: '18px 0 0', fontSize: 13 }}>
            <dt style={{ color: 'var(--text-tertiary)' }}>{t('Delegace', 'Delegation')}</dt>
            <dd><Link href={`/delegations/${approval.delegationId}`} className="mono">{approval.delegationId}</Link></dd>
            <dt style={{ color: 'var(--text-tertiary)' }}>{t('Důvod zásahu', 'Action reason')}</dt>
            <dd>{approval.requestedReason}</dd>
            <dt style={{ color: 'var(--text-tertiary)' }}>{t('Navrhl', 'Proposed by')}</dt>
            <dd>{approval.proposedBy}</dd>
            <dt style={{ color: 'var(--text-tertiary)' }}>{t('Navrženo', 'Proposed at')}</dt>
            <dd><time dateTime={approval.proposedAt}>{formatTime(approval.proposedAt)}</time></dd>
            <dt style={{ color: 'var(--text-tertiary)' }}>{t('Rozhodl', 'Decided by')}</dt>
            <dd>{approval.decidedBy ?? '—'}</dd>
            <dt style={{ color: 'var(--text-tertiary)' }}>{t('Důvod rozhodnutí', 'Decision reason')}</dt>
            <dd>{approval.decisionReason ?? '—'}</dd>
          </dl>
        </section>

        <section className="card" aria-labelledby="approval-timeline" style={{ padding: 18, marginTop: 16 }}>
          <h2 id="approval-timeline" style={{ fontSize: 15, margin: 0, display: 'flex', alignItems: 'center', gap: 7 }}>
            <ShieldCheck size={16} aria-hidden="true" color="var(--accent)" />
            {t('Časová osa důkazů', 'Evidence timeline')}
          </h2>
          <ol style={{ listStyle: 'none', margin: '16px 0 0', padding: 0, display: 'grid', gap: 12 }}>
            <TimelineItem title={t('Návrh zaznamenán', 'Proposal recorded')} actor={approval.proposedBy} at={approval.proposedAt} format={formatTime} />
            <TimelineItem
              title={approval.state === 'PROPOSED' ? t('Čeká na jinou osobu', 'Waiting for a different person') : t('Nezávislé rozhodnutí', 'Independent decision')}
              actor={approval.decidedBy}
              at={approval.decidedAt}
              format={formatTime}
              pending={approval.state === 'PROPOSED'}
            />
            <TimelineItem
              title={executionLabel(approval.state, language)}
              actor={null}
              at={approval.executedAt}
              format={formatTime}
              pending={approval.state === 'PROPOSED'}
            />
          </ol>
        </section>
      </>}
    </div>
  </AuthGuard>
}

function StatusBadge({ state, lang }: { state: LifecycleApproval['state']; lang: 'cs' | 'en' }) {
  const meta = STATUS[state]
  return <span style={{ color: meta.color, background: meta.bg, border: `1px solid ${meta.border}`, borderRadius: 999, padding: '4px 9px', display: 'inline-flex', alignItems: 'center', gap: 5, fontSize: 11, fontWeight: 750 }}>
    <meta.Icon size={12} aria-hidden="true" />{lang === 'cs' ? meta.cs : meta.en}
  </span>
}

function TimelineItem({ title, actor, at, format, pending = false }: {
  title: string
  actor: string | null
  at: string | null
  format: (value: string | null) => string
  pending?: boolean
}) {
  return <li style={{ display: 'grid', gridTemplateColumns: '18px 1fr', gap: 10, color: pending ? 'var(--text-tertiary)' : 'var(--text-primary)' }}>
    <span aria-hidden="true" style={{ width: 10, height: 10, borderRadius: 999, marginTop: 4, background: pending ? 'var(--border)' : 'var(--accent)' }} />
    <div>
      <div style={{ fontSize: 13, fontWeight: 650 }}>{title}</div>
      <div style={{ fontSize: 11, color: 'var(--text-tertiary)', marginTop: 2 }}>
        {actor ? `${actor} · ` : ''}{at ? <time dateTime={at}>{format(at)}</time> : '—'}
      </div>
    </div>
  </li>
}

function operationLabel(operation: LifecycleApproval['operation'], lang: 'cs' | 'en') {
  const labels = {
    SUSPEND: { cs: 'Pozastavení delegace', en: 'Suspend delegation' },
    REINSTATE: { cs: 'Obnovení delegace', en: 'Reinstate delegation' },
    REVOKE: { cs: 'Odvolání delegace', en: 'Revoke delegation' },
  }
  return labels[operation][lang]
}

function executionLabel(state: LifecycleApproval['state'], lang: 'cs' | 'en') {
  const labels = {
    PROPOSED: {
      cs: 'Nic se neprovedlo — návrh čeká',
      en: 'Nothing executed — proposal pending',
    },
    REJECTED: {
      cs: 'Zásah se neprovedl',
      en: 'Action was not executed',
    },
    EXECUTED: {
      cs: 'Autoritativní změna zaznamenána',
      en: 'Authoritative transition recorded',
    },
  }
  return labels[state][lang]
}
