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

import { useCallback, useEffect, useRef, useState } from 'react'
import { AlertTriangle, RefreshCw, Store, Trash2, Plus, ImageUp, ImageOff } from 'lucide-react'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { classifyBffFailure, svcUrl } from '@/lib/services/bff'
import { DataUnavailable, type UnavailableKind } from '@/components/feedback/DataUnavailable'
import { PageHeader } from '@/components/ui'
import { parseMerchantCataloguePage, parseUnmatchedMerchantDescriptors, type MerchantCatalogueRow, type UnmatchedMerchantDescriptor } from '@/lib/merchants/merchantCatalogueContract'

const SERVICE = 'transaction-service'
const CATALOGUE = '/api/v1/merchants'

type Merchant = MerchantCatalogueRow
type Unmatched = UnmatchedMerchantDescriptor

type Draft = {
  descriptorKey: string
  cleanName: string
  category: string
  city: string
  country: string
  lat: string
  lon: string
}

type PendingRemoval = { kind: 'entry' | 'logo'; merchant: Merchant }

const EMPTY_DRAFT: Draft = { descriptorKey: '', cleanName: '', category: '', city: '', country: '', lat: '', lon: '' }
const PAGE_SIZE = 50

export default function MerchantsPage() {
  const { t, language } = useLanguage()
  const dateLocale = language === 'cs' ? 'cs-CZ' : 'en-GB'
  const [rows, setRows] = useState<Merchant[]>([])
  const [total, setTotal] = useState(0)
  const [page, setPage] = useState(0)
  const loadedPage = useRef<number | null>(null)
  const [unmatched, setUnmatched] = useState<Unmatched[]>([])
  const [loading, setLoading] = useState(true)
  const [unavailable, setUnavailable] = useState<{ kind: UnavailableKind } | null>(null)
  const [draft, setDraft] = useState<Draft | null>(null)
  const [saving, setSaving] = useState(false)
  const [actionError, setActionError] = useState<string | null>(null)
  const [uploading, setUploading] = useState<string | null>(null)
  const [pendingRemoval, setPendingRemoval] = useState<PendingRemoval | null>(null)
  const [removing, setRemoving] = useState(false)

  const load = useCallback(async (requestedPage: number) => {
    const canRetainSnapshot = loadedPage.current === requestedPage
    setLoading(true)
    if (!canRetainSnapshot) { setRows([]); setTotal(0); loadedPage.current = null }
    try {
      const [listRes, unmatchedRes] = await Promise.all([
        fetch(svcUrl(SERVICE, CATALOGUE, { page: String(requestedPage), size: String(PAGE_SIZE) }), { cache: 'no-store' }),
        fetch(svcUrl(SERVICE, `${CATALOGUE}/unmatched`, { limit: '25' }), { cache: 'no-store' }),
      ])
      if (!listRes.ok) {
        setUnavailable({ kind: await classifyBffFailure(listRes) })
        return
      }
      let nextPage
      try { nextPage = parseMerchantCataloguePage(await listRes.json() as unknown, PAGE_SIZE) } catch { setUnavailable({ kind: 'error' }); return }
      const lastPage = nextPage.total === 0 ? 0 : Math.floor((nextPage.total - 1) / PAGE_SIZE)
      if (requestedPage > lastPage) { setPage(lastPage); return }
      setRows(nextPage.data); setTotal(nextPage.total); loadedPage.current = requestedPage
      // The worklist failing must not blank the catalogue: they are separate reads, and a stale
      // or empty worklist is a smaller loss than losing the rows an operator is editing.
      if (unmatchedRes.ok) {
        try { setUnmatched(parseUnmatchedMerchantDescriptors(await unmatchedRes.json() as unknown)) } catch { /* independent worklist drift must not blank catalogue */ }
      }
      setUnavailable(null)
    } catch {
      setUnavailable({ kind: 'unreachable' })
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => { void load(page) }, [load, page])

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
      await load(page)
    } catch {
      setActionError(t('Služba je nedostupná', 'The service is unreachable'))
    } finally {
      setSaving(false)
    }
  }

  const remove = async (descriptorKey: string): Promise<boolean> => {
    setActionError(null)
    try {
      const res = await fetch(svcUrl(SERVICE, `${CATALOGUE}/${encodeURIComponent(descriptorKey)}`), {
        method: 'DELETE',
      })
      if (!res.ok && res.status !== 404) {
        setActionError(t('Smazání selhalo', 'Delete failed'))
        return false
      }
      await load(page)
      return true
    } catch {
      setActionError(t('Služba je nedostupná', 'The service is unreachable'))
      return false
    }
  }

  // Client-side limits mirroring LogoImages on the service. Duplicated deliberately: the server
  // is the enforcement point and rejects the same cases, but a 512 kB round trip to be told "too
  // big" is a worse answer than an immediate one, and the operator is picking a file, not
  // debugging an API.
  const MAX_UPLOAD_BYTES = 512 * 1024
  const ACCEPTED = 'image/png,image/jpeg,image/gif'

  const uploadLogo = async (descriptorKey: string, file: File) => {
    setActionError(null)
    if (file.size > MAX_UPLOAD_BYTES) {
      setActionError(t(
        `Soubor má ${Math.round(file.size / 1024)} kB, limit je ${MAX_UPLOAD_BYTES / 1024} kB.`,
        `The file is ${Math.round(file.size / 1024)} kB; the limit is ${MAX_UPLOAD_BYTES / 1024} kB.`,
      ))
      return
    }
    setUploading(descriptorKey)
    try {
      const res = await fetch(
        svcUrl(SERVICE, `${CATALOGUE}/${encodeURIComponent(descriptorKey)}/logo`, { licence: 'trademark' }),
        { method: 'PUT', headers: { 'Content-Type': 'application/octet-stream' }, body: file },
      )
      if (!res.ok) {
        const body = await res.json().catch(() => null) as { message?: string } | null
        // The service says WHY it refused — not a raster format, implausible dimensions, no
        // catalogue row. Passing that through is the difference between a fixable message and
        // "upload failed".
        setActionError(body?.message ?? t('Nahrání loga selhalo', 'The logo upload failed'))
        return
      }
      await load(page)
    } catch {
      setActionError(t('Služba je nedostupná', 'The service is unreachable'))
    } finally {
      setUploading(null)
    }
  }

  const removeLogo = async (descriptorKey: string): Promise<boolean> => {
    setActionError(null)
    try {
      const res = await fetch(
        svcUrl(SERVICE, `${CATALOGUE}/${encodeURIComponent(descriptorKey)}/logo`),
        { method: 'DELETE' },
      )
      if (!res.ok && res.status !== 404) {
        setActionError(t('Smazání loga selhalo', 'Deleting the logo failed'))
        return false
      }
      await load(page)
      return true
    } catch {
      setActionError(t('Služba je nedostupná', 'The service is unreachable'))
      return false
    }
  }

  const confirmRemoval = async () => {
    if (!pendingRemoval || removing) return
    setRemoving(true)
    const completed = pendingRemoval.kind === 'entry'
      ? await remove(pendingRemoval.merchant.descriptorKey)
      : await removeLogo(pendingRemoval.merchant.descriptorKey)
    setRemoving(false)
    if (completed) setPendingRemoval(null)
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
          onClick={() => void load(page)}
          disabled={loading}
          type="button"
          aria-busy={loading}
          aria-label={t('Obnovit katalog obchodníků', 'Refresh the merchant catalogue')}
          className="btn btn-secondary btn-sm"
        >
          <RefreshCw size={14} aria-hidden="true" className={loading ? 'animate-spin' : ''} /> {t('Obnovit', 'Refresh')}
        </button>}
      />

      {actionError && !pendingRemoval && <div className="card" role="alert" style={{ marginBottom: 14, borderColor: 'var(--red)', color: 'var(--red)', fontSize: 13 }}>
        {actionError}
      </div>}

      {unavailable && <DataUnavailable
        kind={unavailable.kind}
        service={SERVICE}
        feature={t('Katalog obchodníků', 'Merchant catalogue')}
        lang={language}
        dense={rows.length > 0}
      />}

      {(!unavailable || rows.length > 0) && <>
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
                <th style={th} aria-label={t('Logo', 'Logo')} />
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
                  <td style={{ ...td, width: 44 }}><MerchantLogo merchant={m} /></td>
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
                    <LogoButton
                      merchant={m}
                      busy={uploading === m.descriptorKey}
                      accept={ACCEPTED}
                      label={m.logoContentHash
                        ? t(`Nahradit logo ${m.descriptorKey}`, `Replace the logo for ${m.descriptorKey}`)
                        : t(`Nahrát logo ${m.descriptorKey}`, `Upload a logo for ${m.descriptorKey}`)}
                      onPick={file => void uploadLogo(m.descriptorKey, file)}
                    />{' '}
                    {m.logoContentHash && <button
                      type="button"
                      className="btn btn-secondary btn-sm"
                      onClick={() => { setActionError(null); setPendingRemoval({ kind: 'logo', merchant: m }) }}
                      aria-label={t(`Smazat logo ${m.descriptorKey}`, `Delete the logo for ${m.descriptorKey}`)}
                    >
                      <ImageOff size={12} aria-hidden="true" />
                    </button>}{' '}
                    <button
                      type="button"
                      className="btn btn-secondary btn-sm"
                      onClick={() => { setActionError(null); setPendingRemoval({ kind: 'entry', merchant: m }) }}
                      aria-label={t(`Smazat ${m.descriptorKey}`, `Delete ${m.descriptorKey}`)}
                    >
                      <Trash2 size={12} aria-hidden="true" />
                    </button>
                  </td>
                </tr>
              ))}
              {!loading && rows.length === 0 && (
                <tr><td colSpan={7} style={{ padding: 20, textAlign: 'center', color: 'var(--text-tertiary)', fontSize: 13 }}>
                  {t('Katalog je prázdný', 'The catalogue is empty')}
                </td></tr>
              )}
            </tbody>
          </table>
          <nav aria-label={t('Stránkování katalogu obchodníků', 'Merchant catalogue pagination')} style={{ padding: '12px 14px', borderTop: '1px solid var(--border)', display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 12 }}>
            <span aria-live="polite" style={{ color: 'var(--text-tertiary)', fontSize: 12 }}>
              {total === 0 ? t('Žádné záznamy', 'No entries') : t(`Zobrazeno ${page * PAGE_SIZE + 1}–${Math.min(page * PAGE_SIZE + rows.length, total)} z ${total}`, `Showing ${page * PAGE_SIZE + 1}–${Math.min(page * PAGE_SIZE + rows.length, total)} of ${total}`)}
            </span>
            <div style={{ display: 'flex', gap: 8 }}>
              <button type="button" className="btn btn-secondary btn-sm" disabled={loading || unavailable !== null || page === 0} onClick={() => setPage(current => Math.max(0, current - 1))}>{t('Předchozí', 'Previous')}</button>
              <button type="button" className="btn btn-secondary btn-sm" disabled={loading || unavailable !== null || (page + 1) * PAGE_SIZE >= total} onClick={() => setPage(current => current + 1)}>{t('Další', 'Next')}</button>
            </div>
          </nav>
        </div>
      </>}
      {pendingRemoval && <MerchantRemovalDialog
        removal={pendingRemoval}
        busy={removing}
        failed={actionError != null}
        onCancel={() => { if (!removing) { setPendingRemoval(null); setActionError(null) } }}
        onConfirm={() => void confirmRemoval()}
      />}
    </div>
  )
}

