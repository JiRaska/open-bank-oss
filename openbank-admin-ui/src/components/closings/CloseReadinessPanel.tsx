// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

'use client'

import { useEffect, useState } from 'react'
import { DataUnavailable } from '@/components/feedback/DataUnavailable'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { classifyBffFailure, type BffFailure } from '@/lib/services/bff'
import { fetchCloseReadiness } from '@/lib/closings/evidence'

interface Readiness {
  month: string
  eligibleForEnforcement: boolean
  observedDays: number
  requiredDays: number
  blockers: string[]
  days: { businessDate: string; blockers: string[] }[]
}

export function parseCloseReadiness(raw: unknown): Readiness | null {
  if (!raw || typeof raw !== 'object') return null
  const r = raw as Record<string, unknown>
  if (typeof r.month !== 'string' || !/^\d{4}-\d{2}$/.test(r.month)
    || typeof r.eligibleForEnforcement !== 'boolean'
    || !Number.isInteger(r.observedDays) || !Number.isInteger(r.requiredDays)
    || !Array.isArray(r.blockers) || !r.blockers.every(b => typeof b === 'string')
    || !Array.isArray(r.days)) return null
  const days: Readiness['days'] = []
  for (const day of r.days) {
    if (!day || typeof day !== 'object' || typeof day.businessDate !== 'string'
      || !Array.isArray(day.blockers) || !day.blockers.every((b: unknown) => typeof b === 'string')) return null
    days.push({ businessDate: day.businessDate, blockers: day.blockers })
  }
  const observedDays = r.observedDays as number
  const requiredDays = r.requiredDays as number
  if (requiredDays < 28 || requiredDays > 31 || observedDays < 0 || observedDays > requiredDays
    || days.length !== requiredDays || new Set(days.map(d => d.businessDate)).size !== requiredDays) return null
  if (r.eligibleForEnforcement && (observedDays !== requiredDays || r.blockers.length > 0 || days.some(d => d.blockers.length))) return null
  return { month: r.month, eligibleForEnforcement: r.eligibleForEnforcement, observedDays, requiredDays, blockers: r.blockers, days }
}

