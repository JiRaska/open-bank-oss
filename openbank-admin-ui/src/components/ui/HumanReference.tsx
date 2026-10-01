// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// A human label first, the machine reference second (small, monospace, copyable).
//
// The rule it encodes: a UUID is never the thing a person reads. Whatever a row is about is named
// by something the reader recognises — a product and maturity, a contract number, a customer — and
// the raw id is still on the page, shortened and one click from the clipboard, for the operator who
// needs to paste it into a ticket or a log search. For a reference the BFF can RESOLVE (a party, an
// account), prefer `EntityChip`, which fetches the name and links to the entity; this component is
// for references that have no page of their own or whose label the caller already knows.

'use client'

import { useState } from 'react'
import { Copy, Check } from 'lucide-react'
import { useLanguage } from '@/lib/i18n/LanguageContext'

export function shortReference(ref: string): string {
  return ref.length > 12 ? `${ref.slice(0, 8)}…` : ref
}

export function HumanReference({ label, reference, sublabel }: { label: string; reference?: string | null; sublabel?: string }) {
  const { t } = useLanguage()
  const [copied, setCopied] = useState(false)
  const copy = async () => {
    if (!reference) return
    try {
      await navigator.clipboard.writeText(reference)
      setCopied(true)
      setTimeout(() => setCopied(false), 1500)
    } catch {
      // Clipboard can be unavailable (insecure context, denied permission): the full id is still
      // in the title tooltip, so there is nothing further to do.
    }
  }
  return (
    <span style={{ display: 'inline-flex', flexDirection: 'column', gap: 2 }}>
      <span>{label}</span>
      {sublabel && <span style={{ fontSize: 11, color: 'var(--text-secondary)' }}>{sublabel}</span>}
      {reference && (
        <button
          type="button"
          onClick={copy}
          title={reference}
          aria-label={t(`Kopírovat referenci ${reference}`, `Copy reference ${reference}`)}
          data-reference={reference}
          style={{
            display: 'inline-flex', alignItems: 'center', gap: 4, fontSize: 11, fontFamily: 'var(--font-mono, monospace)',
            color: 'var(--text-secondary)', background: 'none', border: 0, padding: 0, cursor: 'pointer', width: 'fit-content',
          }}
        >
          {shortReference(reference)}
          {copied ? <Check size={11} aria-hidden="true" /> : <Copy size={11} aria-hidden="true" />}
        </button>
      )}
    </span>
  )
}
