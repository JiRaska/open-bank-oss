// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

import Link from 'next/link'
import { useMemo, useState } from 'react'
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

  const visible = register.obligations
    .filter(o => area === 'all' || o.area === area)
    .filter(o => status === 'all' || o.coverage.status === status)
    .sort((a, b) => COVERAGE_ORDER.indexOf(a.coverage.status) - COVERAGE_ORDER.indexOf(b.coverage.status))

  const name = (o: RegulatoryObligation) => (language === 'cs' ? o.name.cs : o.name.en)

  return (
    <div className="space-y-4">
      {header}

      <section aria-labelledby="obligations-context" className="card" style={{ padding: 16 }}>
        <h2 id="obligations-context" style={{ marginTop: 0 }}>{t('Jak stránku číst', 'How to read this page')}</h2>
        <p>
          {t(
            'Registr vychází ze zákona, ne z kódu. Každý řádek je jedna povinnost se zdrojem — zněním předpisu a specifikací úřadu — a teprve potom je k ní přiřazen výkaz platformy. Povinnost bez výkazu je proto vidět jako „Chybí“.',
            'The register starts from the law, not the code. Each row is one duty with its source — the legal text and the authority’s specification — and only then mapped to a platform report. A duty without a report therefore shows as “Missing”.',
          )}
        </p>
        <p>
          {t(
            'Lhůty nesou míru jistoty. „NEOVĚŘENO“ je stopa pro ověření, ne fakt — před podáním vždy ověřte aktuální znění předpisu. Charta agenta regulatory-obligations-watcher je navržena, ale je vypnutá, dokud nebude zapojen stahovač zdrojů — dnes zdroje nikdo automaticky nesleduje. Registr mění jen schválený PR.',
            'Deadlines carry a confidence. “UNVERIFIED” is a lead, not a fact — check the current text before filing. A regulatory-obligations-watcher agent charter is proposed but disabled until a source fetcher is wired — nothing watches the sources automatically today. Only a reviewed PR changes the register.',
          )}
        </p>
        <p style={{ marginBottom: 0 }}>
          <a href={DOC_URL} target="_blank" rel="noreferrer" className="btn btn-secondary">
            <ExternalLink size={13} aria-hidden="true" /> {t('Dokumentace registru', 'Register documentation')}
          </a>{' '}
          <Link href="/regulatory" className="btn btn-secondary">{t('Katalog výkazů', 'Report catalogue')}</Link>{' '}
          <Link href="/regulatory/statutory-returns" className="btn btn-secondary">{t('Statutární výkazy a termíny', 'Statutory returns and deadlines')}</Link>
        </p>
      </section>

      <div role="group" aria-label={t('Souhrn pokrytí', 'Coverage summary')} style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(150px, 1fr))', gap: 12 }}>
        {COVERAGE_ORDER.map(s => (
          <button
            key={s}
            type="button"
            aria-pressed={status === s}
            onClick={() => setStatus(status === s ? 'all' : s)}
            className="card"
            style={{ padding: 12, textAlign: 'left', background: STATUS_STYLE[s].bg, color: STATUS_STYLE[s].color, cursor: 'pointer' }}
          >
            <div style={{ fontSize: 24, fontWeight: 700 }} data-testid={`count-${s}`}>{counts[s]}</div>
            <div style={{ fontSize: 13 }}>{statusLabel[s]}</div>
          </button>
        ))}
      </div>

      <label style={{ display: 'inline-flex', gap: 8, alignItems: 'center' }}>
        {t('Oblast', 'Area')}
        <select value={area} onChange={e => setArea(e.target.value)} aria-label={t('Oblast', 'Area')}>
          <option value="all">{t('Všechny', 'All')}</option>
          {register.areas.map(a => (
            <option key={a.id} value={a.id}>{language === 'cs' ? a.cs : a.en}</option>
          ))}
        </select>
      </label>

      <ul style={{ listStyle: 'none', padding: 0, display: 'grid', gap: 12 }}>
        {visible.map(o => {
          const auth = authorities.get(o.authority)
          const law = trustedHttpsUrl(o.sources.law)
          const spec = trustedHttpsUrl(o.sources.spec)
          return (
            <li key={o.id} id={o.id} className="card" style={{ padding: 14 }}>
              <div style={{ display: 'flex', justifyContent: 'space-between', gap: 12, flexWrap: 'wrap' }}>
                <h3 style={{ margin: 0 }}>{name(o)}</h3>
                <span
                  data-testid={`status-${o.id}`}
                  style={{ padding: '2px 8px', borderRadius: 6, fontSize: 12, background: STATUS_STYLE[o.coverage.status].bg, color: STATUS_STYLE[o.coverage.status].color }}
                >
                  {statusLabel[o.coverage.status]}
                </span>
              </div>
              <dl style={{ display: 'grid', gridTemplateColumns: 'max-content 1fr', gap: '4px 12px', margin: '10px 0 0', fontSize: 14 }}>
                <dt>{t('Adresát', 'Authority')}</dt>
                <dd style={{ margin: 0 }}>{auth ? (language === 'cs' ? auth.cs : auth.en) : o.authority}</dd>
                <dt>{t('Právní základ', 'Legal basis')}</dt>
                <dd style={{ margin: 0 }}>{o.legalBasis}</dd>
                <dt>{t('Zdroj', 'Source')}</dt>
                <dd style={{ margin: 0 }}>
                  {law && <a href={law} target="_blank" rel="noreferrer">{t('Znění předpisu', 'Legal text')}</a>}
                  {spec && <>{' · '}<a href={spec} target="_blank" rel="noreferrer">{t('Specifikace úřadu', 'Authority specification')}</a></>}
                </dd>
                <dt>{t('Lhůta', 'Deadline')}</dt>
                <dd style={{ margin: 0 }}>{o.cadence} <em>({confidenceLabel[o.cadenceConfidence]})</em></dd>
                <dt>{t('Kanál', 'Channel')}</dt>
                <dd style={{ margin: 0 }}>{o.transport}</dd>
                {o.applicability !== 'always' && (
                  <>
                    <dt>{t('Použitelnost', 'Applies when')}</dt>
                    <dd style={{ margin: 0 }}>{o.applicability.replace(/^conditional:\s*/, '')}</dd>
                  </>
                )}
                <dt>{t('Pokrytí', 'Coverage')}</dt>
                <dd style={{ margin: 0 }}>
                  {o.coverage.reports.length > 0 && (
                    <span>
                      {t('Výkazy: ', 'Reports: ')}
                      {o.coverage.reports.map((r, i) => (
                        <span key={r}>{i > 0 && ', '}<Link href={`/regulatory#${r}`}>{r}</Link></span>
                      ))}
                    </span>
                  )}
                  {o.coverage.services.length > 0 && <span>{o.coverage.reports.length > 0 && ' · '}{t('Služby: ', 'Services: ')}{o.coverage.services.join(', ')}</span>}
                  {o.coverage.tracking && <span>{' · '}{t('Sleduje ', 'Tracked by ')}{o.coverage.tracking}</span>}
                  {o.coverage.reports.length === 0 && o.coverage.services.length === 0 && !o.coverage.tracking && '—'}
                </dd>
                {o.note && (
                  <>
                    <dt>{t('Poznámka', 'Note')}</dt>
                    <dd style={{ margin: 0 }}>{o.note}</dd>
                  </>
                )}
              </dl>
            </li>
          )
        })}
      </ul>
    </div>
  )
}
