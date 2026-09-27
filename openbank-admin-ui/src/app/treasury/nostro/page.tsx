// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Nostro reconciliation (ADR-0315 D7, #10896): upload a correspondent's camt.053 end-of-day
// statement (NostroResource's own @RolesAllowed — upload is APPROVER only, unlike a treasury deal
// draft it is nothing a dealer may do) and read how it compares with the ledger's nostro GL.
// Nothing here posts to the ledger; an unmatched item is only ever listed for a person.

'use client'

import { useMemo, useState } from 'react'
import { useSession } from 'next-auth/react'
import { FileSearch, UploadCloud } from 'lucide-react'
import { AuthGuard } from '@/components/auth/AuthGuard'
import { PageHeader, StatCard, StatusBadge } from '@/components/ui'
import { hasPermission } from '@/lib/auth/roles'
import { newIdempotencyKey, postNostroStatement, getJson, nostroReconciliationUrl } from '@/components/treasury/api'
import { nostroReconciliationSchema, type NostroReconciliation } from '@/components/treasury/contracts'
import { formatDifference, isNonZeroDifference, MATCH_TYPE_TONE, matchTypeLabel, refusalText } from '@/components/treasury/model'
import { useLanguage } from '@/lib/i18n/LanguageContext'

export default function NostroReconciliationPage() {
  return (
    <AuthGuard permission="treasury:nostro:read">
      <Nostro />
    </AuthGuard>
  )
}

type Notice = { tone: 'success' | 'danger'; text: string }

function Nostro() {
  const { t, language } = useLanguage()
  const { data: session } = useSession()
  const roles = useMemo(() => session?.user?.roles ?? [], [session?.user?.roles])
  const canUpload = hasPermission(roles, 'treasury:nostro:upload')
  const locale = language === 'cs' ? 'cs-CZ' : 'en-GB'
  const money = (v: number) => v.toLocaleString(locale, { minimumFractionDigits: 2, maximumFractionDigits: 2 })

  const [file, setFile] = useState<File | null>(null)
  const [busy, setBusy] = useState(false)
  const [notice, setNotice] = useState<Notice | null>(null)
  const [result, setResult] = useState<NostroReconciliation | null>(null)
  const [lookupId, setLookupId] = useState('')

  const upload = async () => {
    if (!file) return
    setBusy(true)
    setNotice(null)
    // One key per submit (one click = one intent); a retry of the SAME click should reuse this
    // key, never mint a fresh one, or the server can no longer tell a resubmit from a new upload.
    const idempotencyKey = newIdempotencyKey()
    const bytes = await file.arrayBuffer()
    const uploaded = await postNostroStatement(bytes, idempotencyKey)
    if (!uploaded.ok) {
      setBusy(false)
      setNotice({ tone: 'danger', text: refusalText(uploaded, t('Nahrání', 'Upload'), t) })
      return
    }
    const rec = await getJson(nostroReconciliationUrl(uploaded.data.id), nostroReconciliationSchema)
    setBusy(false)
    if (rec.ok) {
      setResult(rec.data)
      setNotice({ tone: 'success', text: t('Výpis nahrán a spárován s hlavní knihou. Nic se nezaúčtovalo.', 'Statement uploaded and matched against the ledger. Nothing has posted.') })
    } else {
      setNotice({ tone: 'danger', text: t('Výpis byl nahrán, ale rekonciliaci se nepodařilo načíst.', 'The statement uploaded, but the reconciliation could not be loaded.') })
    }
  }

  const lookup = async () => {
    if (!lookupId.trim()) return
    setBusy(true)
    setNotice(null)
    const rec = await getJson(nostroReconciliationUrl(lookupId.trim()), nostroReconciliationSchema)
    setBusy(false)
    if (rec.ok) setResult(rec.data)
    else setNotice({ tone: 'danger', text: rec.kind === 'not_found' ? t('Výpis nebyl nalezen.', 'No such statement.') : t('Rekonciliaci se nepodařilo načíst.', 'The reconciliation could not be loaded.') })
  }

  return (
    <div>
      <PageHeader
        title={t('Nostro rekonciliace', 'Nostro reconciliation')}
        subtitle={t('Výpis camt.053 od korespondenční banky vs. nostro účet v hlavní knize. Nic se nezaúčtovává.', 'A correspondent’s camt.053 statement vs. the ledger nostro GL. Nothing is posted.')}
        icon={<FileSearch size={20} aria-hidden="true" />}
      />

      {canUpload && (
        <div className="card" style={{ marginBottom: 16 }}>
          <label style={{ display: 'flex', flexDirection: 'column', gap: 4, fontSize: 12, maxWidth: 420 }}>
            {t('Výpis camt.053 (XML)', 'camt.053 statement (XML)')}
            <input
              type="file"
              accept=".xml,application/xml,text/xml"
              aria-label={t('Vybrat soubor výpisu', 'Choose statement file')}
              onChange={e => setFile(e.target.files?.[0] ?? null)}
            />
          </label>
          <button
            type="button"
            className="btn btn-primary btn-sm"
            style={{ marginTop: 12 }}
            disabled={!file || busy}
            onClick={() => void upload()}
          >
            <UploadCloud size={14} aria-hidden="true" style={{ marginRight: 6 }} />
            {t('Nahrát výpis', 'Upload statement')}
          </button>
        </div>
      )}

      <div className="card" style={{ marginBottom: 16 }}>
        <label style={{ display: 'flex', flexDirection: 'column', gap: 4, fontSize: 12, maxWidth: 320 }}>
          {t('Zobrazit rekonciliaci podle ID výpisu', 'View reconciliation by statement ID')}
          <input className="input" value={lookupId} onChange={e => setLookupId(e.target.value)} aria-label={t('ID výpisu', 'Statement ID')} />
        </label>
        <button type="button" className="btn btn-secondary btn-sm" style={{ marginTop: 12 }} disabled={busy || !lookupId.trim()} onClick={() => void lookup()}>
          {t('Načíst', 'Load')}
        </button>
      </div>

      {notice && (
        <div role="status" className="card" style={{ marginBottom: 16, borderColor: notice.tone === 'danger' ? 'var(--danger)' : 'var(--success)' }}>
          {notice.text}
        </div>
      )}

      {result && <ReconciliationResult result={result} money={money} t={t} />}
    </div>
  )
}

