// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// State contribution (ADR-0334, pension-service API 1.2.0; ZDPS §16/§18): the claim batches filed
// to the state agency each quarter, the deadline counters, the returns of contributions the state
// paid for an ineligible participant, and the monthly return reports. pension-service
// GET /funding/operations/claim-batches, /state-contribution/{deadlines,returns,return-reports};
// each panel degrades through DataUnavailable on its own.

'use client'

import { useCallback, useEffect, useMemo, useState, type FormEvent } from 'react'
import Link from 'next/link'
import { useSession } from 'next-auth/react'
import { Gift } from 'lucide-react'
import { AuthGuard } from '@/components/auth/AuthGuard'
import { DataUnavailable, type UnavailableKind } from '@/components/feedback/DataUnavailable'
import { PageHeader } from '@/components/ui'
import { getJson, PENSION, pensionUrl, sendJson } from '@/components/pension/api'
import {
  incentiveStatusSchema, pensionContractListSchema, queueRowSchema, stateContributionDeadlinesSchema, stateReturnListSchema,
  type IncentiveStatus, type PensionContract, type StateContributionDeadlines, type StateReturn,
} from '@/components/pension/contracts'
import { refusalText, statusLabel } from '@/components/pension/model'
import { PartySearch, type PartyHit, partyDisplayName } from '@/components/party/PartySearch'
import { PAGE_SIZE, PensionQueue } from '@/components/pension/PensionQueue'
import { hasPermission } from '@/lib/auth/roles'
import { useLanguage } from '@/lib/i18n/LanguageContext'

export default function PensionIncentiveBatchesPage() {
  return (
    <AuthGuard permission="pension:view">
      <StateContribution />
    </AuthGuard>
  )
}

const MONTH = /^\d{4}-(0[1-9]|1[0-2])$/
const CLAIM_LIMIT = 100

function StateContribution() {
  const { t } = useLanguage()
  const { data: session } = useSession()
  const roles = useMemo(() => session?.user?.roles ?? [], [session?.user?.roles])
  const canOperate = hasPermission(roles, 'pension:operate')
  const [returnStatus, setReturnStatus] = useState('CONFIRMED')
  return (
    <div>
      <PageHeader
        title={t('Státní příspěvky', 'State incentives')}
        subtitle={t('Čtvrtletní žádosti o státní příspěvek, lhůty, vratky a jejich hlášení.', 'Quarterly state-contribution claims, deadlines, returns and their reports.')}
        icon={<Gift size={20} aria-hidden="true" />}
      />
      <Deadlines />
      {canOperate && <ClaimRun />}
      <PensionQueue
        title={t('Dávky žádostí', 'Claim batches')}
        url={pensionUrl('/funding/operations/claim-batches')}
        service={PENSION}
        feature={t('dávky státních příspěvků', 'state-incentive claim batches')}
        columns={[
          { key: 'period', cs: 'Období', en: 'Period' },
          { key: 'claimFormat', cs: 'Formát', en: 'Format' },
          { key: 'createdAt', cs: 'Vytvořeno', en: 'Created' },
        ]}
      />
      <div style={{ display: 'flex', gap: 8, alignItems: 'center', marginBottom: 8, fontSize: 13 }}>
        <label htmlFor="return-status">{t('Vratky ve stavu', 'Returns with status')}</label>
        <select id="return-status" className="input" value={returnStatus} onChange={e => setReturnStatus(e.target.value)}>
          <option value="DUE">{t('K vrácení', 'Due')}</option>
          <option value="REPORTED">{t('Nahlášené', 'Reported')}</option>
          <option value="CONFIRMED">{t('Potvrzené', 'Confirmed')}</option>
          <option value="SETTLED">{t('Vypořádané', 'Settled')}</option>
        </select>
      </div>
      <Returns status={returnStatus} canOperate={canOperate} />
      {canOperate && <FileReturnReport />}
      <PensionQueue
        title={t('Hlášení vratek', 'Return reports')}
        url={pensionUrl('/funding/operations/state-contribution/return-reports')}
        service={PENSION}
        feature={t('hlášení vratek', 'return reports')}
        columns={[
          { key: 'month', cs: 'Měsíc', en: 'Month' },
          { key: 'channelReference', cs: 'Reference podání', en: 'Filing reference' },
          { key: 'createdAt', cs: 'Vytvořeno', en: 'Created' },
        ]}
      />
      <ClientIncentives canOperate={canOperate} />
    </div>
  )
}