function MerchantRemovalDialog({ removal, busy, failed, onCancel, onConfirm }: {
  removal: PendingRemoval
  busy: boolean
  failed: boolean
  onCancel: () => void
  onConfirm: () => void
}) {
  const { t } = useLanguage()
  const dialogRef = useRef<HTMLDivElement>(null)
  const cancelRef = useRef<HTMLButtonElement>(null)
  const isEntry = removal.kind === 'entry'
  const titleId = 'merchant-removal-title'
  const impactId = 'merchant-removal-impact'

  useEffect(() => { cancelRef.current?.focus() }, [])

  return (
    <div
      ref={dialogRef}
      role="alertdialog"
      aria-modal="true"
      aria-labelledby={titleId}
      aria-describedby={impactId}
      aria-busy={busy}
      onKeyDown={event => {
        if (event.key === 'Escape' && !busy) onCancel()
        if (event.key !== 'Tab' || !dialogRef.current) return
        const controls = Array.from(dialogRef.current.querySelectorAll<HTMLElement>('button:not(:disabled)'))
        if (controls.length === 0) return
        const first = controls[0]
        const last = controls[controls.length - 1]
        if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last.focus() }
        if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus() }
      }}
      style={{ position: 'fixed', inset: 0, zIndex: 1200, background: 'rgba(15,23,42,.68)', display: 'grid', placeItems: 'center', padding: 20 }}
    >
      <div className="card" style={{ width: 'min(520px, 100%)', padding: 22 }}>
        <div style={{ display: 'flex', gap: 10, alignItems: 'flex-start' }}>
          <AlertTriangle aria-hidden="true" size={20} style={{ color: 'var(--danger)', flexShrink: 0, marginTop: 2 }} />
          <div>
            <h2 id={titleId} style={{ margin: 0, fontSize: 17 }}>
              {isEntry ? t('Smazat záznam obchodníka?', 'Delete the merchant entry?') : t('Smazat logo obchodníka?', 'Delete the merchant logo?')}
            </h2>
            <p id={impactId} style={{ margin: '7px 0 0', color: 'var(--text-secondary)', fontSize: 13, lineHeight: 1.5 }}>
              {isEntry
                ? t('Transakce s tímto descriptorem se přestanou obohacovat názvem, kategorií, polohou i logem.', 'Transactions carrying this descriptor will no longer be enriched with its name, category, location or logo.')
                : t('Záznam a jeho obohacovací data zůstanou zachována; odstraní se pouze uložený obrázek.', 'The entry and its enrichment data will remain; only the stored image will be removed.')}
            </p>
          </div>
        </div>
        <div style={{ marginTop: 14, padding: '10px 12px', border: '1px solid var(--border)', borderRadius: 8, background: 'var(--surface-2)', fontSize: 13 }}>
          <strong>{removal.merchant.cleanName}</strong><br />
          <span style={{ fontFamily: 'var(--font-mono)', color: 'var(--text-secondary)' }}>{removal.merchant.descriptorKey}</span>
        </div>
        {failed && <p role="alert" style={{ color: 'var(--danger)', fontSize: 13, margin: '12px 0 0' }}>
          {isEntry ? t('Záznam se nepodařilo smazat. Můžete akci opakovat.', 'The entry could not be deleted. You can try again.') : t('Logo se nepodařilo smazat. Můžete akci opakovat.', 'The logo could not be deleted. You can try again.')}
        </p>}
        <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 8, marginTop: 18 }}>
          <button ref={cancelRef} type="button" className="btn btn-secondary" disabled={busy} onClick={onCancel}>
            {t('Ponechat', 'Keep')}
          </button>
          <button type="button" className="btn btn-danger" disabled={busy} aria-busy={busy} onClick={onConfirm}>
            {busy ? t('Mažu…', 'Deleting…') : isEntry ? t('Smazat záznam', 'Delete entry') : t('Smazat logo', 'Delete logo')}
          </button>
        </div>
      </div>
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

