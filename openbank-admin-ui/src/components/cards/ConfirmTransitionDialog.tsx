// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Confirmation for the two irreversible transitions (block, cancel).
//
// The reason is REQUIRED, not optional: `Card.block()` requires a non-blank reason,
// and `Card.cancel()` writes whatever it is given onto `blockedReason` — the
// aggregate's single "why is this card not usable" note, which is what the customer
// service agent reads back to the customer months later. An empty reason is a card
// nobody can explain.

'use client'

import { useRef, useState } from 'react'
import { AlertTriangle } from 'lucide-react'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import type { CardTransition } from '@/lib/cards/lifecycle'
import type { Card } from '@/lib/cards/types'
import { trapDialogFocus } from '@/lib/a11y/trapDialogFocus'
import { CardStatusChip } from './CardStatusChip'

export function ConfirmTransitionDialog({
  card, transition, busy, onCancel, onConfirm,
}: {
  card: Card
  transition: CardTransition
  busy: boolean
  onCancel: () => void
  onConfirm: (reason: string) => void
}) {
  const { t } = useLanguage()
  const dialogRef = useRef<HTMLDivElement>(null)
  const [reason, setReason] = useState('')
  const label = transition.action === 'block'
    ? t('Blokovat kartu', 'Block card')
    : t('Zrušit kartu', 'Cancel card')

  return (
    <div
      ref={dialogRef}
      role="dialog"
      aria-modal="true"
      aria-label={label}
      aria-busy={busy}
      onKeyDown={e => {
        if (e.key === 'Escape' && !busy) onCancel()
        trapDialogFocus(e, dialogRef.current)
      }}
      style={{
        position: 'fixed', inset: 0, zIndex: 60, background: 'rgba(15,23,42,0.45)',
        display: 'flex', alignItems: 'center', justifyContent: 'center', padding: '24px',
      }}
    >
      <div className="card" style={{ width: '100%', maxWidth: '460px', padding: '22px 24px', background: 'var(--surface-1)' }}>
        <div style={{ display: 'flex', gap: '10px', alignItems: 'flex-start', marginBottom: '14px' }}>
          <AlertTriangle size={18} aria-hidden="true" style={{ color: 'var(--danger)', flexShrink: 0, marginTop: '2px' }} />
          <div>
            <div style={{ fontSize: '15px', fontWeight: 700, color: 'var(--text-primary)' }}>{label}</div>
            <div style={{ fontSize: '12px', color: 'var(--text-secondary)', marginTop: '4px' }}>
              {t('Tuto operaci nelze vzít zpět.', 'This operation cannot be undone.')}
            </div>
          </div>
        </div>

        <div style={{
          display: 'flex', alignItems: 'center', gap: '8px', flexWrap: 'wrap',
          padding: '10px 12px', borderRadius: '8px', background: 'var(--surface-2)', marginBottom: '14px',
        }}>
          <span style={{ fontFamily: 'var(--font-mono)', fontSize: '12px', color: 'var(--text-primary)' }}>{card.maskedPan}</span>
          <CardStatusChip status={card.status} small />
          <span style={{ fontSize: '12px', color: 'var(--text-tertiary)' }}>{'→'}</span>
          <CardStatusChip status={transition.to} small />
        </div>

        <label style={{ display: 'block', fontSize: '12px', fontWeight: 600, color: 'var(--text-secondary)', marginBottom: '6px' }}>
          {t('Důvod (povinný, zapíše se do auditu karty)', 'Reason (required, recorded on the card’s audit trail)')}
        </label>
        <textarea
          value={reason}
          onChange={e => setReason(e.target.value)}
          rows={3}
          autoFocus
          aria-label={t('Důvod operace', 'Reason for the operation')}
          style={{
            width: '100%', padding: '8px 10px', borderRadius: '6px', border: '1px solid var(--border)',
            fontSize: '13px', background: 'var(--surface-2)', color: 'var(--text-primary)',
            outline: 'none', resize: 'vertical', fontFamily: 'inherit',
          }}
        />

        <div style={{ display: 'flex', justifyContent: 'flex-end', gap: '8px', marginTop: '16px' }}>
          <button type="button" className="btn btn-ghost btn-sm" onClick={onCancel} disabled={busy}>
            {t('Zpět', 'Back')}
          </button>
          <button
            type="button"
            className="btn btn-danger btn-sm"
            disabled={busy || reason.trim().length === 0}
            aria-busy={busy}
            onClick={() => onConfirm(reason.trim())}
          >
            {busy
              ? t('Odesílám…', 'Submitting…')
              : transition.action === 'block'
                ? t('Potvrdit blokaci', 'Confirm block')
                : t('Potvrdit zrušení', 'Confirm cancellation')}
          </button>
        </div>
      </div>
    </div>
  )
}
