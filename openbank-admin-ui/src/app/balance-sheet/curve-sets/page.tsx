// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Yield-curve sets (risk-engine, ADR-0313 D4; admin console #10618).
//
// The risk department uploads money-market quotes per index and tenor; risk-engine bootstraps them
// into zero curves. A curve set is an INPUT to every PV and projection, so who supplied it must be
// a person: OPA refuses every service account, and only ROLE_RISK / ROLE_ADMIN see the form.
// Provenance is chosen explicitly on every upload — there is no default that could quietly label
// synthetic quotes as production (ADR-0313 D13).

'use client'

import { useCallback, useEffect, useMemo, useState } from 'react'
import Link from 'next/link'
import { useSession } from 'next-auth/react'
import { RefreshCw, TrendingUp as CurveIcon } from 'lucide-react'
import { AuthGuard } from '@/components/auth/AuthGuard'
import { DataUnavailable, type UnavailableKind } from '@/components/feedback/DataUnavailable'
import { PageHeader } from '@/components/ui'
import { ProvenanceBadge } from '@/components/balance-sheet/ProvenanceBadge'
import { getJson, riskUrl, sendJson } from '@/components/balance-sheet/api'
import { curveSetListSchema, curveSetSchema, type CurveSetSummary } from '@/components/balance-sheet/contracts'
import { CURVE_INDICES, isIsoDate, parseQuotes } from '@/components/balance-sheet/model'
import { hasPermission } from '@/lib/auth/roles'
import { useLanguage } from '@/lib/i18n/LanguageContext'

const PAGE_SIZE = 25
const EXAMPLE = 'CZEONIA ON 3.50\nCZEONIA 3M 3.60\nCZEONIA 1Y 3.80'

export default function CurveSetsPage() {
  return (
    <AuthGuard permission="balance-sheet:view">
      <CurveSets />
    </AuthGuard>
  )
}

