// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// The lifecycle actions a card can take RIGHT NOW — one row, two screens.
//
// The button set is derived from `legalTransitions()` (the mirror of Card.kt's
// guards), never from a hand-written per-screen list: the console offers exactly
// the transitions the aggregate would accept, and nothing else.
//
// When there are none, this says WHY rather than rendering an empty cell. "No
// action" reads as a bug; "terminal state — a cancelled card cannot move again"
// reads as the system working.

'use client'

import { Ban, PauseCircle, PlayCircle, RefreshCw, ShieldX } from 'lucide-react'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { isTerminal, legalTransitions, type CardAction, type CardTransition } from '@/lib/cards/lifecycle'
import type { Card } from '@/lib/cards/types'

const ACTION_ICON: Record<CardAction, React.ElementType> = {
  activate: PlayCircle, resume: PlayCircle, suspend: PauseCircle, block: ShieldX, cancel: Ban,
}

export function CardTransitionButtons({
  card, busy, canManage, canBlock, onSelect,
}: {
  card: Card
  /** `${cardId}:${action}` of the in-flight write, or null. */
  busy: string | null
  /** activate, suspend and resume are ROLE_OPERATOR/ROLE_ADMIN only. */
  canManage: boolean
  /** block and cancel also admit the service-authorized ROLE_COMPLIANCE emergency role. */
  canBlock: boolean
  onSelect: (transition: CardTransition) => void
}) {
  const { t } = useLanguage()
  const transitions = legalTransitions(card.status).filter(tr =>
    tr.action === 'block' || tr.action === 'cancel' ? canBlock : canManage,
  )

  if (transitions.length === 0) {
    return (
      <span style={{ fontSize: '11px', color: 'var(--text-tertiary)' }}>
        {isTerminal(card.status)
          ? t('koncový stav', 'terminal state')
          : t('žádná akce', 'no action')}
      </span>
    )
  }

  const label = (action: CardAction) => ({
    activate: t('Aktivovat', 'Activate'),
    resume: t('Obnovit', 'Resume'),
    suspend: t('Pozastavit', 'Suspend'),
    block: t('Blokovat', 'Block'),
    cancel: t('Zrušit', 'Cancel'),
  }[action])

  return (
    <div style={{ display: 'flex', gap: '6px', flexWrap: 'wrap' }}>
      {transitions.map(tr => {
        const Icon = ACTION_ICON[tr.action]
        const running = busy === `${card.id}:${tr.action}`
        return (
          <button
            key={tr.action}
            type="button"
            className={`btn btn-sm ${tr.irreversible ? 'btn-danger' : 'btn-ghost'}`}
            disabled={busy !== null}
            aria-busy={running}
            title={`${label(tr.action)} → ${tr.to}`}
            onClick={() => onSelect(tr)}
          >
            {running
              ? <RefreshCw size={12} aria-hidden="true" style={{ animation: 'spin 0.8s linear infinite' }} />
              : <Icon size={12} aria-hidden="true" />}
            {label(tr.action)}
          </button>
        )
      })}
    </div>
  )
}
