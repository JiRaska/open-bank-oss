// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

import { useState, useEffect, useCallback } from 'react'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { classifyBffFailure, svcUrl } from '@/lib/services/bff'
import { DataUnavailable, type UnavailableKind } from '@/components/feedback/DataUnavailable'
import { ClipboardList, RefreshCw, ChevronRight, X, TrendingUp } from 'lucide-react'
import Link from 'next/link'
import { PageHeader } from '@/components/ui'
import { Can } from '@/components/auth/AuthGuard'

const SVC = 'onboarding-service'

// ── Types ─────────────────────────────────────────────────────────────────────

interface OnboardingRecord {
  partyId: string
  legalName: string | null
  email: string | null
  partyStatus: string
  kycCaseId: string | null
  kycStatus: string | null
  scaEnrolled: boolean
  deviceCount: number
  funnelStage: string
  blockedReason: string | null
  createdAt: string
  updatedAt: string
}

interface RecordPage {
  items: OnboardingRecord[]
  total: number
  page: number
  size: number
  stageFilter?: string
}

type FunnelCounts = Record<string, number>

// ── Stage display config ──────────────────────────────────────────────────────

const STAGES = [
  'REGISTERED',
  'KYC_OPEN',
  'KYC_UNDER_REVIEW',
  'SCA_PENDING',
  'ACTIVE',
  'BLOCKED',
] as const

type Stage = typeof STAGES[number]

const STAGE_LABEL_CS: Record<Stage, string> = {
  REGISTERED:               'Registrován',
  KYC_OPEN:                 'KYC otevřeno',
  KYC_UNDER_REVIEW:         'KYC — přezkoumání',
  SCA_PENDING:              'SCA čeká',
  ACTIVE:                   'Aktivní',
  BLOCKED:                  'Blokován',
}

const STAGE_LABEL_EN: Record<Stage, string> = {
  REGISTERED:               'Registered',
  KYC_OPEN:                 'KYC Open',
  KYC_UNDER_REVIEW:         'KYC Under Review',
  SCA_PENDING:              'SCA Pending',
  ACTIVE:                   'Active',
  BLOCKED:                  'Blocked',
}

const STAGE_COLOR: Record<Stage, string> = {
  REGISTERED:               'var(--text-muted)',
  KYC_OPEN:                 'var(--yellow)',
  KYC_UNDER_REVIEW:         'var(--accent)',
  SCA_PENDING:              '#a855f7',
  ACTIVE:                   'var(--green)',
  BLOCKED:                  'var(--red)',
}

// ── Main page ─────────────────────────────────────────────────────────────────