function CurveSets() {
  const { t, language } = useLanguage()
  const { data: session } = useSession()
  const canUpload = hasPermission(session?.user?.roles ?? [], 'balance-sheet:curves:upload')
  const locale = language === 'cs' ? 'cs-CZ' : 'en-GB'
  const [sets, setSets] = useState<CurveSetSummary[] | null>(null)
  const [unavailable, setUnavailable] = useState<{ kind: UnavailableKind } | null>(null)
  const [asOf, setAsOf] = useState('')
  const [provenance, setProvenance] = useState<'' | 'synthetic' | 'production'>('')
  const [source, setSource] = useState('')
  const [quotes, setQuotes] = useState('')
  const [busy, setBusy] = useState(false)
  const [notice, setNotice] = useState<{ tone: 'success' | 'danger'; text: string; id?: string } | null>(null)

  const parsed = useMemo(() => (quotes.trim() ? parseQuotes(quotes) : null), [quotes])

  const load = useCallback(async () => {
    const res = await getJson(riskUrl('/api/v1/risk/curve-sets', { limit: String(PAGE_SIZE) }), curveSetListSchema)
    if (!res.ok) { setUnavailable({ kind: res.kind }); setSets(null); return }
    setUnavailable(null)
    setSets(res.data.curveSets)
  }, [])

  useEffect(() => { void load() }, [load])

  const lineError = (code: string) => ({
    format: t('očekáváno „INDEX TENOR SAZBA“', 'expected "INDEX TENOR RATE"'),
    index: t(`neznámý index (povolené: ${CURVE_INDICES.join(', ')})`, `unknown index (allowed: ${CURVE_INDICES.join(', ')})`),
    tenor: t('tenor musí být ON nebo číslo s D/W/M/Y', 'tenor must be ON or a number with D/W/M/Y'),
    rate: t('sazba musí být číslo v procentech', 'rate must be a number in percent'),
    duplicate: t('duplicitní tenor pro stejný index', 'duplicate tenor for the same index'),
    empty: t('žádná kotace', 'no quotes'),
  } as Record<string, string>)[code] ?? code

  const upload = async () => {
    if (!isIsoDate(asOf) || !provenance || !source.trim() || !parsed?.ok) return
    setBusy(true)
    setNotice(null)
    const res = await sendJson(
      riskUrl('/api/v1/risk/curve-sets'),
      { asOf, provenance, source: source.trim(), curves: parsed.curves },
      curveSetSchema,
    )
    setBusy(false)
    if (res.ok) {
      setNotice({ tone: 'success', id: res.data.id, text: t('Sada křivek uložena.', 'Curve set stored.') })
      setQuotes('')
      void load()
      return
    }
    setNotice({
      tone: 'danger',
      text: res.kind === 'forbidden'
        ? t('Sadu křivek může nahrát jen útvar rizik (ROLE_RISK) nebo administrátor.', 'Only the risk department (ROLE_RISK) or an administrator may upload a curve set.')
        : res.kind === 'refused'
          ? t(`risk-engine sadu odmítl: ${res.message ?? ''}`, `risk-engine refused the set: ${res.message ?? ''}`)
          : t('risk-engine je nedostupný.', 'risk-engine is unavailable.'),
    })
  }

  const ready = isIsoDate(asOf) && provenance !== '' && source.trim().length > 0 && parsed?.ok === true

  return (
    <div>
      <PageHeader
        title={t('Výnosové křivky', 'Curve sets')}
        subtitle={t('Kotace peněžního trhu převedené na nulové křivky (ADR-0313 D4).', 'Money-market quotes bootstrapped into zero curves (ADR-0313 D4).')}
        icon={<CurveIcon size={20} aria-hidden="true" />}
        actions={
          <button type="button" className="btn btn-secondary btn-sm" onClick={() => void load()} aria-label={t('Obnovit', 'Refresh')}>
            <RefreshCw size={14} aria-hidden="true" />
          </button>
        }
      />

      {canUpload && (
        <div className="card" style={{ marginBottom: 16 }}>
          <h2 style={{ fontSize: 14, fontWeight: 600, marginBottom: 8 }}>{t('Nahrát sadu křivek', 'Upload a curve set')}</h2>
          <div style={{ display: 'flex', gap: 12, flexWrap: 'wrap', marginBottom: 8 }}>
            <label style={{ display: 'flex', flexDirection: 'column', gap: 4, fontSize: 12 }}>
              {t('K datu', 'As of')}
              <input type="date" className="input" value={asOf} onChange={e => setAsOf(e.target.value)} aria-label={t('Datum sady křivek', 'Curve set as-of date')} />
            </label>
            <label style={{ display: 'flex', flexDirection: 'column', gap: 4, fontSize: 12 }}>
              {t('Původ dat', 'Provenance')}
              <select className="input" value={provenance} onChange={e => setProvenance(e.target.value as '' | 'synthetic' | 'production')} aria-label={t('Původ dat sady', 'Curve set provenance')}>
                <option value="">{t('— vyberte —', '— choose —')}</option>
                <option value="synthetic">{t('Syntetická', 'Synthetic')}</option>
                <option value="production">{t('Produkční', 'Production')}</option>
              </select>
            </label>
            <label style={{ display: 'flex', flexDirection: 'column', gap: 4, fontSize: 12, flex: 1, minWidth: 200 }}>
              {t('Zdroj kotací', 'Quote source')}
              <input className="input" value={source} maxLength={256} onChange={e => setSource(e.target.value)} aria-label={t('Zdroj kotací', 'Quote source')} />
            </label>
          </div>
          <label style={{ display: 'flex', flexDirection: 'column', gap: 4, fontSize: 12 }}>
            {t('Kotace — jeden řádek: INDEX TENOR SAZBA v %', 'Quotes — one per line: INDEX TENOR RATE in %')}
            <textarea
              className="input"
              rows={6}
              value={quotes}
              placeholder={EXAMPLE}
              onChange={e => setQuotes(e.target.value)}
              aria-label={t('Kotace peněžního trhu', 'Money-market quotes')}
              style={{ fontFamily: 'var(--font-mono, monospace)' }}
            />
          </label>
          {parsed && !parsed.ok && (
            <ul role="alert" style={{ fontSize: 12, color: 'var(--danger-text)', marginTop: 6 }}>
              {parsed.errors.map(e => (
                <li key={`${e.line}-${e.code}`}>{e.line > 0 ? t(`Řádek ${e.line}: `, `Line ${e.line}: `) : ''}{lineError(e.code)}</li>
              ))}
            </ul>
          )}
          {parsed?.ok && (
            <p style={{ fontSize: 12, color: 'var(--text-secondary)', marginTop: 6 }}>
              {t(`${parsed.count} kotací v ${Object.keys(parsed.curves).length} křivkách.`, `${parsed.count} quotes across ${Object.keys(parsed.curves).length} curves.`)}
            </p>
          )}
          <button type="button" className="btn btn-primary btn-sm" style={{ marginTop: 8 }} disabled={!ready || busy} onClick={() => void upload()}>
            {busy ? t('Nahrávám…', 'Uploading…') : t('Nahrát sadu', 'Upload set')}
          </button>
        </div>
      )}

      {notice && (
        <div role="status" className="card" style={{ marginBottom: 16, borderColor: notice.tone === 'danger' ? 'var(--danger)' : 'var(--success)' }}>
          {notice.text}{' '}
          {notice.id && <Link href={`/balance-sheet/curve-sets/${notice.id}`}>{t('Otevřít sadu', 'Open set')}</Link>}
        </div>
      )}

      {unavailable ? (
        <DataUnavailable kind={unavailable.kind} service="risk-engine" feature={t('sady výnosových křivek', 'curve sets')} lang={language} />
      ) : sets === null ? null : sets.length === 0 ? (
        <DataUnavailable kind="no_data" service="risk-engine" feature={t('sady výnosových křivek', 'curve sets')} lang={language} dense />
      ) : (
        <div className="card" style={{ overflowX: 'auto' }}>
          <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 13 }}>
            <thead>
              <tr>
                <th scope="col" style={{ textAlign: 'left' }}>{t('K datu', 'As of')}</th>
                <th scope="col" style={{ textAlign: 'left' }}>{t('Původ dat', 'Provenance')}</th>
                <th scope="col" style={{ textAlign: 'left' }}>{t('Zdroj', 'Source')}</th>
                <th scope="col" style={{ textAlign: 'left' }}>{t('Křivky', 'Curves')}</th>
                <th scope="col" style={{ textAlign: 'left' }}>{t('Zaznamenáno', 'Recorded')}</th>
              </tr>
            </thead>
            <tbody>
              {sets.map(s => (
                <tr key={s.id}>
                  <td><Link href={`/balance-sheet/curve-sets/${s.id}`}>{s.asOf}</Link></td>
                  <td><ProvenanceBadge provenance={s.provenance} /></td>
                  <td>{s.source}</td>
                  <td>{s.indices.join(', ')}</td>
                  <td>{new Date(s.recordedAt).toLocaleString(locale)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  )
}