function Deadlines() {
  const { t, language } = useLanguage()
  const [data, setData] = useState<StateContributionDeadlines | null>(null)
  const [kind, setKind] = useState<UnavailableKind | null>(null)
  const load = useCallback(async () => {
    const res = await getJson(pensionUrl('/funding/operations/state-contribution/deadlines'), stateContributionDeadlinesSchema)
    if (!res.ok) { setKind(res.kind); setData(null); return }
    setKind(null)
    setData(res.data)
  }, [])
  useEffect(() => { void load() }, [load])
  if (kind) return <DataUnavailable kind={kind} service={PENSION} feature={t('lhůty státního příspěvku', 'state-contribution deadlines')} lang={language} dense />
  if (!data) return null
  const tile = (value: number, cs: string, en: string) => (
    <div className="card" style={{ flex: '1 1 200px', borderColor: value > 0 ? 'var(--danger)' : undefined }}>
      <div style={{ fontSize: 24, fontWeight: 600 }}>{value}</div>
      <div style={{ fontSize: 12 }}>{t(cs, en)}</div>
    </div>
  )
  return (
    <section aria-label={t('Lhůty', 'Deadlines')} style={{ display: 'flex', gap: 12, flexWrap: 'wrap', marginBottom: 16 }}>
      {tile(data.claimsPastFilingDeadline, 'Žádosti po lhůtě podání', 'Claims past the filing deadline')}
      {tile(data.claimsPastExpectedPayment, 'Žádosti bez očekávané platby', 'Claims past the expected payment')}
      {tile(data.returnsOverdue, 'Vratky po splatnosti', 'Returns overdue')}
    </section>
  )
}

type Notice = { tone: 'success' | 'danger'; text: string }

function NoticeLine({ notice }: { notice: Notice | null }) {
  if (!notice) return null
  return <div role="status" style={{ width: '100%', fontSize: 12, color: notice.tone === 'danger' ? 'var(--danger-text)' : 'var(--success-text)' }}>{notice.text}</div>
}

