// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// One operator queue of the pension back office (ADR-0334): a bounded, paged, read-only list of
// records from pension-service. The queues of backend slices S2/S3/S5 (#12350) have no published
// contract yet, so a row is an open record (contracts.ts queueRowSchema) and the caller names the
// columns to show; until a slice ships its route the panel degrades through DataUnavailable.

'use client'

import { useCallback, useEffect, useState } from 'react'
import Link from 'next/link'
import { RefreshCw } from 'lucide-react'
import { DataUnavailable, type UnavailableKind } from '@/components/feedback/DataUnavailable'
import { getJson } from './api'
import { fieldOf, queueSchema, rowIdOf, type QueueRow } from './contracts'
import { statusLabel } from './model'
import { useLanguage } from '@/lib/i18n/LanguageContext'

export const PAGE_SIZE = 25

export type QueueColumn = { key: string; cs: string; en: string }

type Props = {
  /** Dotted path of the row's status field; default `status`. */
  statusPath?: string
  title: string
  url: string
  service: string
  feature: string
  columns: QueueColumn[]
  /** Optional per-row link (e.g. to the contract the record belongs to). */
  hrefOf?: (row: QueueRow) => string | null
}

function cell(value: unknown): string {
  if (value === null || value === undefined) return '—'
  if (typeof value === 'object') return JSON.stringify(value)
  return String(value)
}

export function PensionQueue({ title, url, service, feature, columns, hrefOf, statusPath = 'status' }: Props) {
  const { t, language } = useLanguage()
  const [rows, setRows] = useState<QueueRow[] | null>(null)
  const [unavailable, setUnavailable] = useState<{ kind: UnavailableKind } | null>(null)
  const [shown, setShown] = useState(PAGE_SIZE)

  const load = useCallback(async () => {
    const res = await getJson(url, queueSchema)
    if (!res.ok) { setUnavailable({ kind: res.kind }); setRows(null); return }
    setUnavailable(null)
    setRows(res.data)
  }, [url])

  useEffect(() => { void load() }, [load])

  return (
    <section className="card" style={{ marginBottom: 16, overflowX: 'auto' }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 8 }}>
        <h2 style={{ fontSize: 15, margin: 0 }}>{title}</h2>
        <button type="button" className="btn btn-secondary btn-sm" onClick={() => void load()} aria-label={t('Obnovit', 'Refresh')}>
          <RefreshCw size={14} aria-hidden="true" />
        </button>
      </div>
      {unavailable ? (
        <DataUnavailable kind={unavailable.kind} service={service} feature={feature} lang={language} dense />
      ) : rows === null ? null : rows.length === 0 ? (
        <DataUnavailable kind="no_data" service={service} feature={feature} lang={language} dense />
      ) : (
        <>
          <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 13 }}>
            <thead>
              <tr>
                <th scope="col" style={{ textAlign: 'left' }}>{t('Záznam', 'Record')}</th>
                <th scope="col" style={{ textAlign: 'left' }}>{t('Stav', 'Status')}</th>
                {columns.map(c => <th key={c.key} scope="col" style={{ textAlign: 'left' }}>{t(c.cs, c.en)}</th>)}
              </tr>
            </thead>
            <tbody>
              {rows.slice(0, shown).map((row, i) => {
                const id = rowIdOf(row)
                const contractId = fieldOf(row, 'contractId')
                const href = hrefOf?.(row) ?? (typeof contractId === 'string' ? `/pension/contracts/${encodeURIComponent(contractId)}` : null)
                const status = fieldOf(row, statusPath)
                return (
                  <tr key={id ?? `row-${i}`}>
                    <td>{id === null ? '—' : href ? <Link href={href}>{id}</Link> : id}</td>
                    <td>{typeof status === 'string' ? statusLabel(status, t) : '—'}</td>
                    {columns.map(c => <td key={c.key}>{cell(fieldOf(row, c.key))}</td>)}
                  </tr>
                )
              })}
            </tbody>
          </table>
          {rows.length > shown && (
            <button type="button" className="btn btn-secondary btn-sm" style={{ marginTop: 12 }} onClick={() => setShown(s => s + PAGE_SIZE)}>
              {t('Načíst další', 'Load more')}
            </button>
          )}
        </>
      )}
    </section>
  )
}
