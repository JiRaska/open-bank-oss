// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

// Renders a report response without inventing anything: a provenance badge, a data-gap banner
// (the service's reason, with every figure it withheld shown as "no figure" — never zero), the
// header facts, scalar figures as a definition list, and each list of records as a table.
import type { ReactNode } from 'react'
import { StatusBadge } from '@/components/ui'
import { SyntheticBadge } from '@/components/treasury/SyntheticBadge'
import { dataGapOf, responseProvenance, type Provenance } from './reports'
import { td, th, useFmt } from './ui'

export function ProvenanceBadge({ provenance }: { provenance: Provenance | null }) {
  const { t } = useFmt()
  if (provenance === null) return <StatusBadge status="UNKNOWN" tone="neutral" label={t('Původ neuveden', 'Provenance not stated')} />
  if (provenance === 'synthetic') return <span style={{ display: 'inline-flex', gap: 6 }}><StatusBadge status="SYNTHETIC_PROVENANCE" tone="warning" label={t('Syntetická', 'Synthetic')} /><SyntheticBadge synthetic variant="data" /></span>
  if (provenance === 'intraday') return <StatusBadge status="INTRADAY" tone="info" label={t('Vnitrodenní', 'Intraday')} />
  return <StatusBadge status="REGULATORY" tone="success" label={t('Regulatorní', 'Regulatory')} />
}

const isRecord = (v: unknown): v is Record<string, unknown> => !!v && typeof v === 'object' && !Array.isArray(v)
const isScalar = (v: unknown) => v === null || ['string', 'number', 'boolean'].includes(typeof v)

function Cell({ v }: { v: unknown }) {
  const { t, num } = useFmt()
  if (v === null || v === undefined) return <span title={t('Služba nevrátila hodnotu', 'The service returned no value')}>—</span>
  if (typeof v === 'number') return <>{num(v, 6)}</>
  if (typeof v === 'boolean') return <>{v ? t('ano', 'yes') : t('ne', 'no')}</>
  if (typeof v === 'string') return <>{v}</>
  return <code>{JSON.stringify(v)}</code>
}

function Table({ rows, caption }: { rows: Record<string, unknown>[]; caption: string }) {
  const cols = [...new Set(rows.flatMap(r => Object.keys(r).filter(k => isScalar(r[k]))))]
  return (
    <div style={{ overflowX: 'auto', marginBottom: 12 }}>
      <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 12 }}>
        <caption style={{ textAlign: 'left', fontWeight: 600, padding: '4px 0' }}>{caption}</caption>
        <thead><tr>{cols.map(c => <th key={c} scope="col" style={th}>{c}</th>)}</tr></thead>
        <tbody>{rows.map((r, i) => <tr key={i} style={{ borderTop: '1px solid var(--border)' }}>{cols.map(c => <td key={c} style={td}><Cell v={r[c]} /></td>)}</tr>)}</tbody>
      </table>
    </div>
  )
}

function Body({ value, path, depth }: { value: unknown; path: string; depth: number }): ReactNode {
  const { t } = useFmt()
  if (Array.isArray(value)) {
    if (value.length === 0) return <p style={{ fontSize: 12, color: 'var(--text-secondary)' }}>{path}: {t('žádné záznamy', 'no records')}</p>
    if (value.every(isRecord)) return <Table rows={value as Record<string, unknown>[]} caption={path} />
    return <p style={{ fontSize: 12 }}>{path}: {value.join(', ')}</p>
  }
  if (isRecord(value)) {
    const scalars = Object.entries(value).filter(([, v]) => isScalar(v))
    const nested = Object.entries(value).filter(([, v]) => !isScalar(v))
    return (
      <>
        {scalars.length > 0 && (
          <dl style={{ display: 'grid', gridTemplateColumns: 'max-content 1fr', gap: '2px 12px', fontSize: 13, marginBottom: 8 }}>
            {scalars.map(([k, v]) => <div key={k} style={{ display: 'contents' }}><dt style={{ color: 'var(--text-secondary)' }}>{k}</dt><dd><Cell v={v} /></dd></div>)}
          </dl>
        )}
        {depth < 3 && nested.map(([k, v]) => <Body key={k} value={v} path={path ? `${path}.${k}` : k} depth={depth + 1} />)}
      </>
    )
  }
  return null
}

export function ReportView({ data, fallbackProvenance }: { data: unknown; fallbackProvenance?: Provenance }) {
  const { t, num } = useFmt()
  const provenance = responseProvenance(data, fallbackProvenance)
  const gap = dataGapOf(data)
  const d = isRecord(data) ? data : null
  const { header, dataGap, ...rest } = d ?? {}
  void dataGap
  const hasVar = d && ('valueAtRisk' in d || 'expectedShortfall' in d)
  return (
    <div data-testid="report-view">
      <div style={{ display: 'flex', gap: 8, alignItems: 'center', marginBottom: 8 }}>
        <ProvenanceBadge provenance={provenance} />
      </div>
      {gap && (
        <div role="alert" className="card" data-testid="data-gap" style={{ borderColor: 'var(--warning)', marginBottom: 12, fontSize: 13 }}>
          <strong>{t('Datová mezera — číslo se neuvádí', 'Data gap — no figure is given')}</strong>
          <div>{gap}</div>
        </div>
      )}
      {hasVar && d && (
        <dl data-testid="var-figures" style={{ display: 'grid', gridTemplateColumns: 'max-content 1fr', gap: '2px 12px', fontSize: 14, marginBottom: 12 }}>
          <dt>{t('Value at Risk', 'Value at risk')}</dt><dd>{typeof d.valueAtRisk === 'number' ? num(d.valueAtRisk, 2) : t('bez čísla (datová mezera)', 'no figure (data gap)')}</dd>
          <dt>{t('Expected shortfall', 'Expected shortfall')}</dt><dd>{typeof d.expectedShortfall === 'number' ? num(d.expectedShortfall, 2) : t('bez čísla (datová mezera)', 'no figure (data gap)')}</dd>
          <dt>{t('Scénářů', 'Scenarios')}</dt><dd>{String(d.scenarioCount ?? '—')}{d.minimumScenarios != null ? ` / ${t('min.', 'min.')} ${String(d.minimumScenarios)}` : ''}</dd>
        </dl>
      )}
      {isRecord(header) && <details style={{ fontSize: 12, marginBottom: 8 }}><summary>{t('Hlavička výpočtu', 'Run header')}</summary><Body value={header} path="header" depth={1} /></details>}
      <Body value={hasVar ? Object.fromEntries(Object.entries(rest).filter(([k]) => !['valueAtRisk', 'expectedShortfall'].includes(k))) : (d ? rest : data)} path="" depth={0} />
    </div>
  )
}
