// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// #8573: the merchant enrichment catalogue an operator maintains.
//
// The pipeline behind `merchant` / `merchantCategory` was complete end to end and starved:
// merchant_catalog held the ~30 rows one migration seeded and had no writer at all, so the
// enrichment was absent for most transactions. This screen is the writer.
//
// The unmatched worklist is deliberately the FIRST thing on the page, not a tab behind the
// catalogue. Without it this is a blank form: an operator has no way to know which merchants are
// worth adding, and the catalogue stays at thirty rows with a nicer way to reach them. The list is
// ranked by how many transactions carried each descriptor, so the work is ordered by how many
// customers it affects.

'use client'

import { useCallback, useEffect, useState } from 'react'
import { RefreshCw, Store, Trash2, Plus } from 'lucide-react'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { classifyBffFailure, svcUrl } from '@/lib/services/bff'
import { DataUnavailable, type UnavailableKind } from '@/components/feedback/DataUnavailable'
import { PageHeader } from '@/components/ui'

const SERVICE = 'transaction-service'
const CATALOGUE = '/api/v1/merchants'

type Merchant = {
  descriptorKey: string
  cleanName: string
  logoUrl?: string | null
  category?: string | null
  lat?: number | null
  lon?: number | null
  city?: string | null
  country?: string | null
  updatedAt?: string
}

type Unmatched = { descriptorKey: string; occurrences: number }

type Draft = {
  descriptorKey: string
  cleanName: string
  category: string
  city: string
  country: string
  lat: string
  lon: string
}

const EMPTY_DRAFT: Draft = { descriptorKey: '', cleanName: '', category: '', city: '', country: '', lat: '', lon: '' }

