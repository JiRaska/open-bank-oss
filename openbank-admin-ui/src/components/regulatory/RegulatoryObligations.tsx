// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

import Link from 'next/link'
import { useMemo, useState } from 'react'
import styles from './RegulatoryObligations.module.css'
import { ExternalLink, Scale } from 'lucide-react'
import { PageHeader } from '@/components/ui/PageHeader'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { trustedHttpsUrl } from '@/lib/security/trustedUrls'
// From `obligation-types`, NOT `obligations`: the latter imports `fs`.
import {
  COVERAGE_ORDER,
  type CadenceConfidence,
  type CoverageStatus,
  type ObligationRegister,
  type RegulatoryObligation,
} from '@/lib/regulatory/obligation-types'

const GENERATE_COMMAND = 'node scripts/generate-regulatory-obligations.mjs'
const DOC_URL = 'https://github.com/JiRaska/open-bank-oss/blob/main/docs/compliance/regulatory-obligations.md'

const STATUS_STYLE: Record<CoverageStatus, { color: string; bg: string }> = {
  missing: { color: 'var(--danger-text)', bg: 'var(--danger-bg)' },
  'data-only': { color: 'var(--warning-text)', bg: 'var(--warning-bg)' },
  'bff-only': { color: 'var(--warning-text)', bg: 'var(--warning-bg)' },
  'computed-preview': { color: 'var(--warning-text)', bg: 'var(--warning-bg)' },
  'filed-return': { color: 'var(--success-text)', bg: 'var(--success-bg)' },
  conditional: { color: 'var(--text-secondary)', bg: 'var(--surface-3)' },
  external: { color: 'var(--text-secondary)', bg: 'var(--surface-3)' },
}

/**
 * Regulatory → Obligations (ADR-0367).
 *
 * The screen answers "what does the bank owe which authority, under which law, and which of our
 * reports covers it". It starts from the LAW, so a duty with no implementation is a red row, not
 * an absence — which is how CEÚ and GSFT went unnoticed while the catalogue was the only view.
 */