function ReconciliationResult({
  result, money, t,
}: {
  result: NostroReconciliation
  money: (v: number) => string
  t: (cs: string, en: string) => string
}) {
  const openingDiffText = formatDifference(result.openingDifference, money)
  const closingDiffText = formatDifference(result.closingDifference, money)
  const openingNonZero = isNonZeroDifference(result.openingDifference)
  const closingNonZero = isNonZeroDifference(result.closingDifference)
  const ledgerBalanceText = (v: number | null) =>
    v === null ? t('neuvedeno', 'not stated') : `${money(v)} ${result.currency}`

  return (
    <div>
      <div style={{ display: 'flex', gap: 12, alignItems: 'center', marginBottom: 16, flexWrap: 'wrap' }}>
        {result.reconciled === null ? (
          <StatusBadge status="UNDETERMINED" tone="neutral" label={t('Zůstatky nelze porovnat', 'Balances not comparable')} />
        ) : (
          <StatusBadge
            status={result.reconciled ? 'RECONCILED' : 'UNRECONCILED'}
            tone={result.reconciled ? 'success' : 'warning'}
            label={result.reconciled ? t('Sesouhlaseno', 'Reconciled') : t('Nesouhlasí', 'Not reconciled')}
          />
        )}
        <span style={{ fontSize: 13, color: 'var(--text-secondary)' }}>
          {t(`Výpis ${result.statementId} · IBAN ${result.iban} · GL ${result.glCode} · ${result.statementDate}`, `Statement ${result.statementId} · IBAN ${result.iban} · GL ${result.glCode} · ${result.statementDate}`)}
        </span>
      </div>

      {result.balanceNotStated ? (
        <p role="note" style={{ fontSize: 13, color: 'var(--text-secondary)', marginBottom: 12 }}>
          {t('Zůstatky hlavní knihy nejsou uvedeny: ', 'Ledger balances not stated: ')}{result.balanceNotStated}
        </p>
      ) : null}
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(200px, 1fr))', gap: 12, marginBottom: 16 }}>
        <StatCard label={t('Počáteční zůstatek (výpis)', 'Opening balance (statement)')} value={`${money(result.statementOpeningBalance)} ${result.currency}`} />
        <StatCard label={t('Počáteční zůstatek (hl. kniha)', 'Opening balance (ledger)')} value={ledgerBalanceText(result.ledgerOpeningBalance)} />
        <StatCard
          label={t('Rozdíl (počáteční)', 'Opening difference')}
          value={openingDiffText === null ? t('nevypočteno', 'not computed') : `${openingDiffText} ${result.currency}`}
          tone={openingNonZero ? 'danger' : undefined}
        />
        <StatCard label={t('Konečný zůstatek (výpis)', 'Closing balance (statement)')} value={`${money(result.statementClosingBalance)} ${result.currency}`} />
        <StatCard label={t('Konečný zůstatek (hl. kniha)', 'Closing balance (ledger)')} value={ledgerBalanceText(result.ledgerClosingBalance)} />
        <StatCard
          label={t('Rozdíl (konečný)', 'Closing difference')}
          value={closingDiffText === null ? t('nevypočteno', 'not computed') : `${closingDiffText} ${result.currency}`}
          tone={closingNonZero ? 'danger' : undefined}
        />
      </div>

      <h3 style={{ fontSize: 14, marginBottom: 8 }}>{t('Spárované položky', 'Matched items')}</h3>
      <div className="card" style={{ overflowX: 'auto', marginBottom: 16 }}>
        {result.matches.length === 0 ? (
          <p style={{ fontSize: 13, color: 'var(--text-secondary)' }}>{t('Žádné spárované položky.', 'No matched items.')}</p>
        ) : (
          <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 13 }}>
            <thead>
              <tr>
                <th scope="col" style={{ textAlign: 'left' }}>{t('Typ shody', 'Match type')}</th>
                <th scope="col" style={{ textAlign: 'left' }}>{t('Reference výpisu', 'Statement reference')}</th>
                <th scope="col" style={{ textAlign: 'right' }}>{t('Částka (výpis)', 'Amount (statement)')}</th>
                <th scope="col" style={{ textAlign: 'left' }}>{t('Transakce hl. knihy', 'Ledger transaction')}</th>
                <th scope="col" style={{ textAlign: 'right' }}>{t('Částka (hl. kniha)', 'Amount (ledger)')}</th>
              </tr>
            </thead>
            <tbody>
              {result.matches.map((m, i) => (
                <tr key={`${m.entry.sequence}-${i}`}>
                  <td><StatusBadge status={m.matchType} tone={MATCH_TYPE_TONE[m.matchType]} label={matchTypeLabel(m.matchType, t)} /></td>
                  <td>{m.entry.reference ?? t('(bez reference)', '(no reference)')}</td>
                  <td style={{ textAlign: 'right' }}>{money(m.entry.amount)} {m.entry.currency}</td>
                  <td>{m.ledgerLine.transactionId}</td>
                  <td style={{ textAlign: 'right' }}>{money(m.ledgerLine.amount)} {m.ledgerLine.currency}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>

      <h3 style={{ fontSize: 14, marginBottom: 8 }}>{t('Nespárované položky výpisu', 'Unmatched statement entries')}</h3>
      <div className="card" style={{ overflowX: 'auto', marginBottom: 16 }}>
        {result.unmatchedStatementEntries.length === 0 ? (
          <p style={{ fontSize: 13, color: 'var(--text-secondary)' }}>{t('Žádné nespárované položky výpisu.', 'No unmatched statement entries.')}</p>
        ) : (
          <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 13 }}>
            <thead>
              <tr>
                <th scope="col" style={{ textAlign: 'left' }}>{t('Sekvence', 'Sequence')}</th>
                <th scope="col" style={{ textAlign: 'left' }}>{t('Datum zaúčtování', 'Booking date')}</th>
                <th scope="col" style={{ textAlign: 'left' }}>{t('Směr', 'Direction')}</th>
                <th scope="col" style={{ textAlign: 'right' }}>{t('Částka', 'Amount')}</th>
                <th scope="col" style={{ textAlign: 'left' }}>{t('Reference', 'Reference')}</th>
              </tr>
            </thead>
            <tbody>
              {result.unmatchedStatementEntries.map(e => (
                <tr key={e.sequence}>
                  <td>{e.sequence}</td>
                  <td>{e.bookingDate}</td>
                  <td>{e.direction}</td>
                  <td style={{ textAlign: 'right' }}>{money(e.amount)} {e.currency}</td>
                  <td>{e.reference ?? t('(bez reference)', '(no reference)')}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>

      <h3 style={{ fontSize: 14, marginBottom: 8 }}>{t('Nespárované položky hlavní knihy', 'Unmatched ledger lines')}</h3>
      <div className="card" style={{ overflowX: 'auto' }}>
        {result.unmatchedLedgerLines.length === 0 ? (
          <p style={{ fontSize: 13, color: 'var(--text-secondary)' }}>{t('Žádné nespárované položky hlavní knihy.', 'No unmatched ledger lines.')}</p>
        ) : (
          <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 13 }}>
            <thead>
              <tr>
                <th scope="col" style={{ textAlign: 'left' }}>{t('Transakce', 'Transaction')}</th>
                <th scope="col" style={{ textAlign: 'left' }}>{t('Datum', 'Date')}</th>
                <th scope="col" style={{ textAlign: 'left' }}>{t('Strana', 'Side')}</th>
                <th scope="col" style={{ textAlign: 'right' }}>{t('Částka', 'Amount')}</th>
                <th scope="col" style={{ textAlign: 'left' }}>{t('Popis', 'Description')}</th>
              </tr>
            </thead>
            <tbody>
              {result.unmatchedLedgerLines.map(l => (
                <tr key={l.lineId}>
                  <td>{l.transactionId}</td>
                  <td>{l.entryDate}</td>
                  <td>{l.side}</td>
                  <td style={{ textAlign: 'right' }}>{money(l.amount)} {l.currency}</td>
                  <td>{l.description ?? t('(bez popisu)', '(no description)')}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
    </div>
  )
}