export default function OnboardingPage() {
  const { t, language } = useLanguage()
  const dateLocale = language === 'cs' ? 'cs-CZ' : 'en-GB'

  // funnel KPI — `counts` stays null until the first response lands, so an in-flight or
  // failed fetch is never rendered as an authoritative zero (issue #8233).
  const [counts, setCounts] = useState<FunnelCounts | null>(null)
  const [countsLoading, setCountsLoading] = useState(true)
  const [countsUnavail, setCountsUnavail] = useState<{ kind: UnavailableKind } | null>(null)

  // list
  const [records, setRecords] = useState<OnboardingRecord[]>([])
  const [total, setTotal] = useState(0)
  const [page, setPage] = useState(0)
  const [stage, setStage] = useState<Stage | ''>('')
  const [loading, setLoading] = useState(true)
  const [listUnavail, setListUnavail] = useState<{ kind: UnavailableKind } | null>(null)

  // drawer
  const [selected, setSelected] = useState<OnboardingRecord | null>(null)

  const stageLabel = useCallback((s: string) =>
    language === 'cs'
      ? (STAGE_LABEL_CS[s as Stage] ?? s)
      : (STAGE_LABEL_EN[s as Stage] ?? s),
    [language]
  )

  // ── Load funnel counts ──────────────────────────────────────────────────────

  const loadCounts = useCallback(async () => {
    setCountsLoading(true); setCountsUnavail(null)
    try {
      const res = await fetch(svcUrl(SVC, '/api/v1/onboarding/funnel'), { signal: AbortSignal.timeout(5000) })
      if (!res.ok) { setCountsUnavail({ kind: await classifyBffFailure(res) }); return }
      setCounts(await res.json())
    } catch {
      setCountsUnavail({ kind: 'unreachable' })
    } finally { setCountsLoading(false) }
  }, [])

  // ── Load records list ───────────────────────────────────────────────────────

  const loadRecords = useCallback(async (pg: number, stg: Stage | '') => {
    setLoading(true); setListUnavail(null)
    try {
      const query: Record<string, string> = { page: String(pg), size: '20' }
      if (stg) query.stage = stg
      const res = await fetch(svcUrl(SVC, '/api/v1/onboarding/records', query), { signal: AbortSignal.timeout(5000) })
      if (!res.ok) { setListUnavail({ kind: await classifyBffFailure(res) }); setRecords([]); return }
      const data: RecordPage = await res.json()
      setRecords(data.items ?? [])
      setTotal(data.total ?? 0)
    } catch {
      setListUnavail({ kind: 'unreachable' })
      setRecords([])
    } finally { setLoading(false) }
  }, [])

  const refresh = useCallback(() => {
    loadCounts()
    loadRecords(page, stage)
  }, [loadCounts, loadRecords, page, stage])

  useEffect(() => { loadCounts() }, [loadCounts])
  useEffect(() => { loadRecords(page, stage) }, [loadRecords, page, stage])

  const handleStageFilter = (s: Stage | '') => {
    setStage(s)
    setPage(0)
  }

  // ── Render ────────────────────────────────────────────────────────────────

  return (
    <div>
      <PageHeader
        title={t('Onboarding cockpit', 'Onboarding Cockpit')}
        subtitle={t('Přehled průběhu onboardingu zákazníků — fáze po fázi', 'Customer onboarding funnel — stage by stage')}
        icon={<ClipboardList size={18} aria-hidden="true" />}
        breadcrumb={<div className="breadcrumb"><span>OpenBank</span><span className="breadcrumb-sep">/</span><span className="breadcrumb-current">{t('Onboarding', 'Onboarding')}</span></div>}
        actions={<div style={{ display: 'flex', gap: '8px' }}>
          <Link href="/onboarding/analytics" className="btn btn-secondary" style={{ textDecoration: 'none' }}>
            <TrendingUp size={13} style={{ color: 'var(--accent)' }} />
            {t('Konverze', 'Conversion')}
          </Link>
          <button className="btn btn-secondary" type="button" onClick={refresh} disabled={loading || countsLoading}
            aria-busy={loading || countsLoading} aria-label={t('Obnovit onboarding', 'Refresh onboarding')}>
            <RefreshCw size={13} aria-hidden="true" style={{ animation: (loading || countsLoading) ? 'spin 1s linear infinite' : 'none' }} />
            {t('Obnovit', 'Refresh')}
          </button>
        </div>}
      />

      {/* KPI funnel tiles — never render a fabricated 0 while the count is unknown (#8233) */}
      {countsLoading ? (
        <div
          role="status"
          aria-live="polite"
          aria-busy="true"
          aria-label={t('Načítání počtů funnelu…', 'Loading funnel counts…')}
          style={{ display: 'grid', gridTemplateColumns: 'repeat(7, 1fr)', gap: '10px', marginBottom: '20px' }}
        >
          {STAGES.map(s => (
            <div key={s} style={{ border: '1px solid var(--border)', borderRadius: '8px', padding: '12px 8px', textAlign: 'center' }}>
              <div className="skeleton" style={{ height: '22px', width: '32px', margin: '0 auto' }} />
              <div style={{ fontSize: '10px', color: 'var(--text-muted)', marginTop: '8px', lineHeight: 1.3 }}>
                {stageLabel(s)}
              </div>
            </div>
          ))}
          <span className="sr-only">{t('Načítání počtů funnelu…', 'Loading funnel counts…')}</span>
        </div>
      ) : countsUnavail ? (
        <div className="card" style={{ padding: 0, marginBottom: '20px' }}>
          <DataUnavailable kind={countsUnavail.kind} service={t('Onboarding-service', 'Onboarding-service')} feature={t('Funnel počty', 'Funnel counts')} lang={language} dense />
        </div>
      ) : (
        <div role="group" aria-label={t('Filtr fází onboardingu', 'Onboarding stage filters')} style={{ display: 'grid', gridTemplateColumns: 'repeat(7, 1fr)', gap: '10px', marginBottom: '20px' }}>
          {STAGES.map(s => {
            const count = counts?.[s] ?? 0
            const isActive = stage === s
            const color = STAGE_COLOR[s]
            return (
              <button
                key={s}
                type="button"
                aria-pressed={isActive}
                onClick={() => handleStageFilter(isActive ? '' : s)}
                style={{
                  background: isActive ? `${color}18` : 'var(--surface)',
                  border: `1px solid ${isActive ? color : 'var(--border)'}`,
                  borderRadius: '8px',
                  padding: '12px 8px',
                  cursor: 'pointer',
                  textAlign: 'center',
                  transition: 'all 0.15s',
                }}
              >
                <div style={{ fontSize: '22px', fontWeight: 700, color, lineHeight: 1 }}>{count}</div>
                <div style={{ fontSize: '10px', color: 'var(--text-muted)', marginTop: '4px', lineHeight: 1.3 }}>
                  {stageLabel(s)}
                </div>
              </button>
            )
          })}
        </div>
      )}

      {/* Stage filter badge */}
      {stage && (
        <div style={{ display: 'flex', alignItems: 'center', gap: '8px', marginBottom: '12px' }}>
          <span style={{ fontSize: '12px', color: 'var(--text-muted)' }}>{t('Filtr:', 'Filter:')}</span>
          <span className="pill" style={{ background: `${STAGE_COLOR[stage]}22`, color: STAGE_COLOR[stage], display: 'flex', alignItems: 'center', gap: '4px' }}>
            {stageLabel(stage)}
            <button type="button"
              onClick={() => handleStageFilter('')}
              style={{ background: 'none', border: 'none', cursor: 'pointer', padding: 0, display: 'flex', color: 'inherit' }}
              aria-label={t('Zrušit filtr', 'Clear filter')}
            >
              <X size={11} aria-hidden="true" />
            </button>
          </span>
          <span style={{ fontSize: '12px', color: 'var(--text-muted)' }}>{t(`${total} záznamů`, `${total} records`)}</span>
        </div>
      )}

      {/* Records table */}
      {listUnavail ? (
        <div className="card" style={{ padding: 0 }}>
          <DataUnavailable kind={listUnavail.kind} service={t('Onboarding-service', 'Onboarding-service')} feature={t('Onboarding záznamy', 'Onboarding records')} lang={language} dense />
        </div>
      ) : (
        <div className="card" aria-busy={loading} style={{ overflow: 'hidden' }}>
          <table className="data-table">
            <thead>
              <tr>
                <th>{t('Jméno', 'Name')}</th>
                <th>{t('E-mail', 'Email')}</th>
                <th>{t('Fáze funnelu', 'Funnel Stage')}</th>
                <th>{t('Stav party', 'Party Status')}</th>
                <th>KYC</th>
                <th>SCA</th>
                <th>{t('Aktualizováno', 'Updated')}</th>
                <th></th>
              </tr>
            </thead>
            <tbody>
              {loading && Array.from({ length: 5 }).map((_, i) => (
                <tr key={i}>
                  {Array.from({ length: 8 }).map((_, j) => (
                    <td key={j}><div className="skeleton" style={{ height: '14px', width: j === 0 ? '140px' : '70px' }} /></td>
                  ))}
                </tr>
              ))}
              {!loading && records.length === 0 && (
                <tr>
                  <td colSpan={8} style={{ padding: 0 }}>
                    <DataUnavailable
                      kind="no_data"
                      feature={t('Onboarding záznamy', 'Onboarding records')}
                      lang={language}
                      detail={stage
                        ? t(`Žádné záznamy ve fázi ${stageLabel(stage)}.`, `No records in stage ${stageLabel(stage)}.`)
                        : t('Žádné onboarding záznamy.', 'No onboarding records yet.')}
                      dense
                    />
                  </td>
                </tr>
              )}
              {!loading && records.map(r => (
                <tr key={r.partyId} tabIndex={0} aria-label={t(`Vybrat onboarding subjekt ${r.legalName ?? r.partyId}`, `Select onboarding party ${r.legalName ?? r.partyId}`)} style={{ cursor: 'pointer' }} onClick={() => setSelected(r)}
                  onKeyDown={e => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); setSelected(r) } }}>
                  <td style={{ fontWeight: 500 }}>{r.legalName ?? <span style={{ color: 'var(--text-muted)', fontStyle: 'italic' }}>—</span>}</td>
                  <td style={{ fontFamily: 'var(--font-mono)', fontSize: '12px', color: 'var(--text-secondary)' }}>{r.email ?? '—'}</td>
                  <td>
                    <span className="pill" style={{ background: `${STAGE_COLOR[r.funnelStage as Stage] ?? 'var(--text-muted)'}22`, color: STAGE_COLOR[r.funnelStage as Stage] ?? 'var(--text-muted)' }}>
                      {stageLabel(r.funnelStage)}
                    </span>
                  </td>
                  <td><span className="tag">{r.partyStatus}</span></td>
                  <td>
                    {r.kycStatus
                      ? <span className="tag" style={{ fontSize: '10px' }}>{r.kycStatus.replace('_', ' ')}</span>
                      : <span style={{ color: 'var(--text-muted)' }}>—</span>}
                  </td>
                  <td>
                    {r.scaEnrolled
                      ? <span style={{ color: 'var(--green)', fontSize: '12px' }}>✓ {r.deviceCount}</span>
                      : <span style={{ color: 'var(--text-muted)', fontSize: '12px' }}>—</span>}
                  </td>
                  <td style={{ color: 'var(--text-muted)', fontSize: '12px' }}>{new Date(r.updatedAt).toLocaleDateString(dateLocale)}</td>
                  <td>
                    <span style={{ color: 'var(--accent)', display: 'flex', alignItems: 'center', gap: '2px', fontSize: '12px' }}>
                      {t('Detail', 'Detail')} <ChevronRight size={12} />
                    </span>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>

          {/* Pagination */}
          {total > 20 && (
            <div style={{ padding: '12px 20px', borderTop: '1px solid var(--border)', display: 'flex', gap: '8px', alignItems: 'center' }}>
              <button type="button" className="btn btn-secondary" aria-label={t('Předchozí strana onboardingu', 'Previous onboarding page')} disabled={page === 0} onClick={() => setPage(p => p - 1)}>
                {t('← Předchozí', '← Prev')}
              </button>
              <span style={{ fontSize: '12px', color: 'var(--text-muted)' }}>
                {t(`Strana ${page + 1} z ${Math.ceil(total / 20)}`, `Page ${page + 1} of ${Math.ceil(total / 20)}`)}
              </span>
              <button type="button" className="btn btn-secondary" aria-label={t('Další strana onboardingu', 'Next onboarding page')} disabled={(page + 1) * 20 >= total} onClick={() => setPage(p => p + 1)}>
                {t('Další →', 'Next →')}
              </button>
            </div>
          )}
        </div>
      )}

      {/* Detail drawer */}
      {selected && (
        <RecordDrawer record={selected} onClose={() => setSelected(null)} stageLabel={stageLabel} t={t} />
      )}
    </div>
  )
}

