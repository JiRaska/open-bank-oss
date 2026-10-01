// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// A regulatory table read by CATEGORY, with the underlying items one click away.
//
// One row per category (name, count, amounts, factor, basis in plain words); the per-contract
// detail is rendered only when the row is opened, so a 44-loan category costs one row until the
// reader asks for the 44. Every column is its own cell with its own alignment — the regulatory
// basis sits in a separate, left-aligned column, never glued onto a right-aligned number.

'use client'

import { Fragment, useState, type ReactNode } from 'react'
import { ChevronDown, ChevronRight } from 'lucide-react'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import type { PlainCitation } from '@/lib/risk/labels'

export type CategoryColumn = { key: string; header: string; numeric?: boolean }
export type CategoryRow = {
  key: string
  label: string
  /** e.g. "44 úvěrů" — what the category is made of. */
  countText: string
  /** One value per column in `columns`, same order. */
  values: ReactNode[]
  basis: PlainCitation
  /** Rendered lazily on expand; each item is [label node, ...one value per column]. */
  items?: () => { key: string; label: ReactNode; values: ReactNode[] }[]
}

const cell = { padding: '6px 8px', borderBottom: '1px solid var(--border)', verticalAlign: 'top' } as const
const num = { ...cell, textAlign: 'right', whiteSpace: 'nowrap', fontVariantNumeric: 'tabular-nums' } as const
const txt = { ...cell, textAlign: 'left' } as const

export function CategoryTable({ caption, columns, rows, totalLabel, totals }: {
  caption: string
  columns: CategoryColumn[]
  rows: CategoryRow[]
  totalLabel?: string
  /** One value per column (null leaves the cell empty). */
  totals?: (ReactNode | null)[]
}) {
  const { t } = useLanguage()
  const [open, setOpen] = useState<Set<string>>(new Set())
  const toggle = (k: string) => setOpen(prev => {
    const next = new Set(prev)
    if (next.has(k)) next.delete(k)
    else next.add(k)
    return next
  })
  return (
    <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 13 }} aria-label={caption}>
      <thead>
        <tr>
          <th scope="col" style={txt}>{t('Kategorie', 'Category')}</th>
          {columns.map(c => <th key={c.key} scope="col" style={c.numeric ? num : txt}>{c.header}</th>)}
          <th scope="col" style={txt}>{t('Regulatorní základ', 'Regulatory basis')}</th>
        </tr>
      </thead>
      <tbody>
        {rows.map(r => {
          const isOpen = open.has(r.key)
          const canOpen = !!r.items
          return (
            <Fragment key={r.key}>
              <tr data-category={r.key}>
                <td style={txt}>
                  {canOpen ? (
                    <button
                      type="button"
                      onClick={() => toggle(r.key)}
                      aria-expanded={isOpen}
                      style={{ display: 'inline-flex', gap: 4, alignItems: 'flex-start', background: 'none', border: 0, padding: 0, cursor: 'pointer', textAlign: 'left', color: 'inherit', font: 'inherit' }}
                    >
                      {isOpen ? <ChevronDown size={14} aria-hidden="true" /> : <ChevronRight size={14} aria-hidden="true" />}
                      <span><strong style={{ fontWeight: 500 }}>{r.label}</strong><br /><span style={{ fontSize: 11, color: 'var(--text-secondary)' }}>{r.countText}</span></span>
                    </button>
                  ) : (
                    <span><strong style={{ fontWeight: 500 }}>{r.label}</strong><br /><span style={{ fontSize: 11, color: 'var(--text-secondary)' }}>{r.countText}</span></span>
                  )}
                </td>
                {r.values.map((v, i) => <td key={columns[i]?.key ?? i} style={columns[i]?.numeric ? num : txt}>{v}</td>)}
                <td style={{ ...txt, fontSize: 11, color: 'var(--text-secondary)' }} title={r.basis.raw}>{r.basis.article}</td>
              </tr>
              {isOpen && r.items && r.items().map(item => (
                <tr key={`${r.key}:${item.key}`} data-item-of={r.key} style={{ background: 'var(--bg-subtle, transparent)' }}>
                  <td style={{ ...txt, paddingLeft: 26, fontSize: 12 }}>{item.label}</td>
                  {item.values.map((v, i) => <td key={columns[i]?.key ?? i} style={{ ...(columns[i]?.numeric ? num : txt), fontSize: 12 }}>{v}</td>)}
                  <td style={cell} />
                </tr>
              ))}
            </Fragment>
          )
        })}
        {totals && (
          <tr style={{ fontWeight: 600 }}>
            <td style={txt}>{totalLabel ?? t('Celkem', 'Total')}</td>
            {totals.map((v, i) => <td key={columns[i]?.key ?? i} style={columns[i]?.numeric ? num : txt}>{v}</td>)}
            <td style={cell} />
          </tr>
        )}
      </tbody>
    </table>
  )
}
