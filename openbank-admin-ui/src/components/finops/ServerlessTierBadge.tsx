// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

import { useLanguage } from '@/lib/i18n/LanguageContext'
import { serverlessTierFor, TIER_MECHANISM, type Tier, type TierStatus } from '@/lib/finops/serverlessTiers'

// Visual treatment per status. "planned" is the "to be serverless" state — dashed,
// amber — to read as a roadmap target rather than a live capability.
const STATUS_STYLE: Record<TierStatus, { fg: string; bg: string; border: string; dashed?: boolean }> = {
  live:      { fg: 'var(--success-text)', bg: 'var(--success-bg)', border: 'var(--success-border)' },
  planned:   { fg: 'var(--warning-text)', bg: 'var(--warning-bg)', border: 'var(--warning-border)', dashed: true },
  candidate: { fg: 'var(--text-secondary)', bg: 'var(--surface-2)', border: 'var(--border)', dashed: true },
  always_on: { fg: 'var(--text-secondary)', bg: 'var(--surface-2)', border: 'var(--border)' },
}

function tierLabel(tier: Tier, t: (cs: string, en: string) => string): string {
  switch (tier) {
    case 'T0': return t('Vždy běží', 'Always-on')
    case 'T1': return t('HTTP→0', 'HTTP→0')
    case 'T2': return t('Event→0', 'Event→0')
    case 'T3': return t('Cron', 'Cron')
  }
}

function statusLabel(status: TierStatus, t: (cs: string, en: string) => string): string {
  switch (status) {
    case 'live':      return t('Serverless', 'Serverless')
    case 'planned':   return t('Bude serverless', 'To be serverless')
    case 'candidate': return t('Kandidát', 'Candidate')
    case 'always_on': return t('Money-path', 'Money-path')
  }
}

function statusTooltip(status: TierStatus, t: (cs: string, en: string) => string): string {
  switch (status) {
    case 'live':      return t('Reálně škáluje na nulu v sandboxu', 'Actually scales to zero in the sandbox')
    case 'planned':   return t('Návrh hotový, implementace plánovaná', 'Designed, implementation planned')
    case 'candidate': return t('Vhodný kandidát, zatím bez plánu', 'Eligible candidate, no plan committed yet')
    case 'always_on': return t('Vždy běží dle politiky (money-path)', 'Always-on by policy (money-path)')
  }
}

/**
 * Small pill showing a service's FinOps serverless tier + status (ADR-0057/0059).
 * Renders nothing for unclassified services. `dense` trims it for inline use on cards.
 */
export function ServerlessTierBadge({ serviceId, dense = false }: { serviceId: string; dense?: boolean }) {
  const { t } = useLanguage()
  const info = serverlessTierFor(serviceId)
  if (!info) return null

  const s = STATUS_STYLE[info.status]
  const mech = TIER_MECHANISM[info.tier]
  const title = [
    `${statusLabel(info.status, t)} · ${info.tier} ${tierLabel(info.tier, t)}`,
    statusTooltip(info.status, t),
    t('Mechanismus', 'Mechanism') + ': ' + mech.tech,
    info.adr ? `(${info.adr})` : '',
  ].filter(Boolean).join(' — ')

  return (
    <span
      title={title}
      aria-label={title}
      style={{
        display: 'inline-flex', alignItems: 'center', gap: '5px',
        padding: dense ? '1px 7px' : '2px 9px',
        fontSize: dense ? '10px' : '11px', fontWeight: 600, lineHeight: 1.6,
        color: s.fg, background: s.bg,
        border: `1px ${s.dashed ? 'dashed' : 'solid'} ${s.border}`,
        borderRadius: '12px', whiteSpace: 'nowrap',
      }}
    >
      <span aria-hidden style={{ fontSize: dense ? '9px' : '10px' }}>
        {info.status === 'always_on' ? '●' : info.status === 'live' ? '⚡' : '◌'}
      </span>
      {statusLabel(info.status, t)}
      <span style={{ fontWeight: 500 }}>· {tierLabel(info.tier, t)}</span>
    </span>
  )
}
