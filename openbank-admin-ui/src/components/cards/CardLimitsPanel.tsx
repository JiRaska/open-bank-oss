// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Daily / monthly spending limits — the same thing the customer can do in the app,
// done by an operator on the customer's behalf.
//
// Two things this panel refuses to do:
//   1. Show minor units. The API speaks them; a human does not. The operator types
//      "5 000" against a CZK card, not "500000" (see lib/cards/money.ts).
//   2. Offer a Save that can only ever 400. Card.withLimits() requires a live card,
//      non-negative values and daily <= monthly — mirrored in lib/cards/rules.ts and
//      checked here BEFORE the call, with the failing invariant named on screen.

'use client'

import { useState } from 'react'
import { Save, RefreshCw, Wallet } from 'lucide-react'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { minorToMajorString, parseMajorToMinor, formatMinor } from '@/lib/cards/money'
import { settingsBlock, validateLimits } from '@/lib/cards/rules'
import type { Card } from '@/lib/cards/types'

export function CardLimitsPanel({
  card, busy, onSave,
}: {
  card: Card
  busy: string | null
  onSave: (dailyMinorUnits: number, monthlyMinorUnits: number) => Promise<boolean>
}) {
  const { t, language } = useLanguage()
  const locale = language === 'cs' ? 'cs-CZ' : 'en-US'
  const [daily, setDaily] = useState(() => minorToMajorString(card.dailyLimitMinorUnits, card.currency))
  const [monthly, setMonthly] = useState(() => minorToMajorString(card.monthlyLimitMinorUnits, card.currency))

  // NOTE: the fields are seeded ONCE, from the card as it was when this panel
  // mounted. Re-seeding from a changed prop is the parent's job, via a `key` that
  // includes the server's limits — a remount is a cleaner "the server won" than an
  // effect that could stamp on what the operator is mid-way through typing.

  const dailyMinorUnits = parseMajorToMinor(daily, card.currency)
  const monthlyMinorUnits = parseMajorToMinor(monthly, card.currency)
  const violations = validateLimits({ status: card.status, dailyMinorUnits, monthlyMinorUnits })
  const block = settingsBlock(card.status)
  const dirty = dailyMinorUnits !== card.dailyLimitMinorUnits || monthlyMinorUnits !== card.monthlyLimitMinorUnits
  const saving = busy === `${card.id}:limits`

  const blockCopy = block === 'terminal'
    ? t('Karta je v koncovém stavu — limity už nelze měnit.', 'The card is in a terminal state — its limits can no longer be changed.')
    : block === 'blocked'
      ? t('Blokovaná karta nemá co utrácet, limity proto nelze měnit. Nejdřív ji odblokujte, nebo zrušte.', 'A blocked card has nothing left to spend, so its limits cannot be changed. Unblock or cancel it first.')
      : t('Stav karty neumožňuje změnu limitů.', 'The card’s status does not allow a limit change.')

  const field = (
    label: string, value: string, onChange: (v: string) => void, invalid: boolean, aria: string,
  ) => (
    <label style={{ display: 'block' }}>
      <span style={{ display: 'block', fontSize: '11px', fontWeight: 700, letterSpacing: '0.04em', textTransform: 'uppercase', color: 'var(--text-tertiary)', marginBottom: '5px' }}>
        {label}
      </span>
      <input
        value={value}
        inputMode="decimal"
        disabled={block !== null}
        aria-label={aria}
        onChange={e => onChange(e.target.value)}
        style={{
          width: '100%', padding: '7px 10px', borderRadius: '6px', fontSize: '13px',
          border: `1px solid ${invalid ? 'var(--danger-border)' : 'var(--border)'}`,
          background: 'var(--surface-2)', color: 'var(--text-primary)', outline: 'none',
          fontFamily: 'var(--font-mono)', opacity: block ? 0.6 : 1,
        }}
      />
    </label>
  )

  return (
    <div className="card" style={{ padding: '16px 20px' }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: '8px', marginBottom: '14px' }}>
        <Wallet size={15} style={{ color: 'var(--accent)' }} />
        <span style={{ fontSize: '13px', fontWeight: 700, color: 'var(--text-primary)' }}>{t('Limity útraty', 'Spending limits')}</span>
      </div>

      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '12px' }}>
        {field(
          t(`Denní (${card.currency})`, `Daily (${card.currency})`),
          daily, setDaily,
          violations.includes('daily_not_a_number') || violations.includes('daily_exceeds_monthly'),
          t('Denní limit', 'Daily limit'),
        )}
        {field(
          t(`Měsíční (${card.currency})`, `Monthly (${card.currency})`),
          monthly, setMonthly,
          violations.includes('monthly_not_a_number'),
          t('Měsíční limit', 'Monthly limit'),
        )}
      </div>

      <div style={{ fontSize: '11px', color: 'var(--text-tertiary)', marginTop: '8px' }}>
        {t('Nyní platí', 'Currently in force')}{': '}
        {formatMinor(card.dailyLimitMinorUnits, card.currency, locale)}
        {' · '}
        {formatMinor(card.monthlyLimitMinorUnits, card.currency, locale)}
      </div>

      {block ? (
        <div style={{ fontSize: '11.5px', color: 'var(--text-secondary)', marginTop: '10px' }}>{blockCopy}</div>
      ) : (
        <>
          {violations.includes('daily_not_a_number') && (
            <div style={{ fontSize: '11.5px', color: 'var(--danger-text)', marginTop: '8px' }}>
              {t('Denní limit musí být částka v měně karty.', 'The daily limit must be an amount in the card’s currency.')}
            </div>
          )}
          {violations.includes('monthly_not_a_number') && (
            <div style={{ fontSize: '11.5px', color: 'var(--danger-text)', marginTop: '8px' }}>
              {t('Měsíční limit musí být částka v měně karty.', 'The monthly limit must be an amount in the card’s currency.')}
            </div>
          )}
          {violations.includes('daily_exceeds_monthly') && (
            <div style={{ fontSize: '11.5px', color: 'var(--danger-text)', marginTop: '8px' }}>
              {t('Denní limit nesmí být vyšší než měsíční.', 'The daily limit cannot exceed the monthly one.')}
            </div>
          )}
        </>
      )}

      <div style={{ display: 'flex', justifyContent: 'flex-end', gap: '8px', marginTop: '14px' }}>
        <button
          type="button"
          className="btn btn-primary btn-sm"
          disabled={busy !== null || block !== null || violations.length > 0 || !dirty}
          aria-busy={saving}
          onClick={() => { if (dailyMinorUnits !== null && monthlyMinorUnits !== null) void onSave(dailyMinorUnits, monthlyMinorUnits) }}
        >
          {saving
            ? <RefreshCw size={12} aria-hidden="true" style={{ animation: 'spin 0.8s linear infinite' }} />
            : <Save size={12} aria-hidden="true" />}
          {saving ? t('Ukládám limity…', 'Saving limits…') : t('Uložit limity', 'Save limits')}
        </button>
      </div>
    </div>
  )
}
