// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

import { useState } from 'react'
import { Copy, Check } from 'lucide-react'

type HumanReferenceProps = {
  /** What a person reads first — "Zápis č. 4812", "Vypořádání". */
  label: string
  /** The machine identifier (a UUID, a key) — shown small and shortened, copied in full. */
  reference: string
  /** Accessible name of the copy button, already translated. */
  copyLabel: string
  /** Optional link for the label. */
  href?: string
}

/** First 8 characters of a UUID-like reference, enough to recognise and search for it. */
export function shortReference(ref: string): string {
  return ref.length > 12 ? `${ref.slice(0, 8)}…` : ref
}

/**
 * A human label with its machine reference beside it, small and copyable (owner feedback: a UUID
 * is never the primary content for a business reader). Intended as the shared primitive for every
 * "label + reference" cell; the full reference is in `title` and on the clipboard.
 */
export function HumanReference({ label, reference, copyLabel, href }: HumanReferenceProps) {
  const [copied, setCopied] = useState(false)
  const copy = async () => {
    try {
      await navigator.clipboard.writeText(reference)
      setCopied(true)
      setTimeout(() => setCopied(false), 1500)
    } catch { /* clipboard unavailable — the full value is still in the tooltip */ }
  }
  return (
    <span style={{ display: 'inline-flex', alignItems: 'baseline', gap: 6, flexWrap: 'wrap' }}>
      {href ? <a href={href}>{label}</a> : <span>{label}</span>}
      <span className="mono" title={reference} style={{ fontSize: 11, color: 'var(--text-secondary)' }}>
        {shortReference(reference)}
      </span>
      <button type="button" className="btn btn-ghost" style={{ padding: 2 }} aria-label={copyLabel} title={copyLabel} onClick={() => void copy()}>
        {copied ? <Check size={12} aria-hidden="true" /> : <Copy size={12} aria-hidden="true" />}
      </button>
    </span>
  )
}