// ── Record detail drawer ──────────────────────────────────────────────────────

function RecordDrawer({
  record,
  onClose,
  stageLabel,
  t,
}: {
  record: OnboardingRecord
  onClose: () => void
  stageLabel: (s: string) => string
  t: (cs: string, en: string) => string
}) {
  const { language } = useLanguage()
  const dateLocale = language === 'cs' ? 'cs-CZ' : 'en-GB'
  const stageColor = STAGE_COLOR[record.funnelStage as Stage] ?? 'var(--text-muted)'

  return (
    <>
      {/* Backdrop */}
      <div
        onClick={onClose}
        style={{ position: 'fixed', inset: 0, background: 'rgba(0,0,0,0.4)', zIndex: 40 }}
      />
      {/* Drawer */}
      <div style={{
        position: 'fixed', top: 0, right: 0, bottom: 0, width: '420px',
        background: 'var(--surface)', borderLeft: '1px solid var(--border)',
        zIndex: 50, overflowY: 'auto', padding: '24px',
        boxShadow: '-4px 0 24px rgba(0,0,0,0.15)',
      }}>
        {/* Header */}
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', marginBottom: '20px' }}>
          <div>
            <h2 style={{ margin: 0, fontSize: '16px', fontWeight: 600 }}>{record.legalName ?? '—'}</h2>
            <div style={{ fontSize: '11px', color: 'var(--text-muted)', fontFamily: 'var(--font-mono)', marginTop: '4px' }}>
              {record.partyId}
            </div>
          </div>
          <button onClick={onClose} className="btn btn-secondary" style={{ padding: '4px 8px' }} aria-label={t('Zavřít', 'Close')}>
            <X size={14} />
          </button>
        </div>

        {/* Stage badge */}
        <div style={{ marginBottom: '20px' }}>
          <span className="pill" style={{ background: `${stageColor}22`, color: stageColor, fontSize: '13px', padding: '6px 12px' }}>
            {stageLabel(record.funnelStage)}
          </span>
          {record.blockedReason && (
            <div style={{ marginTop: '8px', fontSize: '12px', color: 'var(--red)', background: '#fee2e222', padding: '6px 10px', borderRadius: '6px' }}>
              {t('Důvod blokace:', 'Blocked reason:')} {record.blockedReason}
            </div>
          )}
        </div>

        {/* Details grid */}
        <div style={{ display: 'grid', gap: '12px' }}>
          <DrawerRow label={t('E-mail', 'Email')} value={record.email ?? '—'} mono />
          <DrawerRow label={t('Stav party', 'Party status')} value={record.partyStatus} />
          <DrawerRow label={t('Stav KYC', 'KYC status')} value={record.kycStatus?.replace('_', ' ') ?? '—'} />
          <DrawerRow label={t('KYC případ', 'KYC case ID')} value={record.kycCaseId ? record.kycCaseId.slice(0, 8) + '…' : '—'} mono />
          <DrawerRow label={t('SCA zapsáno', 'SCA enrolled')} value={record.scaEnrolled ? `✓ (${record.deviceCount} ${t('zařízení', 'device(s)')})` : '—'} />
          <DrawerRow label={t('Vytvořeno', 'Created')} value={new Date(record.createdAt).toLocaleString(dateLocale)} />
          <DrawerRow label={t('Aktualizováno', 'Updated')} value={new Date(record.updatedAt).toLocaleString(dateLocale)} />
        </div>

        {/* Links */}
        <div style={{ marginTop: '24px', display: 'flex', gap: '8px', flexWrap: 'wrap' }}>
          <Can permission="parties:view">
            <Link
              href={`/parties/${record.partyId}`}
              className="btn btn-secondary"
              style={{ textDecoration: 'none', fontSize: '12px' }}
            >
              {t('Otevřít party →', 'Open party →')}
            </Link>
          </Can>
          {record.kycCaseId && (
            <Link
              href={`/kyc`}
              className="btn btn-secondary"
              style={{ textDecoration: 'none', fontSize: '12px' }}
            >
              {t('KYC případy →', 'KYC cases →')}
            </Link>
          )}
        </div>
      </div>
    </>
  )
}

function DrawerRow({ label, value, mono }: { label: string; value: string; mono?: boolean }) {
  return (
    <div style={{ display: 'grid', gridTemplateColumns: '120px 1fr', gap: '8px', alignItems: 'start' }}>
      <span style={{ fontSize: '11px', color: 'var(--text-muted)', textTransform: 'uppercase', letterSpacing: '0.05em', paddingTop: '2px' }}>{label}</span>
      <span style={{ fontSize: '13px', fontFamily: mono ? 'var(--font-mono)' : undefined, wordBreak: 'break-all' }}>{value}</span>
    </div>
  )
}