/** Returns table: the operator settles a CONFIRMED return by clicking its row, never by typing an id. */
function Returns({ status, canOperate }: { status: string; canOperate: boolean }) {
  const { t, language } = useLanguage()
  const [rows, setRows] = useState<StateReturn[] | null>(null)
  const [kind, setKind] = useState<UnavailableKind | null>(null)
  const [shown, setShown] = useState(PAGE_SIZE)
  const [busyId, setBusyId] = useState<string | null>(null)
  const [notice, setNotice] = useState<Notice | null>(null)
  const load = useCallback(async () => {
    const res = await getJson(pensionUrl('/funding/operations/state-contribution/returns', { status }), stateReturnListSchema)
    if (!res.ok) { setKind(res.kind); setRows(null); return }
    setKind(null)
    setRows(res.data)
    setShown(PAGE_SIZE)
  }, [status])
  useEffect(() => { void load() }, [load])
  const settle = async (id: string) => {
    setBusyId(id)
    const res = await sendJson('POST', pensionUrl(`/funding/operations/state-contribution/returns/${encodeURIComponent(id)}/settle`), undefined, queueRowSchema)
    setBusyId(null)
    setNotice(res.ok
      ? { tone: 'success', text: t('Vratka je vypořádána.', 'The return is settled.') }
      : { tone: 'danger', text: refusalText(res, t('Vypořádání vratky', 'Settling the return'), t) })
    if (res.ok) void load()
  }
  const feature = t('vratky státního příspěvku', 'state-contribution returns')
  return (
    <section className="card" style={{ marginBottom: 16, overflowX: 'auto' }}>
      <h2 style={{ fontSize: 15, margin: '0 0 8px' }}>{t('Vratky státního příspěvku (§ 18)', 'State-contribution returns (§ 18)')}</h2>
      {kind ? <DataUnavailable kind={kind} service={PENSION} feature={feature} lang={language} dense />
        : rows === null ? null
        : rows.length === 0 ? <DataUnavailable kind="no_data" service={PENSION} feature={feature} lang={language} dense />
        : (
          <>
            <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 13 }}>
              <thead>
                <tr>
                  <th scope="col" style={{ textAlign: 'left' }}>{t('Smlouva', 'Contract')}</th>
                  <th scope="col" style={{ textAlign: 'left' }}>{t('Stav', 'Status')}</th>
                  <th scope="col" style={{ textAlign: 'left' }}>{t('Důvod', 'Cause')}</th>
                  <th scope="col" style={{ textAlign: 'left' }}>{t('Částka', 'Amount')}</th>
                  <th scope="col" style={{ textAlign: 'left' }}>{t('Zjištěno', 'Discovered')}</th>
                  <th scope="col" style={{ textAlign: 'left' }}>{t('Splatné do', 'Due by')}</th>
                  {canOperate && <th scope="col" />}
                </tr>
              </thead>
              <tbody>
                {rows.slice(0, shown).map(row => (
                  <tr key={row.id}>
                    <td><Link href={`/pension/contracts/${encodeURIComponent(row.contractId)}`}>{row.contractId}</Link></td>
                    <td>{statusLabel(row.status, t)}</td>
                    <td>{row.cause}</td>
                    <td>{row.amount} {row.currency}</td>
                    <td>{row.discoveredOn ?? '—'}</td>
                    <td>{row.dueBy ?? '—'}</td>
                    {canOperate && (
                      <td>
                        {row.status === 'CONFIRMED' && (
                          <button type="button" className="btn btn-secondary btn-sm" disabled={busyId === row.id} onClick={() => void settle(row.id)}>
                            {t('Vypořádat', 'Settle')}
                          </button>
                        )}
                      </td>
                    )}
                  </tr>
                ))}
              </tbody>
            </table>
            {rows.length > shown && (
              <button type="button" className="btn btn-secondary btn-sm" style={{ marginTop: 12 }} onClick={() => setShown(s => s + PAGE_SIZE)}>
                {t('Načíst další', 'Load more')}
              </button>
            )}
          </>
        )}
      <NoticeLine notice={notice} />
    </section>
  )
}

/** Files the monthly return report with every DUE return (POST …/return-reports {month}). */
function FileReturnReport() {
  const { t } = useLanguage()
  const [month, setMonth] = useState('')
  const [busy, setBusy] = useState(false)
  const [notice, setNotice] = useState<Notice | null>(null)
  const submit = async (e: FormEvent) => {
    e.preventDefault()
    if (!MONTH.test(month.trim())) { setNotice({ tone: 'danger', text: t('Měsíc zadejte jako RRRR-MM.', 'Enter the month as YYYY-MM.') }); return }
    setBusy(true)
    const res = await sendJson('POST', pensionUrl('/funding/operations/state-contribution/return-reports'), { month: month.trim() }, queueRowSchema)
    setBusy(false)
    if (!res.ok) { setNotice({ tone: 'danger', text: refusalText(res, t('Podání hlášení', 'Filing the report'), t) }); return }
    setNotice({
      tone: 'success',
      text: res.data.filed === true ? t('Hlášení vratek bylo podáno.', 'The return report was filed.') : t('Žádná vratka k nahlášení.', 'No returns were due for this month.'),
    })
  }
  return (
    <form className="card" onSubmit={submit} style={{ marginBottom: 16, display: 'flex', gap: 8, flexWrap: 'wrap', alignItems: 'center' }}>
      <input className="input" value={month} onChange={e => setMonth(e.target.value)} placeholder={t('Měsíc (RRRR-MM)', 'Month (YYYY-MM)')} aria-label={t('Měsíc hlášení', 'Report month')} />
      <button type="submit" className="btn btn-secondary btn-sm" disabled={busy}>{t('Podat hlášení vratek', 'File return report')}</button>
      <NoticeLine notice={notice} />
    </form>
  )
}

