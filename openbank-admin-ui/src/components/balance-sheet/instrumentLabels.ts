// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Name a snapshot instrument the way a person recognises it: product, maturity and balance — the
// instrument id is only the secondary, copyable reference (see `HumanReference`).

'use client'

import { useEffect, useState } from 'react'
import { getJson, riskUrl } from './api'
import { instrumentsSchema, type Instrument } from './contracts'
import { formatMoney } from '@/lib/risk/aggregate'

/**
 * The snapshot's instruments by id; null while loading, when the endpoint is unavailable, or when
 * `enabled` is false (no line names an instrument, so there is nothing to look up).
 */
export function useSnapshotInstruments(runId: string, enabled = true): Map<string, Instrument> | null {
  const [byId, setById] = useState<Map<string, Instrument> | null>(null)
  useEffect(() => {
    if (!enabled) return
    let cancelled = false
    void (async () => {
      const res = await getJson(riskUrl(`/api/v1/risk/snapshots/${encodeURIComponent(runId)}/instruments`), instrumentsSchema)
      if (cancelled || !res.ok) return
      setById(new Map(res.data.instruments.map(i => [i.id, i])))
    })()
    return () => { cancelled = true }
  }, [runId, enabled])
  return byId
}

const METHOD: Record<string, { cs: string; en: string }> = {
  ANNUITY: { cs: 'anuitní úvěr', en: 'annuity loan' },
  EQUAL_PRINCIPAL: { cs: 'úvěr s rovnoměrným splácením jistiny', en: 'equal-principal loan' },
  BULLET: { cs: 'úvěr splatný jednorázově', en: 'bullet loan' },
}

export function loanLabel(
  instrument: Instrument | undefined,
  fallbackGl: string | null | undefined,
  lang: 'cs' | 'en',
  locale: string,
): { label: string; sublabel?: string } {
  if (!instrument) {
    const gl = fallbackGl ? (lang === 'cs' ? ` (účet ${fallbackGl})` : ` (account ${fallbackGl})`) : ''
    return { label: (lang === 'cs' ? 'Úvěr' : 'Loan') + gl }
  }
  const method = instrument.loan?.method
  const product = (method && METHOD[method]?.[lang]) ?? (lang === 'cs' ? 'úvěr' : 'loan')
  const capitalised = product.charAt(0).toUpperCase() + product.slice(1)
  const maturity = instrument.maturityDate
    ? `${lang === 'cs' ? 'splatnost' : 'matures'} ${new Date(instrument.maturityDate).toLocaleDateString(locale)}`
    : undefined
  const balance = `${lang === 'cs' ? 'zůstatek' : 'outstanding'} ${formatMoney(instrument.outstanding, locale, instrument.currency)}`
  // #11107: lead with the contract number when risk-engine carries one.
  const head = instrument.contractNumber ? `${instrument.contractNumber} · ${capitalised}` : capitalised
  return { label: [head, maturity].filter(Boolean).join(', '), sublabel: balance }
}
