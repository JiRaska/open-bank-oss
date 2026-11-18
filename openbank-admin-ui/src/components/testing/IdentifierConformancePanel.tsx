// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

'use client'

import { useLanguage } from '@/lib/i18n/LanguageContext'
import { stateLabel } from '@/lib/test-intelligence-last-run'
import { StatusBadge, type Tone } from '@/components/ui'
import type { IdentifierConformanceEvidence, IdentifierValidatorConformance } from '@/lib/types/test-intelligence'

const th = { padding: '10px 12px', textAlign: 'left' as const, borderBottom: '1px solid var(--border)', color: 'var(--text-tertiary)', fontSize: 11 }
const td = { padding: '10px 12px', borderBottom: '1px solid var(--border-subtle)', verticalAlign: 'top' as const }

function rowTone(row: IdentifierValidatorConformance): Tone {
  if (row.status === 'unverified-ad-hoc-copy') return 'warning'
  switch (row.state) {
    case 'passed': return 'success'
    case 'failed': return 'danger'
    case 'stale': case 'skipped': case 'blocked': return 'warning'
    default: return 'neutral'
  }
}

function resultTone(result: string): Tone {
  return result === 'pass' ? 'success' : result === 'fail' ? 'danger' : 'neutral'
}

/** Shown wherever fuzz evidence appears: a crash-free fuzzer is not a correctness proof. */
export function RobustnessOnlyLabel() {
  const { t } = useLanguage()
  return <StatusBadge status={null} tone="warning" label={t('jen robustnost — není důkaz shody', 'robustness only — not conformance evidence')} />
}

export function IdentifierConformancePanel({ evidence }: { evidence: IdentifierConformanceEvidence | undefined }) {
  const { t, language } = useLanguage()
  if (!evidence || evidence.state !== 'present') {
    return <div role="status" data-testid="identifier-conformance-absent" style={{ padding: 16, border: '1px solid var(--border)', borderRadius: 10, background: 'var(--surface-2)', color: 'var(--text-secondary)', fontSize: 12 }}>
      <StatusBadge status={null} tone="neutral" label={t('Žádný důkaz', 'No evidence')} />{' '}
      {t('Tento snapshot nenese matici shody identifikátorů z běhu na main; shoda se specifikací není prokázána.', 'This snapshot carries no identifier-conformance matrix from a main run; spec conformance is not evidenced.')}
    </div>
  }
  const format = (at: string) => new Date(at).toLocaleString(language === 'cs' ? 'cs-CZ' : 'en-GB', { dateStyle: 'medium', timeStyle: 'short' })
  return <div style={{ display: 'grid', gap: 12 }}>
    <div style={{ padding: 13, border: '1px solid var(--border)', borderRadius: 10, background: 'var(--surface-2)', color: 'var(--text-secondary)', fontSize: 12 }}>
      <strong style={{ color: 'var(--text-primary)' }}>{t('Shoda se specifikací je samostatný rozměr.', 'Spec conformance is a separate dimension.')}</strong>{' '}
      {t('Validátor může projít testy i pokrytím a přesto být chybný. Neověřená kopie se nikdy nezobrazí jako prošlá a neproběhlý diferenciál není úspěch.', 'A validator can pass execution and coverage and still be wrong. An unverified copy never reads as passing, and a differential that did not run is not a pass.')}
    </div>
    <div style={{ overflowX: 'auto', border: '1px solid var(--border)', borderRadius: 10 }}><table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 12 }}>
      <thead><tr>
        <th style={th}>{t('Validátor', 'Validator')}</th><th style={th}>{t('Verdikt', 'Verdict')}</th><th style={th}>{t('Vektory', 'Vectors')}</th>
        <th style={th}>{t('Diferenciál', 'Differential')}</th><th style={th}>{t('Poslední běh', 'Last run')}</th><th style={th}>Fuzz</th>
        <th style={th}>Pitest</th><th style={th}>{t('Známé vady', 'Known defects')}</th>
      </tr></thead>
      <tbody>{evidence.validators.map(row => <tr key={`${row.identifier}:${row.module}:${row.symbol}`} data-testid={`conformance-${row.identifier}-${row.module}`}>
        <td style={td}><strong>{row.identifier}</strong><div style={{ color: 'var(--text-tertiary)', fontSize: 11 }}>{row.module} · {row.symbol}</div></td>
        <td style={td}><StatusBadge status={null} tone={rowTone(row)} withDot label={row.status === 'unverified-ad-hoc-copy'
          ? t('Neověřeno (ad-hoc kopie)', 'Unverified (ad-hoc copy)')
          : row.status === 'known-defect' && row.state !== 'failed' ? t('Známá vada', 'Known defect') : stateLabel(row.state, language)} /></td>
        <td style={td}>{row.vectors.count}{row.vectors.count > 0 && <> · <StatusBadge status={null} tone={resultTone(row.vectors.result)} label={row.vectors.result} /></>}
          {row.vectors.sources.length > 0 && <div style={{ color: 'var(--text-tertiary)', fontSize: 11 }}>{row.vectors.sources.map(source => <div key={source}>{source}</div>)}</div>}</td>
        <td style={td}>{row.differential.result === 'no-reference'
          ? <StatusBadge status={null} tone="neutral" label={t('bez reference', 'no reference')} />
          : <StatusBadge status={null} tone={resultTone(row.differential.result)} label={row.differential.result} />}
          {row.differential.reference && <div style={{ color: 'var(--text-tertiary)', fontSize: 11 }}>{row.differential.reference}</div>}</td>
        <td style={td}>{row.lastRun.at ? format(row.lastRun.at) : '—'}{row.lastRun.url && <div><a href={row.lastRun.url} target="_blank" rel="noreferrer">{t('běh', 'run')}</a></div>}</td>
        <td style={td}>{row.fuzz.fuzzer ?? '—'}{row.fuzz.robustnessOnly && <div><RobustnessOnlyLabel /></div>}</td>
        <td style={td}>{row.pitest}</td>
        <td style={td}>{row.knownDefects.length ? row.knownDefects.join(', ') : '—'}</td>
      </tr>)}</tbody>
    </table></div>
  </div>
}
