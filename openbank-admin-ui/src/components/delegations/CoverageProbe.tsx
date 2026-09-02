// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

import { useCallback, useState } from 'react'
import { ShieldQuestion } from 'lucide-react'
import type { Grant } from '@/components/delegations/GrantView'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { isAssignablePresetCapability } from '@/lib/delegations/rolePresets'

type CheckOutcome = { granted: boolean; reason?: string | null; code?: string | null }

/**
 * Asks delegation-service whether any current delegation covers this grantee, resource and
 * capability. The endpoint does not bind its decision to the grant shown on this page and does
 * not prove cumulative headroom: only the payment reservation path can atomically consume
 * daily/monthly capacity without racing another payment.
 */
export function CoverageProbe({ grant }: { grant: Grant }) {
  const { t } = useLanguage()
  const supportedCapabilities = (grant.capabilities ?? []).filter(isAssignablePresetCapability)
  const [capability, setCapability] = useState(supportedCapabilities[0] ?? '')
  const [amount, setAmount] = useState('')
  const [outcome, setOutcome] = useState<CheckOutcome | null>(null)
  const [failed, setFailed] = useState(false)

  const run = useCallback(async () => {
    setFailed(false)
    setOutcome(null)
    const body: Record<string, unknown> = {
      granteePartyId: grant.granteePartyId,
      resourceType: grant.resourceType,
      resourceId: grant.resourceId,
      capability,
    }
    const parsed = Number(amount)
    if (amount.trim() !== '' && Number.isFinite(parsed)) {
      body.amount = { amount: parsed, currency: grant.perTransactionLimit?.currency ?? 'CZK' }
    }
    try {
      const res = await fetch('/api/delegations/check', {
        method: 'POST',
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify(body),
        signal: AbortSignal.timeout(8000),
      })
      if (!res.ok) { setFailed(true); return }
      setOutcome((await res.json()) as CheckOutcome)
    } catch {
      setFailed(true)
    }
  }, [grant, capability, amount])

  return (
    <div className="card" style={{ padding: '16px', marginTop: '16px' }}>
      <h2 style={{ fontSize: '15px', fontWeight: 700, marginBottom: '2px' }}>
        <ShieldQuestion size={15} color="var(--accent)" style={{ verticalAlign: 'middle', marginRight: '6px' }} />
        {t('Kontrola přístupu ke zdroji', 'Resource access eligibility check')}
      </h2>
      <p style={{ fontSize: '12px', color: 'var(--text-tertiary)', marginBottom: '12px' }}>
        {t(
          'Hledá libovolnou právě účinnou delegaci tohoto příjemce ke stejnému zdroji — výsledek nemusí pocházet z tohoto konkrétního grantu. Ověří oprávnění a strop jedné operace, nic nerezervuje a nepotvrzuje zbývající denní ani měsíční limit.',
          'Finds any delegation currently effective for this grantee and resource — the result need not come from this specific grant. It checks capability and the per-operation ceiling, reserves nothing, and does not prove remaining daily or monthly headroom.',
        )}
      </p>

      {supportedCapabilities.length === 0 ? <p role="note" style={{ fontSize: '12px', color: 'var(--warning-text)' }}>
        {t(
          'Tento historický grant neobsahuje žádné účinné právo, které lze ověřit.',
          'This historical grant contains no effective authority that can be probed.',
        )}
      </p> : <div style={{ display: 'flex', gap: '8px', flexWrap: 'wrap', alignItems: 'center' }}>
        <select
          className="input"
          value={capability}
          onChange={e => setCapability(e.target.value)}
          aria-label={t('Oprávnění k ověření', 'Capability to probe')}
        >
          {supportedCapabilities.map(c => <option key={c} value={c}>{c}</option>)}
        </select>
        <input
          className="input"
          value={amount}
          onChange={e => setAmount(e.target.value)}
          placeholder={t('Částka (nepovinné)', 'Amount (optional)')}
          aria-label={t('Částka k ověření', 'Amount to probe')}
          style={{ maxWidth: '200px' }}
        />
        <button className="btn btn-primary" onClick={run} disabled={!capability}>
          {t('Ověřit', 'Probe')}
        </button>
      </div>}

      {failed && (
        <p style={{ marginTop: '10px', fontSize: '13px', color: 'var(--text-tertiary)' }}>
          {t('Ověření se teď nepodařilo provést.', 'The probe could not be run right now.')}
        </p>
      )}

      {outcome && (
        <div style={{ marginTop: '12px', fontSize: '13px' }}>
          <strong>{outcome.granted ? t('Aktuální přístup vyhovuje', 'Current access is eligible') : t('Zamítnuto', 'Denied')}</strong>
          {outcome.code && <span style={{ marginLeft: '8px', color: 'var(--text-tertiary)' }}>{outcome.code}</span>}
          {outcome.reason && <div style={{ color: 'var(--text-tertiary)', marginTop: '4px' }}>{outcome.reason}</div>}
        </div>
      )}
    </div>
  )
}
