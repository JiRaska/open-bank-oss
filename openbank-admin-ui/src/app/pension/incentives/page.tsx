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
import { useSession } from 'next-auth/react'
import { Gift } from 'lucide-react'
import { AuthGuard } from '@/components/auth/AuthGuard'
import { DataUnavailable, type UnavailableKind } from '@/components/feedback/DataUnavailable'
import { PageHeader } from '@/components/ui'
import { getJson, PENSION, pensionUrl, sendJson } from '@/components/pension/api'
import { queueRowSchema, stateContributionDeadlinesSchema, type StateContributionDeadlines } from '@/components/pension/contracts'
import { refusalText } from '@/components/pension/model'
import { PensionQueue } from '@/components/pension/PensionQueue'
import { hasPermission } from '@/lib/auth/roles'
import { useLanguage } from '@/lib/i18n/LanguageContext'

export default function PensionIncentiveBatchesPage() {
  return (
    <AuthGuard permission="pension:view">
      <StateContribution />
    </AuthGuard>
  )
}

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i

function StateContribution() {
  const { t } = useLanguage()
  const { data: session } = useSession()
  const roles = useMemo(() => session?.user?.roles ?? [], [session?.user?.roles])
  const canOperate = hasPermission(roles, 'pension:operate')
  const [returnStatus, setReturnStatus] = useState('DUE')
  return (
    <div>
      <PageHeader
        title={t('Státní příspěvky', 'State incentives')}
        subtitle={t('Čtvrtletní žádosti o státní příspěvek, lhůty, vratky a jejich hlášení.', 'Quarterly state-contribution claims, deadlines, returns and their reports.')}
        icon={<Gift size={20} aria-hidden="true" />}
      />
      <Deadlines />
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
      <PensionQueue
        title={t('Vratky státního příspěvku (§ 18)', 'State-contribution returns (§ 18)')}
        url={pensionUrl('/funding/operations/state-contribution/returns', { status: returnStatus })}
        service={PENSION}
        feature={t('vratky státního příspěvku', 'state-contribution returns')}
        columns={[
          { key: 'cause', cs: 'Důvod', en: 'Cause' },
          { key: 'amount', cs: 'Částka', en: 'Amount' },
          { key: 'currency', cs: 'Měna', en: 'Currency' },
          { key: 'discoveredOn', cs: 'Zjištěno', en: 'Discovered' },
          { key: 'dueBy', cs: 'Splatné do', en: 'Due by' },
        ]}
      />
      {canOperate && <SettleReturn />}
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

/** Record that a return was paid back to the state agency. */
function SettleReturn() {
  const { t } = useLanguage()
  const [id, setId] = useState('')
  const [busy, setBusy] = useState(false)
  const [message, setMessage] = useState<{ tone: 'success' | 'danger'; text: string } | null>(null)
  const submit = async (e: FormEvent) => {
    e.preventDefault()
    if (!UUID.test(id.trim())) { setMessage({ tone: 'danger', text: t('ID vratky musí být UUID.', 'The return id must be a UUID.') }); return }
    setBusy(true)
    const res = await sendJson('POST', pensionUrl(`/funding/operations/state-contribution/returns/${encodeURIComponent(id.trim())}/settle`), undefined, queueRowSchema)
    setBusy(false)
    setMessage(res.ok
      ? { tone: 'success', text: t('Vratka je vypořádána.', 'The return is settled.') }
      : { tone: 'danger', text: refusalText(res, t('Vypořádání vratky', 'Settling the return'), t) })
  }
  return (
    <form className="card" onSubmit={submit} style={{ marginBottom: 16, display: 'flex', gap: 8, flexWrap: 'wrap', alignItems: 'center' }}>
      <input className="input" value={id} onChange={e => setId(e.target.value)} placeholder={t('ID vratky (UUID)', 'Return id (UUID)')} aria-label={t('ID vratky', 'Return id')} style={{ flex: '1 1 320px' }} />
      <button type="submit" className="btn btn-secondary btn-sm" disabled={busy}>{t('Vypořádat vratku', 'Settle return')}</button>
      {message && <div role="status" style={{ width: '100%', fontSize: 12, color: message.tone === 'danger' ? 'var(--danger-text)' : 'var(--success-text)' }}>{message.text}</div>}
    </form>
  )
}
