// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

import styles from './settlement.module.css'
import { useCallback, useEffect, useRef, useState, type ReactNode } from 'react'
import { DataUnavailable, type UnavailableKind } from '@/components/feedback/DataUnavailable'
import { EntityChip } from '@/components/entities/EntityChip'
import { HumanReference } from '@/components/ui/HumanReference'
import { classifyBffFailure } from '@/lib/services/bff'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { recoveryCopy, settlementDetailsSchema, settlementStatusCopy, type SettlementDetails } from '@/lib/settlement/status'

/**
 * One settlement's persisted state, read through the settlement BFF. Read-only by construction:
 * the only control is Refresh, which performs exactly one GET and never retries origination.
 */
export function SettlementStatusPanel({ id }: { id: string }) {
  const { t, language } = useLanguage()
  const [detail, setDetail] = useState<SettlementDetails | null>(null)
  const [failure, setFailure] = useState<UnavailableKind | null>(null)
  const [loading, setLoading] = useState(true)
  const generation = useRef(0)

  const load = useCallback(async () => {
    const current = ++generation.current
    try {
      const response = await fetch(`/api/settlements/${encodeURIComponent(id)}`, {
        cache: 'no-store', signal: AbortSignal.timeout(10000),
      })
      if (current !== generation.current) return
      if (!response.ok) {
        const kind = await classifyBffFailure(response)
        if (current === generation.current) setFailure(kind)
        return
      }
      const parsed = settlementDetailsSchema.safeParse(await response.json())
      if (current !== generation.current) return
      if (!parsed.success || parsed.data.id.toLowerCase() !== id.toLowerCase()) {
        setFailure('error')
        return
      }
      setDetail(parsed.data)
    } catch {
      if (current === generation.current) setFailure('unreachable')
    } finally {
      if (current === generation.current) setLoading(false)
    }
  }, [id])

  useEffect(() => {
    const initialLoad = window.setTimeout(() => void load(), 0)
    return () => { window.clearTimeout(initialLoad); generation.current += 1 }
  }, [load])

  function reload() {
    setLoading(true)
    setDetail(null)
    setFailure(null)
    void load()
  }

  const locale = language === 'cs' ? 'cs-CZ' : 'en-GB'
  const when = (iso: string) => new Date(iso).toLocaleString(locale)
  const rows: Array<[string, ReactNode]> = detail ? [
    [t('Převod', 'Transfer'), <HumanReference key="ref" label={t('Settlement', 'Settlement')} reference={detail.id}
      copyLabel={t('Kopírovat identifikátor převodu', 'Copy transfer reference')} />],
    [t('Účet plátce', 'Payer account'), <EntityChip key="payer" type="account" id={detail.payerAccountId} />],
    [t('Účet příjemce', 'Payee account'), <EntityChip key="payee" type="account" id={detail.payeeAccountId} />],
    [t('Částka', 'Amount'), `${detail.amount} ${detail.currency}`],
    [t('Uložený stav', 'Persisted state'), detail.status],
    [t('Vytvořeno', 'Created'), when(detail.createdAt)],
    [t('Poslední změna', 'Last changed'), when(detail.updatedAt)],
  ] : []

  return <section className={`card ${styles.panel}`} aria-label={t('Uložený stav settlementu', 'Persisted settlement state')}>
    <button className={`btn btn-secondary ${styles.action}`} type="button" onClick={reload} disabled={loading}>
      {t('Obnovit stav', 'Refresh state')}
    </button>
    {loading && <p role="status">{t('Načítám uložený stav…', 'Loading persisted state…')}</p>}
    {failure !== null && <DataUnavailable kind={failure} service="Settlement-service" lang={language} dense
      feature={t('Stav settlementu', 'Settlement state')}
      detail={failure === 'not_found'
        ? t('Záznam nebyl nalezen. To nepotvrzuje nepřijetí původního požadavku. Před opakováním ověřte schválení a stav služby.', 'No record was found. This does not prove the original request was not accepted. Verify the approval and service state before retrying.')
        : failure === 'unauthorized'
          ? t('Pro tento dotaz nemáte platné přihlášení nebo oprávnění.', 'You do not have a valid session or permission for this query.')
          : t('Stav nelze ověřit. Neopakujte převod jen kvůli chybě tohoto dotazu.', 'The state cannot be verified. Do not repeat the transfer solely because this query failed.')}
    />}
    {detail && <>
      <p role="status" className="font-semibold">
        {detail.recoveryRequired ? t(...recoveryCopy) : t(...settlementStatusCopy[detail.status])}
      </p>
      <dl className="grid gap-4 sm:grid-cols-2">
        {rows.map(([label, value]) => <div key={label}>
          <dt className="text-xs text-[var(--text-secondary)]">{label}</dt>
          <dd className="mt-1 break-all font-medium">{value}</dd>
        </div>)}
      </dl>
    </>}
    <p className="text-sm text-[var(--text-secondary)]">{t('Schválení příkazu samo o sobě nepotvrzuje zaúčtování. Tento dotaz převod nespouští ani neopakuje.', 'Approval alone does not confirm booking. This query does not initiate or retry a transfer.')}</p>
  </section>
}
