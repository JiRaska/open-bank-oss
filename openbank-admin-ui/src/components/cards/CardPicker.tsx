// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

// Pick a card by what an operator knows — the cardholder's name, the masked PAN, the product — never
// by typing a card id. Reads the same card-issuance list the Cards page does (the one existing
// endpoint) and filters it client-side; the visible slice is bounded so a large estate cannot dump
// thousands of rows into the DOM.

import { useMemo, useState } from 'react'
import { Search } from 'lucide-react'
import { DataUnavailable } from '@/components/feedback/DataUnavailable'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { svcUrl } from '@/lib/services/bff'
import { useServiceResource } from '@/lib/services/useServiceResource'
import type { Card } from '@/lib/cards/types'

const MAX_VISIBLE = 25

export function CardPicker({ onSelect, selectedId }: { onSelect: (card: Card) => void; selectedId?: string | null }) {
  const { t, language } = useLanguage()
  const [search, setSearch] = useState('')
  const { data, loading, unavailable } = useServiceResource<Card[]>(
    svcUrl('card-issuance-service', '/api/v1/cards'),
    { select: raw => (Array.isArray(raw) ? (raw as Card[]) : ((raw as { cards?: Card[] }).cards ?? [])) },
  )
  const matches = useMemo(() => {
    const needle = search.trim().toLowerCase()
    return (data ?? []).filter(c =>
      !needle
      || c.cardholderName?.toLowerCase().includes(needle)
      || c.maskedPan?.toLowerCase().includes(needle)
      || c.productCode?.toLowerCase().includes(needle),
    )
  }, [data, search])

  if (unavailable) {
    return (
      <DataUnavailable
        kind={unavailable.kind}
        service="card-issuance-service"
        feature={t('Seznam karet', 'Card list')}
        lang={language === 'cs' ? 'cs' : 'en'}
        dense
      />
    )
  }

  return (
    <div className="flex flex-col gap-2" role="search" aria-label={t('Vyhledání karty', 'Find a card')}>
      <label className="flex flex-col gap-1 text-xs text-slate-600">
        {t('Karta', 'Card')}
        <span className="relative">
          <Search className="pointer-events-none absolute left-2 top-2 h-4 w-4 text-slate-400" aria-hidden="true" />
          <input
            value={search}
            onChange={e => setSearch(e.target.value)}
            className="w-96 max-w-full rounded border border-slate-300 py-1 pl-8 pr-2 text-sm"
            placeholder={t('Jméno držitele, číslo karty (maskované) nebo produkt', 'Cardholder name, masked card number or product')}
          />
        </span>
      </label>
      {loading && !data && <p className="text-sm text-slate-500">{t('Načítám…', 'Loading…')}</p>}
      {data && matches.length === 0 && (
        <p className="text-sm text-slate-600">{t('Žádná karta neodpovídá hledání.', 'No card matches that search.')}</p>
      )}
      {matches.length > 0 && (
        <ul className="max-h-64 divide-y divide-slate-100 overflow-y-auto rounded border border-slate-200 bg-white text-sm">
          {matches.slice(0, MAX_VISIBLE).map(c => (
            <li key={c.id} className="flex items-center gap-3 px-3 py-2">
              <span className="flex-1">
                <span className="font-medium text-slate-800">{c.cardholderName}</span>
                <span className="ml-2 text-xs text-slate-500">{c.maskedPan} · {c.productCode} · {c.status}</span>
              </span>
              <button
                type="button"
                aria-pressed={selectedId === c.id}
                onClick={() => onSelect(c)}
                className="rounded border border-slate-300 px-2 py-1 text-xs text-slate-700 hover:bg-slate-50"
              >
                {selectedId === c.id ? t('Vybráno', 'Selected') : t('Vybrat', 'Select')}
              </button>
            </li>
          ))}
        </ul>
      )}
      {matches.length > MAX_VISIBLE && (
        <p className="text-xs text-slate-500">
          {t(`Zobrazeno ${MAX_VISIBLE} z ${matches.length} — zpřesněte hledání.`, `Showing ${MAX_VISIBLE} of ${matches.length} — narrow the search.`)}
        </p>
      )}
    </div>
  )
}
