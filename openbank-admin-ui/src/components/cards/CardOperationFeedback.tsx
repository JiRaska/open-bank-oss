// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// The outcome banner for an operator-initiated card WRITE.
//
// Deliberately not <DataUnavailable>: that panel answers "this data isn't here"
// for a read. A write has three outcomes an operator must tell apart — it worked,
// it is parked for a second approver (ADR-0155 four-eyes), or it was refused — and
// the text is already classified + localized by useCardOperations().failureCopy.

'use client'

import { AlertTriangle, CheckCircle2, Info } from 'lucide-react'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import type { Feedback } from '@/lib/cards/useCardOperations'

const TONE = {
  ok: { bg: 'var(--success-bg)', border: 'var(--success-border)', color: 'var(--success-text)' },
  info: { bg: 'var(--accent-bg)', border: 'var(--accent-border)', color: 'var(--accent-text)' },
  error: { bg: 'var(--danger-bg)', border: 'var(--danger-border)', color: 'var(--danger-text)' },
}

export function CardOperationFeedback({
  feedback, onDismiss,
}: {
  feedback: Feedback | null
  onDismiss: () => void
}) {
  const { t } = useLanguage()
  if (!feedback) return null
  const tone = TONE[feedback.tone]
  const Icon = feedback.tone === 'ok' ? CheckCircle2 : feedback.tone === 'info' ? Info : AlertTriangle
  return (
    <div
      role="status"
      style={{
        display: 'flex', alignItems: 'flex-start', gap: '8px', marginBottom: '16px',
        padding: '10px 14px', borderRadius: '8px', fontSize: '12.5px',
        background: tone.bg, border: `1px solid ${tone.border}`, color: tone.color,
      }}
    >
      <Icon size={15} style={{ flexShrink: 0, marginTop: '1px' }} />
      <span style={{ flex: 1 }}>{feedback.text}</span>
      <button
        type="button"
        onClick={onDismiss}
        aria-label={t('Zavřít zprávu', 'Dismiss message')}
        style={{ background: 'none', border: 'none', cursor: 'pointer', color: 'inherit', lineHeight: 1, padding: 0 }}
      >{'×'}</button>
    </div>
  )
}