export default function MerchantsPage() {
  const { t, language } = useLanguage()
  const dateLocale = language === 'cs' ? 'cs-CZ' : 'en-GB'
  const [rows, setRows] = useState<Merchant[]>([])
  const [total, setTotal] = useState(0)
  const [unmatched, setUnmatched] = useState<Unmatched[]>([])
  const [loading, setLoading] = useState(true)
  const [unavailable, setUnavailable] = useState<{ kind: UnavailableKind } | null>(null)
  const [draft, setDraft] = useState<Draft | null>(null)
  const [saving, setSaving] = useState(false)
  const [actionError, setActionError] = useState<string | null>(null)

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const [listRes, unmatchedRes] = await Promise.all([
        fetch(svcUrl(SERVICE, CATALOGUE, { size: '100' }), { cache: 'no-store' }),
        fetch(svcUrl(SERVICE, `${CATALOGUE}/unmatched`, { limit: '25' }), { cache: 'no-store' }),
      ])
      if (!listRes.ok) {
        setUnavailable({ kind: await classifyBffFailure(listRes) })
        return
      }
      const page = await listRes.json() as { data?: Merchant[]; total?: number }
      setRows(Array.isArray(page.data) ? page.data : [])
      setTotal(typeof page.total === 'number' ? page.total : 0)
      // The worklist failing must not blank the catalogue: they are separate reads, and a stale
      // or empty worklist is a smaller loss than losing the rows an operator is editing.
      setUnmatched(unmatchedRes.ok ? (await unmatchedRes.json() as Unmatched[]) : [])
      setUnavailable(null)
    } catch {
      setUnavailable({ kind: 'unreachable' })
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => { void load() }, [load])

  const save = async () => {
    if (!draft) return
    setSaving(true)
    setActionError(null)
    try {
      const res = await fetch(svcUrl(SERVICE, `${CATALOGUE}/${encodeURIComponent(draft.descriptorKey)}`), {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          cleanName: draft.cleanName,
          category: draft.category || null,
          city: draft.city || null,
          country: draft.country || null,
          // Both or neither: the service refuses half a pair, and sending one would be a 400 the
          // operator has to decode. Blank both fields reads as "no location", which is valid.
          lat: draft.lat ? Number(draft.lat) : null,
          lon: draft.lon ? Number(draft.lon) : null,
        }),
      })
      if (!res.ok) {
        const body = await res.json().catch(() => null) as { message?: string } | null
        setActionError(body?.message ?? t('Uložení selhalo', 'Save failed'))
        return
      }
      setDraft(null)
      await load()
    } catch {
      setActionError(t('Služba je nedostupná', 'The service is unreachable'))
    } finally {
      setSaving(false)
    }
  }

  const remove = async (descriptorKey: string) => {
    setActionError(null)
    try {
      const res = await fetch(svcUrl(SERVICE, `${CATALOGUE}/${encodeURIComponent(descriptorKey)}`), {
        method: 'DELETE',
      })
      if (!res.ok && res.status !== 404) {
        setActionError(t('Smazání selhalo', 'Delete failed'))
        return
      }
      await load()
    } catch {
      setActionError(t('Služba je nedostupná', 'The service is unreachable'))
    }
  }

  const th = { padding: '10px 14px', fontSize: 11, color: 'var(--text-tertiary)' } as const
  const td = { padding: '10px 14px' } as const

  return (
    <div>
      <PageHeader
        title={t('Katalog obchodníků', 'Merchant catalogue')}
        subtitle={t(
          'Obohacení transakcí: obchodní jméno, kategorie a poloha podle normalizovaného descriptoru. Jen veřejná obchodní data.',
          'Transaction enrichment: trading name, category and location keyed by normalised descriptor. Public business data only.',
        )}
        icon={<Store size={20} style={{ color: 'var(--accent)' }} />}
        actions={<button
          onClick={load}
          disabled={loading}
          type="button"
          aria-busy={loading}
          aria-label={t('Obnovit katalog obchodníků', 'Refresh the merchant catalogue')}
          className="btn btn-secondary btn-sm"
        >
          <RefreshCw size={14} aria-hidden="true" className={loading ? 'animate-spin' : ''} /> {t('Obnovit', 'Refresh')}
        </button>}
      />

      {actionError && <div className="card" role="alert" style={{ marginBottom: 14, borderColor: 'var(--red)', color: 'var(--red)', fontSize: 13 }}>
        {actionError}
      </div>}

      {unavailable && <DataUnavailable
        kind={unavailable.kind}
        service={SERVICE}
        feature={t('Katalog obchodníků', 'Merchant catalogue')}
        lang={language}
        dense={rows.length > 0}
      />}

      {!unavailable && <>
        <section className="card" style={{ marginBottom: 18 }}>
          <h2 style={{ fontSize: 14, margin: '0 0 4px' }}>{t('Nespárované descriptory', 'Unmatched descriptors')}</h2>
          <p style={{ fontSize: 12, color: 'var(--text-tertiary)', margin: '0 0 10px' }}>
            {t(
              'Nejčastější descriptory z posledních transakcí, které katalog neumí přeložit. Řazeno podle počtu transakcí.',
              'The most frequent descriptors from recent transactions that the catalogue cannot resolve, ranked by how many transactions carried them.',
            )}
          </p>
          {unmatched.length === 0 && <p style={{ fontSize: 13, color: 'var(--text-tertiary)', margin: 0 }}>
            {loading ? t('Načítám…', 'Loading…') : t('Nic nespárovaného v posledním okně.', 'Nothing unmatched in the recent window.')}
          </p>}
          <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6 }}>
            {unmatched.map(u => (
              <button
                key={u.descriptorKey}
                type="button"
                className="btn btn-secondary btn-sm"
                onClick={() => setDraft({ ...EMPTY_DRAFT, descriptorKey: u.descriptorKey })}
                aria-label={t(`Přidat ${u.descriptorKey}`, `Add ${u.descriptorKey}`)}
              >
                <Plus size={12} aria-hidden="true" />
                <span style={{ fontFamily: 'var(--font-mono)', fontSize: 12 }}>{u.descriptorKey}</span>
                <span style={{ color: 'var(--text-tertiary)', fontSize: 11 }}>×{u.occurrences}</span>
              </button>
            ))}
          </div>
        </section>

        {draft && <section className="card" style={{ marginBottom: 18 }}>
          <h2 style={{ fontSize: 14, margin: '0 0 10px' }}>
            {t('Zápis do katalogu', 'Catalogue entry')} — <span style={{ fontFamily: 'var(--font-mono)' }}>{draft.descriptorKey || '—'}</span>
          </h2>
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(160px, 1fr))', gap: 8 }}>
            <Field label={t('Descriptor', 'Descriptor')} value={draft.descriptorKey} onChange={v => setDraft({ ...draft, descriptorKey: v })} mono />
            <Field label={t('Obchodní jméno', 'Trading name')} value={draft.cleanName} onChange={v => setDraft({ ...draft, cleanName: v })} />
            <Field label={t('Kategorie', 'Category')} value={draft.category} onChange={v => setDraft({ ...draft, category: v })} />
            <Field label={t('Město', 'City')} value={draft.city} onChange={v => setDraft({ ...draft, city: v })} />
            <Field label={t('Země', 'Country')} value={draft.country} onChange={v => setDraft({ ...draft, country: v })} />
            <Field label={t('Šířka', 'Latitude')} value={draft.lat} onChange={v => setDraft({ ...draft, lat: v })} mono />
            <Field label={t('Délka', 'Longitude')} value={draft.lon} onChange={v => setDraft({ ...draft, lon: v })} mono />
          </div>
          <p style={{ fontSize: 11, color: 'var(--text-tertiary)', margin: '8px 0 0' }}>
            {t(
              'Descriptor se normalizuje na serveru — vložit lze i syrový řádek z výpisu. Souřadnice zadejte obě, nebo žádnou.',
              'The descriptor is normalised server-side, so a raw statement line works. Give both coordinates or neither.',
            )}
          </p>
          <div style={{ display: 'flex', gap: 6, marginTop: 10 }}>
            <button type="button" className="btn btn-primary btn-sm" onClick={save} disabled={saving || !draft.cleanName.trim() || !draft.descriptorKey.trim()}>
              {saving ? t('Ukládám…', 'Saving…') : t('Uložit', 'Save')}
            </button>
            <button type="button" className="btn btn-secondary btn-sm" onClick={() => { setDraft(null); setActionError(null) }}>
              {t('Zrušit', 'Cancel')}
            </button>
          </div>
        </section>}

        <div className="card" style={{ padding: 0, overflow: 'hidden' }}>
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', padding: '10px 14px' }}>
            <h2 style={{ fontSize: 14, margin: 0 }}>
              {t('Katalog', 'Catalogue')} <span style={{ color: 'var(--text-tertiary)', fontSize: 12 }}>({total})</span>
            </h2>
            <button type="button" className="btn btn-secondary btn-sm" onClick={() => setDraft({ ...EMPTY_DRAFT })}>
              <Plus size={12} aria-hidden="true" /> {t('Nový záznam', 'New entry')}
            </button>
          </div>
          <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 13 }}>
            <thead>
              <tr style={{ background: 'var(--surface-2)', textAlign: 'left' }}>
                <th style={th}>{t('Descriptor', 'Descriptor')}</th>
                <th style={th}>{t('Obchodní jméno', 'Trading name')}</th>
                <th style={th}>{t('Kategorie', 'Category')}</th>
                <th style={th}>{t('Místo', 'Location')}</th>
                <th style={th}>{t('Aktualizováno', 'Updated')}</th>
                <th style={th} aria-label={t('Akce', 'Actions')} />
              </tr>
            </thead>
            <tbody>
              {rows.map(m => (
                <tr key={m.descriptorKey} style={{ borderTop: '1px solid var(--border)' }}>
                  <td style={{ ...td, fontFamily: 'var(--font-mono)', fontSize: 12 }}>{m.descriptorKey}</td>
                  <td style={td}>{m.cleanName}</td>
                  <td style={td}>{m.category ?? '—'}</td>
                  <td style={td}>{[m.city, m.country].filter(Boolean).join(', ') || '—'}</td>
                  <td style={{ ...td, color: 'var(--text-tertiary)', fontSize: 12 }}>
                    {m.updatedAt ? new Date(m.updatedAt).toLocaleString(dateLocale) : '—'}
                  </td>
                  <td style={{ ...td, textAlign: 'right', whiteSpace: 'nowrap' }}>
                    <button
                      type="button"
                      className="btn btn-secondary btn-sm"
                      onClick={() => setDraft({
                        descriptorKey: m.descriptorKey,
                        cleanName: m.cleanName,
                        category: m.category ?? '',
                        city: m.city ?? '',
                        country: m.country ?? '',
                        lat: m.lat != null ? String(m.lat) : '',
                        lon: m.lon != null ? String(m.lon) : '',
                      })}
                    >
                      {t('Upravit', 'Edit')}
                    </button>{' '}
                    <button
                      type="button"
                      className="btn btn-secondary btn-sm"
                      onClick={() => void remove(m.descriptorKey)}
                      aria-label={t(`Smazat ${m.descriptorKey}`, `Delete ${m.descriptorKey}`)}
                    >
                      <Trash2 size={12} aria-hidden="true" />
                    </button>
                  </td>
                </tr>
              ))}
              {!loading && rows.length === 0 && (
                <tr><td colSpan={6} style={{ padding: 20, textAlign: 'center', color: 'var(--text-tertiary)', fontSize: 13 }}>
                  {t('Katalog je prázdný', 'The catalogue is empty')}
                </td></tr>
              )}
            </tbody>
          </table>
        </div>
      </>}
    </div>
  )
}

function Field({ label, value, onChange, mono }: {
  label: string
  value: string
  onChange: (v: string) => void
  mono?: boolean
}) {
  return (
    <label style={{ display: 'block', fontSize: 11, color: 'var(--text-tertiary)' }}>
      {label}
      <input
        value={value}
        onChange={e => onChange(e.target.value)}
        style={{
          display: 'block', width: '100%', marginTop: 3, fontSize: 13, padding: '5px 8px',
          borderRadius: 6, border: '1px solid var(--border)', background: 'var(--surface)',
          fontFamily: mono ? 'var(--font-mono)' : 'inherit',
        }}
      />
    </label>
  )
}
