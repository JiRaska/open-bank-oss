// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// The four channel controls — contactless, online, ATM, abroad.
//
// `PUT /{id}/controls` takes ALL FOUR every time (UpdateControlsRequest has no
// optional field), so the panel edits a complete set locally and sends the whole
// set: patching one toggle by sending one field would silently re-enable the other
// three on the server's side of the request. The same status guard as limits
// applies (Card.withControls), so a dead card's toggles are read-only, with the
// reason on screen.

'use client'

import { useState } from 'react'
import { Globe, Landmark, RefreshCw, Save, ShoppingCart, Wifi } from 'lucide-react'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { settingsBlock } from '@/lib/cards/rules'
import { controlsOf, type Card, type CardControls } from '@/lib/cards/types'

const ICONS = {
  contactlessEnabled: Wifi,
  onlineEnabled: ShoppingCart,
  atmEnabled: Landmark,
  abroadEnabled: Globe,
} as const

export function CardControlsPanel({
  card, busy, onSave,
}: {
  card: Card
  busy: string | null
  onSave: (controls: CardControls) => Promise<boolean>
}) {
  const { t } = useLanguage()
  const server = controlsOf(card)
  const [draft, setDraft] = useState<CardControls>(server)

  // Seeded once per mount; the parent remounts with a `key` carrying the server's
  // four flags when the card changes underneath us (see CardLimitsPanel's note).

  const block = settingsBlock(card.status)
  const dirty = (Object.keys(draft) as (keyof CardControls)[]).some(k => draft[k] !== server[k])
  const saving = busy === `${card.id}:controls`

  const rows: { key: keyof CardControls; label: string; hint: string }[] = [
    {
      key: 'contactlessEnabled',
      label: t('Bezkontaktní platby', 'Contactless'),
      hint: t('Platby přiložením u terminálu.', 'Tap-to-pay at a terminal.'),
    },
    {
      key: 'onlineEnabled',
      label: t('Platby na internetu', 'Online payments'),
      hint: t('E-commerce a platby bez přítomnosti karty.', 'E-commerce and card-not-present payments.'),
    },
    {
      key: 'atmEnabled',
      label: t('Výběry z bankomatu', 'ATM withdrawals'),
      hint: t('Výběry hotovosti.', 'Cash withdrawals.'),
    },
    {
      key: 'abroadEnabled',
      label: t('Použití v zahraničí', 'Use abroad'),
      hint: t('Transakce mimo domovskou zemi.', 'Transactions outside the home country.'),
    },
  ]

  return (
    <div className="card" style={{ padding: '16px 20px' }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: '8px', marginBottom: '14px' }}>
        <Wifi size={15} style={{ color: 'var(--accent)' }} />
        <span style={{ fontSize: '13px', fontWeight: 700, color: 'var(--text-primary)' }}>{t('Kanály karty', 'Channel controls')}</span>
      </div>

      <div style={{ display: 'grid', gap: '8px' }}>
        {rows.map(({ key, label, hint }) => {
          const Icon = ICONS[key]
          const on = draft[key]
          return (
            <button
              key={key}
              type="button"
              role="switch"
              aria-checked={on}
              aria-label={label}
              disabled={block !== null || busy !== null}
              onClick={() => setDraft(d => ({ ...d, [key]: !d[key] }))}
              style={{
                display: 'flex', alignItems: 'center', gap: '10px', width: '100%', textAlign: 'left',
                padding: '9px 11px', borderRadius: '8px', background: 'var(--surface-2)',
                border: `1px solid ${on ? 'var(--success-border)' : 'var(--border)'}`,
                cursor: block ? 'not-allowed' : 'pointer', opacity: block ? 0.6 : 1,
              }}
            >
              <Icon size={14} style={{ color: on ? 'var(--success)' : 'var(--text-tertiary)', flexShrink: 0 }} />
              <span style={{ flex: 1 }}>
                <span style={{ display: 'block', fontSize: '12.5px', fontWeight: 600, color: 'var(--text-primary)' }}>{label}</span>
                <span style={{ display: 'block', fontSize: '11px', color: 'var(--text-tertiary)' }}>{hint}</span>
              </span>
              <span style={{
                width: '34px', height: '18px', borderRadius: '999px', flexShrink: 0, position: 'relative',
                background: on ? 'var(--success)' : 'var(--border-strong)', transition: 'background 0.15s',
              }}>
                <span style={{
                  position: 'absolute', top: '2px', left: on ? '18px' : '2px', width: '14px', height: '14px',
                  borderRadius: '50%', background: 'var(--surface-1)', transition: 'left 0.15s',
                }} />
              </span>
            </button>
          )
        })}
      </div>

      {block && (
        <div style={{ fontSize: '11.5px', color: 'var(--text-secondary)', marginTop: '10px' }}>
          {block === 'terminal'
            ? t('Karta je v koncovém stavu — kanály už nelze měnit.', 'The card is in a terminal state — its channels can no longer be changed.')
            : block === 'blocked'
              ? t('Blokovaná karta netransaguje na žádném kanálu; přepínače proto nic nezmění.', 'A blocked card transacts on no channel, so the toggles would change nothing.')
              : t('Stav karty neumožňuje změnu kanálů.', 'The card’s status does not allow a channel change.')}
        </div>
      )}

      <div style={{ display: 'flex', justifyContent: 'flex-end', marginTop: '14px' }}>
        <button
          type="button"
          className="btn btn-primary btn-sm"
          disabled={busy !== null || block !== null || !dirty}
          aria-busy={saving}
          onClick={() => void onSave(draft)}
        >
          {saving
            ? <RefreshCw size={12} aria-hidden="true" style={{ animation: 'spin 0.8s linear infinite' }} />
            : <Save size={12} aria-hidden="true" />}
          {saving ? t('Ukládám kanály…', 'Saving channels…') : t('Uložit kanály', 'Save channels')}
        </button>
      </div>
    </div>
  )
}