export function RegulatoryObligations({ register }: { register: ObligationRegister }) {
  const { t, language } = useLanguage()
  const [area, setArea] = useState<string>('all')
  const [status, setStatus] = useState<CoverageStatus | 'all'>('all')
  const [query, setQuery] = useState('')
  const [authority, setAuthority] = useState('all')
  const [confidence, setConfidence] = useState<CadenceConfidence | 'all'>('all')
  const [page, setPage] = useState(0)
  const pageSize = 25
  const resetFilters = () => {
    setQuery(''); setArea('all'); setStatus('all'); setAuthority('all'); setConfidence('all'); setPage(0)
  }

  const statusLabel: Record<CoverageStatus, string> = {
    'filed-return': t('Výkaz počítá backend', 'Computed by a backend'),
    'computed-preview': t('Náhled z backendu, nelze podat', 'Backend preview, not fileable'),
    'bff-only': t('Výkaz počítá jen admin-ui', 'Computed in admin-ui only'),
    'data-only': t('Data ano, výkaz ne', 'Data exists, no return'),
    missing: t('Chybí', 'Missing'),
    external: t('Mimo platformu', 'Outside the platform'),
    conditional: t('Jen za podmínky', 'Conditional'),
  }
  const confidenceLabel: Record<CadenceConfidence, string> = {
    verified: t('ověřeno', 'verified'),
    'known-eu': t('EU text, znovu neověřeno', 'EU text, not re-checked'),
    unverified: t('NEOVĚŘENO — ověřte v předpisu', 'UNVERIFIED — check the law'),
  }

  const title = t('Regulatorní povinnosti', 'Regulatory obligations')
  const header = (
    <PageHeader
      title={title}
      subtitle={t(
        'Co banka komu vykazuje, podle jakého předpisu, a který výkaz platformy to pokrývá',
        'What the bank reports to whom, under which law, and which platform report covers it',
      )}
      icon={<Scale className="h-6 w-6 text-slate-500" />}
    />
  )

  const authorities = useMemo(() => new Map(register.authorities.map(a => [a.id, a])), [register.authorities])
  const counts = useMemo(() => {
    const c = Object.fromEntries(COVERAGE_ORDER.map(s => [s, 0])) as Record<CoverageStatus, number>
    for (const o of register.obligations) c[o.coverage.status] = (c[o.coverage.status] ?? 0) + 1
    return c
  }, [register.obligations])

  if (!register.available) {
    return (
      <div className="space-y-4">
        {header}
        <div role="alert" className="rounded border border-amber-200 bg-amber-50 p-4 text-sm text-amber-900">
          {t(
            'Registr povinností nebyl zabudován do tohoto buildu. Nejde o prázdný seznam povinností, ale o chybu buildu: spusťte',
            'The obligations register was not baked into this build. This is a build problem, not an empty list of duties: run',
          )}{' '}
          <code className="rounded bg-amber-100 px-1">{GENERATE_COMMAND}</code>{' '}
          {t('a build zopakujte.', 'and rebuild.')}
        </div>
      </div>
    )
  }

  const normalize = (value: string) => value.normalize('NFD').replace(/[\u0300-\u036f]/g, '').toLocaleLowerCase(language).trim()
  const search = normalize(query)
  const visible = register.obligations
    .filter(o => area === 'all' || o.area === area)
    .filter(o => status === 'all' || o.coverage.status === status)
    .filter(o => authority === 'all' || o.authority === authority)
    .filter(o => confidence === 'all' || o.cadenceConfidence === confidence)
    .filter(o => !search || normalize([o.id, o.name.cs, o.name.en, o.legalBasis, ...o.coverage.reports].join(' ')).includes(search))
    .sort((a, b) => COVERAGE_ORDER.indexOf(a.coverage.status) - COVERAGE_ORDER.indexOf(b.coverage.status))

  const pageCount = Math.max(1, Math.ceil(visible.length / pageSize))
  const currentPage = Math.min(page, pageCount - 1)
  const pageRows = visible.slice(currentPage * pageSize, (currentPage + 1) * pageSize)

  const name = (o: RegulatoryObligation) => (language === 'cs' ? o.name.cs : o.name.en)

  return (
    <div className="space-y-4">
      {header}

      <nav className={styles.links} aria-label={t('Regulatorní reporting', 'Regulatory reporting')}>
        <Link href="/regulatory" className="btn btn-secondary">{t('Katalog výkazů', 'Report catalogue')}</Link>
        <Link href="/regulatory/statutory-returns" className="btn btn-secondary">{t('Statutární výkazy a termíny', 'Statutory returns and deadlines')}</Link>
        <a href={DOC_URL} target="_blank" rel="noreferrer" className="btn btn-secondary">
          <ExternalLink size={13} aria-hidden="true" /> {t('Dokumentace registru', 'Register documentation')}
        </a>
      </nav>
      <p className={styles.context}>{t(
        'Jedna položka je povinnost, nikoli podaný výkaz. Pokrytí popisuje implementaci; použitelnost pro konkrétní subjekt a přijetí podání se ověřují samostatně.',
        'Each item is an obligation, not a filed return. Coverage describes implementation; applicability to an entity and acceptance of a filing are checked separately.',
      )}</p>
      <details className={styles.context}>
        <summary>{t('Jak číst ověření lhůt', 'How to read deadline verification')}</summary>
        <p>{t(
          'Neověřená lhůta není potvrzený termín. Před podáním ověřte aktuální metodiku a povinnost subjektu. Zdroje zatím nejsou sledovány automaticky; registr se mění schválenou změnou.',
          'An unverified cadence is not a confirmed deadline. Check the current methodology and entity obligation before filing. Sources are not yet monitored automatically; register changes require review.',
        )}</p>
      </details>

      <div role="group" aria-label={t('Souhrn pokrytí', 'Coverage summary')} className={styles.summary}>
        {COVERAGE_ORDER.map(s => (
          <button
            key={s}
            type="button"
            aria-pressed={status === s}
            onClick={() => { setStatus(status === s ? 'all' : s); setPage(0) }}
            className="btn btn-secondary"
          >
            <span data-testid={`count-${s}`}>{counts[s]}</span> {statusLabel[s]}
          </button>
        ))}
      </div>

      <div className={styles.filters}>
        <label>{t('Hledat povinnost', 'Search obligations')}
          <input type="search" className="input" value={query} onChange={e => { setQuery(e.target.value); setPage(0) }}
            placeholder={t('Název, kód nebo právní základ', 'Name, code or legal basis')} />
        </label>
        <label>{t('Oblast', 'Area')}
          <select className="input" value={area} onChange={e => { setArea(e.target.value); setPage(0) }}>
            <option value="all">{t('Všechny', 'All')}</option>
            {register.areas.map(a => <option key={a.id} value={a.id}>{language === 'cs' ? a.cs : a.en}</option>)}
          </select>
        </label>
        <label>{t('Adresát', 'Authority')}
          <select className="input" value={authority} onChange={e => { setAuthority(e.target.value); setPage(0) }}>
            <option value="all">{t('Všichni', 'All')}</option>
            {register.authorities.map(a => <option key={a.id} value={a.id}>{language === 'cs' ? a.cs : a.en}</option>)}
          </select>
        </label>
        <label>{t('Pokrytí', 'Coverage')}
          <select className="input" value={status} onChange={e => { setStatus(e.target.value as CoverageStatus | 'all'); setPage(0) }}>
            <option value="all">{t('Všechna pokrytí', 'All coverage states')}</option>
            {COVERAGE_ORDER.map(s => <option key={s} value={s}>{statusLabel[s]}</option>)}
          </select>
        </label>
        <label>{t('Ověření lhůty', 'Deadline verification')}
          <select className="input" value={confidence} onChange={e => { setConfidence(e.target.value as CadenceConfidence | 'all'); setPage(0) }}>
            <option value="all">{t('Všechny úrovně', 'All levels')}</option>
            {(['verified', 'known-eu', 'unverified'] as const).map(c => <option key={c} value={c}>{confidenceLabel[c]}</option>)}
          </select>
        </label>
        <button type="button" className="btn btn-secondary" onClick={resetFilters}>{t('Zrušit filtry', 'Clear filters')}</button>
      </div>
      <p role="status" className={styles.context}>
        {visible.length === 0 ? t('Žádná povinnost neodpovídá filtrům.', 'No obligation matches these filters.')
          : `${t('Nalezeno', 'Matched')}: ${visible.length} / ${register.obligations.length} · ${t('Strana', 'Page')} ${currentPage + 1} / ${pageCount}`}
      </p>
      <div className={styles.tableScroll}>
        <table className={`table ${styles.table}`} aria-label={title}>
          <thead><tr>
            <th scope="col">{t('Povinnost a zdroje', 'Obligation and sources')}</th>
            <th scope="col">{t('Adresát', 'Authority')}</th>
            <th scope="col">{t('Pokrytí', 'Coverage')}</th>
            <th scope="col">{t('Lhůta a ověření', 'Cadence and verification')}</th>
            <th scope="col">{t('Výkazy', 'Reports')}</th>
          </tr></thead>
          <tbody>{pageRows.map(o => {
            const auth = authorities.get(o.authority)
            const law = trustedHttpsUrl(o.sources.law)
            const spec = trustedHttpsUrl(o.sources.spec)
            return <tr key={o.id} id={o.id}>
              <td>
                <strong>{name(o)}</strong>
                <details className={styles.detail}>
                  <summary>{t('Právní základ a podrobnosti', 'Legal basis and details')}</summary>
                  <p>{o.legalBasis}</p>
                  <p>{law && <a href={law} target="_blank" rel="noreferrer">{t('Znění předpisu', 'Legal text')}</a>}
                    {spec && <> · <a href={spec} target="_blank" rel="noreferrer">{t('Specifikace úřadu', 'Authority specification')}</a></>}</p>
                  <p>{t('Kanál', 'Channel')}: {o.transport}</p>
                  <p>{t('Použitelnost', 'Applicability')}: {o.applicability === 'always' ? t('Obecná povinnost dle registru', 'General obligation in the register') : o.applicability.replace(/^conditional:\s*/, '')}</p>
                  {o.coverage.services.length > 0 && <p>{t('Služby', 'Services')}: {o.coverage.services.join(', ')}</p>}
                  {o.coverage.tracking && <p>{t('Sleduje', 'Tracked by')}: {o.coverage.tracking}</p>}
                  {o.note && <p>{o.note}</p>}
                </details>
              </td>
              <td>{auth ? (language === 'cs' ? auth.cs : auth.en) : o.authority}</td>
              <td><span data-testid={`status-${o.id}`} className={styles.coverage}
                style={{ background: STATUS_STYLE[o.coverage.status].bg, color: STATUS_STYLE[o.coverage.status].color }}>{statusLabel[o.coverage.status]}</span></td>
              <td>{o.cadence}<div className={styles.confidence}>{confidenceLabel[o.cadenceConfidence]}</div></td>
              <td>{o.coverage.reports.length === 0 ? '—' : o.coverage.reports.map(r => <div key={r}><Link href={`/regulatory#${r}`}>{r}</Link></div>)}</td>
            </tr>
          })}</tbody>
        </table>
      </div>
      {pageCount > 1 && <nav className={styles.links} aria-label={t('Stránkování povinností', 'Obligation pagination')}>
        <button type="button" className="btn btn-secondary" disabled={currentPage === 0} onClick={() => setPage(currentPage - 1)}>{t('Předchozí', 'Previous')}</button>
        <button type="button" className="btn btn-secondary" disabled={currentPage + 1 >= pageCount} onClick={() => setPage(currentPage + 1)}>{t('Další', 'Next')}</button>
      </nav>}
    </div>
  )
}