/**
 * The stored logo, or a monogram standing in for one that has not been ingested.
 *
 * The monogram is a UI affordance and NOT a fallback the customer app gets: absence stays absence
 * on the wire (`merchant.logoUrl` is simply missing), because a placeholder rendered next to a
 * payment reads as "this is the merchant's mark" when it is really "we have nothing". Here, on an
 * operator screen whose whole purpose is to find the gaps, a monogram is exactly the right thing —
 * it makes a missing logo visible at a glance instead of leaving an empty cell.
 */
function MerchantLogo({ merchant }: { merchant: Merchant }) {
  const box = {
    width: 32, height: 32, borderRadius: 8, display: 'flex', alignItems: 'center',
    justifyContent: 'center', border: '1px solid var(--border)', background: 'var(--surface-2)',
    overflow: 'hidden', flexShrink: 0,
  } as const
  if (!merchant.logoContentHash) {
    return (
      <div style={box} aria-label={`${merchant.cleanName} — no logo`} title={`${merchant.cleanName} — no logo`}>
        <span style={{ fontSize: 13, fontWeight: 600, color: 'var(--text-tertiary)' }}>
          {merchant.cleanName.trim().charAt(0).toUpperCase() || '?'}
        </span>
      </div>
    )
  }
  return (
    <div style={box}>
      {/* eslint-disable-next-line @next/next/no-img-element -- the BFF proxies bytes from the
          service; next/image would need a remote-pattern allowlist for a same-origin path it
          cannot optimise anyway. */}
      <img
        src={svcUrl(SERVICE, `${CATALOGUE}/${encodeURIComponent(merchant.descriptorKey)}/logo`, {
          size: '64',
          // Same cache-busting token the service puts in the customer-facing URL: the browser may
          // cache hard, and a replaced logo is a different URL rather than a stale one.
          v: merchant.logoContentHash.slice(0, 16),
        })}
        alt={merchant.cleanName}
        width={32}
        height={32}
        style={{ objectFit: 'contain' }}
      />
    </div>
  )
}

/** A file picker dressed as a button — the row already has three, and a bare input breaks the row. */
function LogoButton({ merchant, busy, accept, label, onPick }: {
  merchant: Merchant
  busy: boolean
  accept: string
  label: string
  onPick: (file: File) => void
}) {
  const input = useRef<HTMLInputElement>(null)
  return (
    <>
      <button
        type="button"
        className="btn btn-secondary btn-sm"
        disabled={busy}
        aria-busy={busy}
        aria-label={label}
        title={label}
        onClick={() => input.current?.click()}
      >
        <ImageUp size={12} aria-hidden="true" />
      </button>
      <input
        ref={input}
        type="file"
        accept={accept}
        data-testid={`logo-input-${merchant.descriptorKey}`}
        style={{ display: 'none' }}
        onChange={e => {
          const file = e.target.files?.[0]
          // Reset the input so picking the SAME file twice fires change again — an operator who
          // re-crops and re-picks would otherwise get silence.
          e.target.value = ''
          if (file) onPick(file)
        }}
      />
    </>
  )
}
