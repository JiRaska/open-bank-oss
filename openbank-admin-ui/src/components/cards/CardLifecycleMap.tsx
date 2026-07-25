// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// The card state machine, drawn.
//
// An operator should be able to read the lifecycle off the screen instead of off
// Card.kt. It is split by REVERSIBILITY, because that is the distinction that
// actually matters at the moment of clicking: the top rail is undo-able, the
// bottom band is not.
//
// Lifted out of the Cards page unchanged so the card detail view can show the same
// diagram with that card's state ringed — one drawing of one state machine.

'use client'

import { useLanguage } from '@/lib/i18n/LanguageContext'
import { CardStatusChip } from './CardStatusChip'

function Arrow({ label, back }: { label: string; back?: boolean }) {
  return (
    <span style={{ display: 'inline-flex', flexDirection: 'column', alignItems: 'center', margin: '0 6px' }}>
      <span style={{ fontSize: '10px', color: 'var(--text-tertiary)', fontWeight: 600, whiteSpace: 'nowrap' }}>{label}</span>
      <span style={{ fontSize: '13px', color: 'var(--border-strong)', lineHeight: 1 }}>{back ? '⇄' : '→'}</span>
    </span>
  )
}

export function CardLifecycleMap({ current, compact }: { current?: string; compact?: boolean }) {
  const { t } = useLanguage()
  const row: React.CSSProperties = { display: 'flex', alignItems: 'center', flexWrap: 'wrap', gap: '4px' }
  const caption: React.CSSProperties = {
    fontSize: '10px', fontWeight: 700, letterSpacing: '0.06em', textTransform: 'uppercase',
    color: 'var(--text-tertiary)', marginBottom: '8px',
  }
  return (
    <div className="card" style={{ padding: '16px 20px', marginBottom: compact ? 0 : '24px' }}>
      <div style={{ display: 'flex', alignItems: 'baseline', justifyContent: 'space-between', gap: '12px', marginBottom: '14px' }}>
        <div style={{ fontSize: '13px', fontWeight: 700, color: 'var(--text-primary)' }}>
          {t('Životní cyklus karty', 'Card lifecycle')}
        </div>
        <div style={{ fontSize: '11px', color: 'var(--text-tertiary)' }}>
          {current
            ? t('Zvýrazněný stav patří vybrané kartě.', 'The highlighted state is the selected card’s.')
            : t('Vyberte kartu v tabulce a její stav se zvýrazní.', 'Select a card below to highlight its state.')}
        </div>
      </div>

      <div style={{ display: 'grid', gap: '14px' }}>
        <div>
          <div style={caption}>{t('Vratné přechody', 'Reversible transitions')}</div>
          <div style={row}>
            <CardStatusChip status="PENDING" current={current === 'PENDING'} />
            <Arrow label={t('aktivovat', 'activate')} />
            <CardStatusChip status="ACTIVE" current={current === 'ACTIVE'} />
            <Arrow label={t('pozastavit / obnovit', 'suspend / resume')} back />
            <CardStatusChip status="SUSPENDED" current={current === 'SUSPENDED'} />
          </div>
        </div>

        <div style={{ borderTop: '1px dashed var(--border)', paddingTop: '12px' }}>
          <div style={caption}>{t('Nevratné přechody — vyžadují potvrzení a důvod', 'Irreversible transitions — confirmation and a reason required')}</div>
          <div style={{ display: 'grid', gap: '8px' }}>
            <div style={row}>
              <CardStatusChip status="ACTIVE" small />
              <CardStatusChip status="SUSPENDED" small />
              <Arrow label={t('blokovat', 'block')} />
              <CardStatusChip status="BLOCKED" current={current === 'BLOCKED'} />
            </div>
            <div style={row}>
              <CardStatusChip status="PENDING" small />
              <CardStatusChip status="ACTIVE" small />
              <CardStatusChip status="SUSPENDED" small />
              <CardStatusChip status="BLOCKED" small />
              <Arrow label={t('zrušit', 'cancel')} />
              <CardStatusChip status="CANCELLED" current={current === 'CANCELLED'} />
            </div>
          </div>
        </div>

        <div style={{ borderTop: '1px dashed var(--border)', paddingTop: '12px', display: 'flex', alignItems: 'center', gap: '8px', flexWrap: 'wrap' }}>
          <CardStatusChip status="EXPIRED" current={current === 'EXPIRED'} />
          <span style={{ fontSize: '11px', color: 'var(--text-tertiary)' }}>
            {t(
              'Stav existuje v modelu, ale card-issuance nemá žádnou úlohu expirace — zatím ho tedy žádná karta nedosáhne.',
              'The status exists in the model, but card-issuance runs no expiry job — no card reaches it today.',
            )}
          </span>
        </div>
      </div>
    </div>
  )
}