/** The ledger selects the last completed month in its accounting timezone. */
export function CloseReadinessPanel() {
  const { t, language } = useLanguage()
  const [data, setData] = useState<Readiness | null>(null)
  const [failure, setFailure] = useState<BffFailure | null>(null)
  const [refresh, setRefresh] = useState(0)

  useEffect(() => {
    const abort = new AbortController()
    const load = async () => {
      try {
        const response = await fetchCloseReadiness(AbortSignal.any([abort.signal, AbortSignal.timeout(8000)]))
        if (abort.signal.aborted) return
        if (!response.ok) {
          setData(null)
          setFailure(await classifyBffFailure(response))
          return
        }
        const parsed = parseCloseReadiness(await response.json())
        if (abort.signal.aborted) return
        setData(parsed)
        setFailure(parsed ? null : 'error')
      } catch {
        if (!abort.signal.aborted) { setData(null); setFailure('unreachable') }
      }
    }
    void load()
    return () => abort.abort()
  }, [refresh])

  const explain = (code: string): string => {
    const [kind, name, job] = code.split(':')
    switch (kind) {
      case 'NO_CLOSE_EVIDENCE': return t('Chybí důkaz z okamžiku uzavření', 'No evidence captured at close')
      case 'MISSING': return t(`Chybělo hlášení: ${name}`, `Missing report: ${name}`)
      case 'REFUSED': return t(`Nezaúčtovaná práce: ${name}`, `Unposted work: ${name}`)
      case 'WAIVED': return t(`Operátorská výjimka: ${name}`, `Operator waiver: ${name}`)
      case 'NO_SUCCESSFUL_JOB': return t(`Chybí doložené zpracování: ${name} / ${job}`, `No evidenced processing: ${name} / ${job}`)
      case 'NO_POST_CUTOFF_TIEOUT': return t('Chybí úspěšný tie-out po cutoffu', 'No successful post-cutoff tie-out')
      case 'JOURNALS_CHANGED_AFTER_CLOSE': return t('Účetní zápisy se po uzavření změnily', 'Accounting entries changed after close')
      case 'SOURCE_EVIDENCE_CHANGED_AFTER_CLOSE': return t('Účastník po uzavření změnil podklady', 'Participant evidence changed after close')
      case 'JOB_REQUIREMENTS_INCOMPLETE': return t('Nejsou úplně deklarovány požadované běhy', 'Required job declaration is incomplete')
      case 'JOB_REQUIREMENTS_CHANGED': return t('Změnily se požadované běhy', 'Required jobs changed')
      case 'CHECK_DISABLED': return t('Kontrola byla vypnuta', 'Check was disabled')
      case 'PARTICIPANTS_CHANGED': case 'PRINCIPAL_CHANGED': return t('Změnila se deklarace účastníků', 'Participant declaration changed')
      case 'NO_DECLARED_PARTICIPANTS': return t('Nejsou deklarováni účastníci', 'No participants declared')
      case 'STALE_PERIOD': return t('Důkaz se týká staršího měsíce', 'Evidence is from an older month')
      case 'INCOMPLETE_DAILY_EVIDENCE': return t('Denní důkazy nejsou úplné', 'Daily evidence is incomplete')
      default: return t('Důkaz vyžaduje kontrolu', 'Evidence needs review')
    }
  }

  return (
    <section aria-labelledby="close-readiness-heading" style={{ marginTop: '24px', padding: '16px', background: 'var(--surface)', border: '1px solid var(--border)', borderRadius: 'var(--r-lg)' }}>
      <h2 id="close-readiness-heading" style={{ margin: '0 0 12px', fontSize: '15px', fontWeight: 700 }}>{t('Měsíční důkaz úplnosti uzávěrek', 'Monthly close completeness evidence')}</h2>
      <p style={{ color: 'var(--text-muted)', fontSize: '13px', marginBottom: '12px' }}>{t('Důkaz platí pro účastníky těchto účetních knih. Penzijní společnost a fondy vyžadují vlastní účetní pokrytí.',
        'Evidence covers participants of these books. The pension company and funds require their own accounting coverage.')}</p>
      <button type="button" style={{ padding: '6px 12px', border: '1px solid var(--border)', borderRadius: 'var(--r-sm)', marginBottom: '12px', cursor: 'pointer' }} onClick={() => { setData(null); setFailure(null); setRefresh(n => n + 1) }}>
        {t('Obnovit měsíční důkaz', 'Refresh monthly evidence')}
      </button>
      {failure ? <DataUnavailable kind={failure} service="Ledger-service" feature={t('Měsíční důkaz', 'Monthly evidence')} lang={language} dense />
        : !data ? <p>{t('Načítání důkazu…', 'Loading evidence…')}</p>
          : <div>
            <p>{data.month}: {data.observedDays} / {data.requiredDays} {t('dní s uloženým důkazem', 'days with stored evidence')}</p>
            <p style={{ margin: '12px 0', fontWeight: 600, color: data.eligibleForEnforcement ? 'var(--success-text)' : 'var(--danger-text)' }}>{data.eligibleForEnforcement
              ? t('Měsíční důkaz je úplný. Zapnutí blokování neúplné uzávěrky vyžaduje samostatné schválení.',
                'Monthly evidence is complete. Activating incomplete-close blocking requires separate approval.')
              : t('Měsíční důkaz zatím neumožňuje schválit blokování neúplné uzávěrky.',
                'Monthly evidence does not yet support activating incomplete-close blocking.')}</p>
            <p style={{ fontSize: '13px', color: 'var(--text-muted)', marginBottom: '12px' }}>{t('Pozdní hlášení zpětně neopraví důkaz. Výjimka umožní uzavřít den, ale sama nepotvrdí připravenost kontroly.',
              'Late reports cannot repair historical evidence. A waiver permits a day to close but does not establish readiness.')}</p>
            {data.blockers.length > 0 && <ul style={{ paddingLeft: '20px', listStyle: 'disc', marginBottom: '12px' }}>{data.blockers.map(code => <li key={code}>{explain(code)}</li>)}</ul>}
            <details>
              <summary style={{ cursor: 'pointer', fontWeight: 600 }}>{t('Důkazy za jednotlivé dny', 'Daily evidence')}</summary>
              <ul style={{ paddingLeft: '20px', marginTop: '8px', fontSize: '13px', listStyle: 'disc' }}>{data.days.map(day => <li key={day.businessDate}>{day.businessDate}: {day.blockers.length
                ? day.blockers.map(explain).join('; ') : t('Úplné', 'Complete')}</li>)}</ul>
            </details>
          </div>}
    </section>
  )
}
