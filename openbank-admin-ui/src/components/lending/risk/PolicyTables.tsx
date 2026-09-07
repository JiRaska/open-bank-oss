// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

import { useLanguage } from '@/lib/i18n/LanguageContext'
import { StatusBadge } from '@/components/ui'
import type { Policy, PolicyRule } from './model'

const OPERATOR_GLYPH: Record<string, string> = {
  GT: '>', GTE: '≥', LT: '<', LTE: '≤', EQ: '=', NEQ: '≠', IN: '∈', NOT_IN: '∉',
}

const KIND_LABEL_CS: Record<string, string> = {
  EXCLUSION: 'Vyloučení', ELIGIBILITY: 'Způsobilost', AFFORDABILITY: 'Bonita', PRICING_BAND: 'Cenové pásmo',
}
const KIND_LABEL_EN: Record<string, string> = {
  EXCLUSION: 'Exclusion', ELIGIBILITY: 'Eligibility', AFFORDABILITY: 'Affordability', PRICING_BAND: 'Pricing band',
}

/** The rule as a credit officer would read it: `DSTI ≤ 0.45`, `RESIDENCY ∈ {CZ, DE, SK}`. */
export function ruleCondition(rule: PolicyRule, numberLocale: string): string {
  const op = OPERATOR_GLYPH[rule.operator] ?? rule.operator
  if (rule.threshold !== null) return `${rule.attribute} ${op} ${rule.threshold.toLocaleString(numberLocale, { maximumFractionDigits: 4 })}`
  if (rule.values.length) return `${rule.attribute} ${op} {${rule.values.join(', ')}}`
  return `${rule.attribute} ${op}`
}

type Props = {
  policy: Policy
  /** Matched-rule counts from the loaded decisions — how often each rule actually fired. */
  hits: Map<string, number>
  /** How many decisions the hit counts were taken from (so the column can say "of N"). */
  sample: number
}

/**
 * The decision tables themselves, in the order the engine evaluates them (ADR-0213 D2), with a
 * hit count per rule. A table a risk committee cannot read is a policy nobody governs; a rule
 * that never fires is either redundant or starved of data — both are things this column shows.
 */
export function PolicyTables({ policy, hits, sample }: Props) {
  const { t, language } = useLanguage()
  const numberLocale = language === 'cs' ? 'cs-CZ' : 'en-GB'
  const kindLabel = (k: string) => (language === 'cs' ? KIND_LABEL_CS[k] : KIND_LABEL_EN[k]) ?? k
  const order = ['EXCLUSION', 'ELIGIBILITY', 'AFFORDABILITY', 'PRICING_BAND']
  const tables = [...policy.tables].sort((a, b) => order.indexOf(a.kind) - order.indexOf(b.kind) || b.version - a.version)
  const th = { padding: '8px 12px', fontSize: 11, color: 'var(--text-tertiary)', textAlign: 'left' } as const
  const td = { padding: '8px 12px', fontSize: 13, verticalAlign: 'top' } as const

  return (
    <div style={{ display: 'grid', gap: 16 }}>
      {tables.map(table => (
        <div key={`${table.kind}-${table.name}-${table.version}`} className="card" style={{ padding: 0, overflow: 'hidden' }} data-testid={`policy-table-${table.kind}`}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 10, padding: '10px 12px', borderBottom: '1px solid var(--border)', flexWrap: 'wrap' }}>
            <strong style={{ fontSize: 13 }}>{kindLabel(table.kind)}</strong>
            <code style={{ fontSize: 11, color: 'var(--text-tertiary)' }}>{table.name}</code>
            <StatusBadge status={`v${table.version}`} tone="neutral" />
            <span style={{ fontSize: 11, color: 'var(--text-tertiary)' }}>
              {t('platné od', 'effective from')} {new Date(table.effectiveFrom).toLocaleDateString(numberLocale)}
              {table.effectiveTo ? ` — ${new Date(table.effectiveTo).toLocaleDateString(numberLocale)}` : ''}
            </span>
          </div>
          <table style={{ width: '100%', borderCollapse: 'collapse' }}>
            <thead>
              <tr style={{ background: 'var(--surface-2)' }}>
                <th style={th}>{t('Pravidlo', 'Rule')}</th>
                <th style={th}>{t('Podmínka', 'Condition')}</th>
                <th style={th}>{table.kind === 'PRICING_BAND' ? t('Pásmo', 'Band') : t('Důvod', 'Reason')}</th>
                <th style={{ ...th, textAlign: 'right' }} title={t(`z ${sample} načtených rozhodnutí`, `of ${sample} loaded decisions`)}>
                  {t('Zásahy', 'Hits')}
                </th>
              </tr>
            </thead>
            <tbody>
              {table.rules.map(rule => {
                const n = hits.get(rule.id) ?? 0
                return (
                  <tr key={rule.id} style={{ borderTop: '1px solid var(--border)' }}>
                    <td style={td}><code style={{ fontSize: 12 }}>{rule.id}</code></td>
                    <td style={{ ...td, fontWeight: 600 }}>{ruleCondition(rule, numberLocale)}</td>
                    <td style={{ ...td, color: 'var(--text-secondary)' }}>
                      {table.kind === 'PRICING_BAND' ? <StatusBadge status={rule.band ?? '—'} tone="accent" /> : (rule.detail || '—')}
                    </td>
                    <td style={{ ...td, textAlign: 'right', fontVariantNumeric: 'tabular-nums', color: n === 0 && sample > 0 ? 'var(--warning-text)' : undefined }}
                      title={n === 0 && sample > 0 ? t('Nikdy nezasáhlo v načteném vzorku — nadbytečné, nebo bez dat.', 'Never fired in the loaded sample — redundant, or starved of data.') : undefined}>
                      {sample === 0 ? '—' : n.toLocaleString(numberLocale)}
                    </td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        </div>
      ))}
    </div>
  )
}