/** Generates the claims of a closed month and files them (POST …/claim-runs {period}). */
function ClaimRun() {
  const { t } = useLanguage()
  const [period, setPeriod] = useState('')
  const [busy, setBusy] = useState(false)
  const [notice, setNotice] = useState<Notice | null>(null)
  const submit = async (e: FormEvent) => {
    e.preventDefault()
    if (!MONTH.test(period.trim())) { setNotice({ tone: 'danger', text: t('Období zadejte jako RRRR-MM.', 'Enter the period as YYYY-MM.') }); return }
    setBusy(true)
    const res = await sendJson('POST', pensionUrl('/funding/operations/claim-runs'), { period: period.trim() }, queueRowSchema)
    setBusy(false)
    setNotice(res.ok
      ? { tone: 'success', text: t(`Žádosti vytvořeny: ${String(res.data.claimsCreated ?? 0)}.`, `Claims created: ${String(res.data.claimsCreated ?? 0)}.`) }
      : { tone: 'danger', text: refusalText(res, t('Dávka žádostí', 'The claim run'), t) })
  }
  return (
    <form className="card" onSubmit={submit} style={{ marginBottom: 16, display: 'flex', gap: 8, flexWrap: 'wrap', alignItems: 'center' }}>
      <input className="input" value={period} onChange={e => setPeriod(e.target.value)} placeholder={t('Uzavřený měsíc (RRRR-MM)', 'Closed month (YYYY-MM)')} aria-label={t('Období žádostí', 'Claim period')} />
      <button type="submit" className="btn btn-secondary btn-sm" disabled={busy}>{t('Vytvořit a podat žádosti', 'Generate and file claims')}</button>
      <NoticeLine notice={notice} />
    </form>
  )
}

/**
 * Find a client by name (party-service search), list their pension contracts, open one's incentive
 * claims and balances, and return a RECEIVED claim to the agency. A pasted id still works because
 * PartySearch passes a UUID straight through.
 */
