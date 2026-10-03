// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Shared pieces of the per-run analysis pages (IRRBB, liquidity forecast): a human description of
// the snapshot run instead of its raw id, and a curve-set picker that offers only the sets the run
// accepts (risk-engine refuses a set of another date) and says plainly what to do when none exist.

'use client'

import { useEffect, useState } from 'react'
import Link from 'next/link'
import { Copy } from 'lucide-react'
import { getJson, riskUrl } from './api'
import { curveSetListSchema, snapshotRunSchema, type CurveSetSummary } from './contracts'
import { curveSetLabel, formatDate, formatDateTime, runRequesterLabel } from './model'
import type { UnavailableKind } from '@/components/feedback/DataUnavailable'
import { useLanguage } from '@/lib/i18n/LanguageContext'

export type RunInfo = { asOf: string; recordedAt: string; positionCount: number; requestedBy?: string | null }

/**
 * The run's own summary. `run` is null while loading or when it cannot be read (the id is then
 * shown alone); `loaded` turns true either way, so dependent reads do not wait forever.
 */
export function useRun(id: string): { run: RunInfo | null; loaded: boolean } {
  const [state, setState] = useState<{ run: RunInfo | null; loaded: boolean }>({ run: null, loaded: false })
  useEffect(() => {
    let cancelled = false
    void (async () => {
      const res = await getJson(riskUrl(`/api/v1/risk/snapshots/${encodeURIComponent(id)}`), snapshotRunSchema)
      if (!cancelled) setState({ run: res.ok ? res.data : null, loaded: true })
    })()
    return () => { cancelled = true }
  }, [id])
  return state
}

/** "Snímek rozvahy k 30. 9. 2026 · zaznamenán … · 129 pozic · …", with the id as a small copyable reference. */
export function RunSubtitle({ id, run }: { id: string; run: RunInfo | null }) {
  const { t, language } = useLanguage()
  const [copied, setCopied] = useState(false)
  const copy = () => {
    void navigator.clipboard?.writeText(id).then(() => setCopied(true), () => undefined)
  }
  return (
    <span data-testid="run-subtitle">
      {run ? (
        <>
          {t(`Snímek rozvahy k ${formatDate(run.asOf, 'cs')}`, `Balance-sheet snapshot as of ${formatDate(run.asOf, 'en')}`)}
          {' · '}{t(`zaznamenán ${formatDateTime(run.recordedAt, 'cs')}`, `recorded ${formatDateTime(run.recordedAt, 'en')}`)}
          {' · '}{t(`${run.positionCount} pozic`, `${run.positionCount} positions`)}
          {' · '}{runRequesterLabel(run.requestedBy, language)}
        </>
      ) : t('Snímek rozvahy', 'Balance-sheet snapshot')}
      {' '}
      <button
        type="button"
        onClick={copy}
        title={t('Zkopírovat ID běhu', 'Copy run ID')}
        aria-label={t('Zkopírovat ID běhu', 'Copy run ID')}
        style={{ fontSize: 11, color: 'var(--text-secondary)', background: 'none', border: 'none', padding: 0, cursor: 'pointer', display: 'inline-flex', alignItems: 'center', gap: 3 }}
      >
        <code>{t('ID', 'ID')} {id.slice(0, 8)}…</code>
        <Copy size={11} aria-hidden="true" />
        {copied && <span>{t('zkopírováno', 'copied')}</span>}
      </button>
    </span>
  )
}

/**
 * The curve sets this run can use: as of the run's own date when the run is known, otherwise the
 * newest ones (the engine still refuses a mismatched date, and the page shows that refusal).
 */
export function useCurveSets(run: RunInfo | null, runLoaded: boolean) {
  const [sets, setSets] = useState<CurveSetSummary[] | null>(null)
  const [kind, setKind] = useState<UnavailableKind | null>(null)
  useEffect(() => {
    if (!runLoaded) return
    let cancelled = false
    void (async () => {
      const query: Record<string, string> = { limit: '25' }
      if (run) query.asOf = run.asOf
      const res = await getJson(riskUrl('/api/v1/risk/curve-sets', query), curveSetListSchema)
      if (cancelled) return
      if (res.ok) { setSets(res.data.curveSets); setKind(null) } else { setSets(null); setKind(res.kind) }
    })()
    return () => { cancelled = true }
  }, [run, runLoaded])
  return { sets, kind }
}

/** A select labelled by currency + date + source; disabled with a call to action when empty. */
export function CurveSetPicker({ sets, value, onChange, asOf }: {
  sets: CurveSetSummary[]
  value: string
  onChange: (id: string) => void
  asOf: string | null
}) {
  const { t, language } = useLanguage()
  const empty = sets.length === 0
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 4, fontSize: 12 }}>
      <label style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
        {t('Sada výnosových křivek', 'Yield-curve set')}
        <select
          className="input"
          value={value}
          disabled={empty}
          onChange={e => onChange(e.target.value)}
          aria-label={t('Sada výnosových křivek', 'Yield-curve set')}
        >
          {empty && <option value="">{t('Žádná sada k dispozici', 'No curve set available')}</option>}
          {sets.map(s => <option key={s.id} value={s.id}>{curveSetLabel(s, language)}</option>)}
        </select>
      </label>
      {empty && (
        <span data-testid="curve-set-cta" style={{ color: 'var(--text-secondary)' }}>
          {asOf
            ? t(`Pro datum ${formatDate(asOf, 'cs')} zatím neexistuje žádná sada křivek. `, `There is no curve set for ${formatDate(asOf, 'en')} yet. `)
            : t('Zatím neexistuje žádná sada křivek. ', 'There is no curve set yet. ')}
          <Link href="/balance-sheet/curve-sets">{t('Nahrát sadu v sekci Výnosové křivky', 'Upload one under Curve sets')}</Link>
        </span>
      )}
    </div>
  )
}