function ClientIncentives({ canOperate }: { canOperate: boolean }) {
  const { t, language } = useLanguage()
  const [party, setParty] = useState<PartyHit | null>(null)
  const [contracts, setContracts] = useState<PensionContract[] | null>(null)
  const [contractsKind, setContractsKind] = useState<UnavailableKind | null>(null)
  const [contractId, setContractId] = useState<string | null>(null)
  const [status, setStatus] = useState<IncentiveStatus | null>(null)
  const [statusKind, setStatusKind] = useState<UnavailableKind | null>(null)
  const [busyId, setBusyId] = useState<string | null>(null)
  const [notice, setNotice] = useState<Notice | null>(null)

  const loadStatus = useCallback(async (id: string) => {
    const res = await getJson(pensionUrl(`/contracts/${encodeURIComponent(id)}/incentives`), incentiveStatusSchema)
    if (!res.ok) { setStatusKind(res.kind); setStatus(null); return }
    setStatusKind(null)
    setStatus(res.data)
  }, [])

  const choose = async (hit: PartyHit) => {
    setParty(hit); setContracts(null); setContractsKind(null); setContractId(null); setStatus(null); setStatusKind(null); setNotice(null)
    const res = await getJson(pensionUrl('/contracts', { q: hit.id, limit: String(CLAIM_LIMIT) }), pensionContractListSchema)
    if (!res.ok) { setContractsKind(res.kind); return }
    setContracts(res.data.filter(c => c.participantPartyId === hit.id))
  }
  const open = (id: string) => { setContractId(id); setStatus(null); setNotice(null); void loadStatus(id) }
  const returnClaim = async (claimId: string) => {
    setBusyId(claimId)
    const res = await sendJson('POST', pensionUrl(`/funding/operations/claims/${encodeURIComponent(claimId)}/return`), undefined, queueRowSchema)
    setBusyId(null)
    setNotice(res.ok
      ? { tone: 'success', text: t('Příspěvek byl vrácen státu.', 'The incentive was returned to the state.') }
      : { tone: 'danger', text: refusalText(res, t('Vrácení příspěvku', 'Returning the incentive'), t) })
    if (res.ok && contractId) void loadStatus(contractId)
  }

  return (
    <section style={{ marginBottom: 16 }}>
      <h2 style={{ fontSize: 15, margin: '0 0 8px' }}>{t('Příspěvky klienta', 'Client incentives')}</h2>
      <PartySearch onSelect={hit => void choose(hit)} selectedId={party?.id} />
      {party && (
        <div className="card" style={{ marginBottom: 16, overflowX: 'auto' }}>
          <div style={{ fontSize: 13, marginBottom: 8 }}>{t('Klient', 'Client')}: <strong>{partyDisplayName(party)}</strong></div>
          {contractsKind ? <DataUnavailable kind={contractsKind} service={PENSION} feature={t('smlouvy klienta', 'client contracts')} lang={language} dense />
            : contracts === null ? null
            : contracts.length === 0 ? <DataUnavailable kind="no_data" service={PENSION} feature={t('smlouvy klienta', 'client contracts')} lang={language} dense />
            : (
              <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 13 }}>
                <thead><tr>
                  <th scope="col" style={{ textAlign: 'left' }}>{t('Smlouva', 'Contract')}</th>
                  <th scope="col" style={{ textAlign: 'left' }}>{t('Produkt', 'Product')}</th>
                  <th scope="col" style={{ textAlign: 'left' }}>{t('Stav', 'Status')}</th>
                  <th scope="col" />
                </tr></thead>
                <tbody>
                  {contracts.slice(0, PAGE_SIZE).map(c => (
                    <tr key={c.contractId}>
                      <td><Link href={`/pension/contracts/${encodeURIComponent(c.contractId)}`}>{c.contractId}</Link></td>
                      <td>{c.productLine}</td>
                      <td>{statusLabel(c.status, t)}</td>
                      <td><button type="button" className="btn btn-secondary btn-sm" onClick={() => open(c.contractId)}>{t('Zobrazit příspěvky', 'Show incentives')}</button></td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
        </div>
      )}
      {contractId && (
        <div className="card" style={{ overflowX: 'auto' }}>
          {statusKind ? <DataUnavailable kind={statusKind} service={PENSION} feature={t('příspěvky smlouvy', 'contract incentives')} lang={language} dense />
            : status === null ? null
            : status.claims.length === 0 ? <DataUnavailable kind="no_data" service={PENSION} feature={t('příspěvky smlouvy', 'contract incentives')} lang={language} dense />
            : (
              <>
                <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 13 }}>
                  <thead><tr>
                    <th scope="col" style={{ textAlign: 'left' }}>{t('Příspěvek', 'Incentive')}</th>
                    <th scope="col" style={{ textAlign: 'left' }}>{t('Období', 'Period')}</th>
                    <th scope="col" style={{ textAlign: 'left' }}>{t('Požadováno', 'Claimed')}</th>
                    <th scope="col" style={{ textAlign: 'left' }}>{t('Přijato', 'Received')}</th>
                    <th scope="col" style={{ textAlign: 'left' }}>{t('Stav', 'Status')}</th>
                    {canOperate && <th scope="col" />}
                  </tr></thead>
                  <tbody>
                    {status.claims.slice(0, CLAIM_LIMIT).map(c => (
                      <tr key={c.id}>
                        <td>{c.incentiveId}</td>{/* raw-id-ok: incentive code (e.g. STATE_CONTRIBUTION), not an entity id */}
                        <td>{c.period}</td>
                        <td>{c.claimedAmount} {c.currency}</td>
                        <td>{c.receivedAmount ?? '—'}</td>
                        <td>{statusLabel(c.status, t)}{c.rejectionReason ? ` (${c.rejectionReason})` : ''}</td>
                        {canOperate && (
                          <td>
                            {c.status === 'RECEIVED' && (
                              <button type="button" className="btn btn-secondary btn-sm" disabled={busyId === c.id} onClick={() => void returnClaim(c.id)}>
                                {t('Vrátit státu', 'Return to state')}
                              </button>
                            )}
                          </td>
                        )}
                      </tr>
                    ))}
                  </tbody>
                </table>
                <div style={{ marginTop: 8, fontSize: 12 }}>
                  {status.balances.map(b => (
                    <div key={b.incentiveId}>{b.incentiveId}: {t('přijato', 'received')} {b.received}, {t('vráceno', 'returned')} {b.returned}, {t('čistě', 'net')} {b.net}</div>
                  ))}
                </div>
              </>
            )}
          <NoticeLine notice={notice} />
        </div>
      )}
    </section>
  )
}
