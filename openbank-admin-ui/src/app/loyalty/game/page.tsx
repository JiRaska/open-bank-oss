// SPDX-License-Identifier: Apache-2.0
'use client'

import { useCallback, useEffect, useState } from 'react'
import { useSession } from 'next-auth/react'
import Link from 'next/link'
import { AuthGuard } from '@/components/auth/AuthGuard'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { hasPermission } from '@/lib/auth/roles'
import styles from './page.module.css'

type Requirement = { kind: 'NONE' | 'PREVIOUS_LEVEL' | 'EARN_SOURCE'; source: string | null; count: number; hintCs: string; hintEn: string }
type Level = { id: string; theme: string; order: number; titleCs: string; titleEn: string; state: 'OFF' | 'BETA' | 'LIVE'; startsAt: string | null; endsAt: string | null; requirement: Requirement }
type Catalog = { revision: number; levels: Level[] }

function parseCatalog(value: unknown): Catalog {
  const v = value as Catalog
  if (!v || !Number.isSafeInteger(v.revision) || v.revision < 0 || !Array.isArray(v.levels) || v.levels.length > 30 ||
    !v.levels.every(l => typeof l.id === 'string' && typeof l.theme === 'string' && Number.isInteger(l.order) &&
      typeof l.titleCs === 'string' && typeof l.titleEn === 'string' && ['OFF', 'BETA', 'LIVE'].includes(l.state) && l.requirement &&
      ['NONE', 'PREVIOUS_LEVEL', 'EARN_SOURCE'].includes(l.requirement.kind))) throw new Error('Invalid catalogue')
  return v
}

async function readCatalog(): Promise<Catalog> {
  const response = await fetch('/api/game/catalog', { cache: 'no-store', signal: AbortSignal.timeout(10000) })
  if (!response.ok) throw new Error('load')
  return parseCatalog(await response.json())
}

const source = 'EDUCATIONAL_CONTENT_COMPLETION' 

export default function GameCatalogPage() {
  const { t } = useLanguage()
  const { data: session } = useSession()
  const canManage = hasPermission(session?.user?.roles ?? [], 'game:manage')
  const [catalog, setCatalog] = useState<Catalog | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(true)
  const [dirty, setDirty] = useState(false)
  const [saved, setSaved] = useState(false)

  const load = useCallback(async () => {
    setBusy(true); setError(null); setSaved(false)
    try {
      setCatalog(await readCatalog()); setDirty(false)
    } catch { setError('load') } finally { setBusy(false) }
  }, [])
  useEffect(() => {
    let active = true
    void readCatalog().then(value => { if (active) setCatalog(value) })
      .catch(() => { if (active) setError('load') })
      .finally(() => { if (active) setBusy(false) })
    return () => { active = false }
  }, [])
  useEffect(() => {
    if (!dirty) return
    const warn = (event: BeforeUnloadEvent) => { event.preventDefault() }
    window.addEventListener('beforeunload', warn)
    return () => window.removeEventListener('beforeunload', warn)
  }, [dirty])

  function change(index: number, patch: Partial<Level>) {
    setCatalog(old => old && ({ ...old, levels: old.levels.map((l, i) => i === index ? { ...l, ...patch } : l) }))
    setDirty(true); setSaved(false)
  }
  async function save() {
    setBusy(true); setError(null); setSaved(false)
    try {
      const response = await fetch('/api/game/catalog', { method: 'PUT', headers: { 'content-type': 'application/json' }, body: JSON.stringify(catalog), signal: AbortSignal.timeout(10000) })
      if (!response.ok) { setError(response.status === 409 ? 'conflict' : response.status === 400 ? 'invalid' : 'save'); return }
      setCatalog(parseCatalog(await response.json())); setDirty(false); setSaved(true)
    } catch { setError('save') } finally { setBusy(false) }
  }
  return <AuthGuard permission="game:view"><section className={styles.page} aria-labelledby="game-catalog-title">
    <Link href="/loyalty">← {t('Lípa', 'Lípa')}</Link>
    <h1 id="game-catalog-title">{t('Lví stezka — správa kol', 'Lion Quest — chapters')}</h1>
    <p>{t('Nastavte pořadí, dostupnost a podmínky odemčení. Kolo 0 je Náplavka. Nové herní prostředí vyžaduje vydání mobilní aplikace.', 'Set order, availability and unlock requirements. Chapter 0 is Embankment. New worlds require a mobile app release.')}</p>
    <p>{t('LIVE: všichni hráči. BETA: testovací verze aplikace. OFF: skryté. Uložená změna se promítne při obnovení mapy.', 'LIVE: all players. BETA: testing builds. OFF: hidden. Saved changes appear when the map refreshes.')}</p>
    {error && <p role="alert">{error === 'conflict' ? t('Katalog mezitím někdo změnil. Vaše úpravy zůstaly v tomto formuláři; před dalším uložením načtěte aktuální verzi.', 'Someone changed the catalogue. Your edits remain in this form; reload the current version before saving again.') : error === 'invalid' ? t('Zkontrolujte jedinečné pořadí, názvy a časové období kol.', 'Check unique order, titles and chapter dates.') : t('Katalog se nepodařilo načíst nebo uložit. Zkuste akci znovu.', 'Could not load or save the catalogue. Please retry.')} </p>}
    {saved && <p role="status">{t('Změny jsou uložené a zapsané v auditu.', 'Changes saved and recorded in the audit.')}</p>}
    {!catalog && busy && <p role="status">{t('Načítám kola…', 'Loading chapters…')}</p>}
    {catalog && <form onSubmit={e => { e.preventDefault(); void save() }}>
      <p>{t('Verze katalogu', 'Catalogue revision')}: {catalog.revision}</p>
      {catalog.levels.map((level, index) => <fieldset key={index} disabled={busy || !canManage} className={styles.level}>
        <legend>{level.order} · {level.titleCs} · {level.id}</legend>
        <label>ID<input required pattern="[a-z][a-z0-9_]{0,63}" value={level.id} onChange={e => change(index, { id: e.target.value })} /></label>
        <label>{t('Prostředí', 'Theme')}<input required pattern="[a-z][a-z0-9_]{0,63}" value={level.theme} onChange={e => change(index, { theme: e.target.value })} /></label>
        <label>{t('Pořadí', 'Order')}<input type="number" min="0" max="99" required value={level.order} onChange={e => change(index, { order: Number(e.target.value) })} /></label>
        <label>{t('Dostupnost', 'Availability')}<select value={level.state} onChange={e => change(index, { state: e.target.value as Level['state'] })}>{['OFF', 'BETA', 'LIVE'].map(s => <option key={s}>{s}</option>)}</select></label>
        <label>{t('Český název', 'Czech title')}<input required maxLength={80} value={level.titleCs} onChange={e => change(index, { titleCs: e.target.value })} /></label>
        <label>{t('Anglický název', 'English title')}<input required maxLength={80} value={level.titleEn} onChange={e => change(index, { titleEn: e.target.value })} /></label>
        <label>{t('Začátek (UTC)', 'Starts (UTC)')}<input type="datetime-local" value={level.startsAt?.slice(0, 16) ?? ''} onChange={e => change(index, { startsAt: e.target.value ? `${e.target.value}:00Z` : null })} /></label>
        <label>{t('Konec (UTC)', 'Ends (UTC)')}<input type="datetime-local" value={level.endsAt?.slice(0, 16) ?? ''} onChange={e => change(index, { endsAt: e.target.value ? `${e.target.value}:00Z` : null })} /></label>
        <label>{t('Odemčení', 'Unlock')}<select value={level.requirement.kind} onChange={e => { const kind = e.target.value as Requirement['kind']; change(index, { requirement: { ...level.requirement, kind, source: kind === 'EARN_SOURCE' ? source : null } }) }}>
          <option value="NONE">{t('Bez podmínky', 'No requirement')}</option><option value="PREVIOUS_LEVEL">{t('Dokončení předchozího kola', 'Complete previous chapter')}</option><option value="EARN_SOURCE">{t('Bankovní úspěch: dokončené vzdělávání', 'Bank achievement: completed education')}</option>
        </select></label>
        {level.requirement.kind === 'EARN_SOURCE' && <label>{t('Počet dokončení', 'Completion count')}<input type="number" min={1} max={1000} required value={level.requirement.count} onChange={e => change(index, { requirement: { ...level.requirement, count: Number(e.target.value) } })} /></label>}
        <label>{t('Česká nápověda k odemčení', 'Czech unlock hint')}<input maxLength={160} value={level.requirement.hintCs} onChange={e => change(index, { requirement: { ...level.requirement, hintCs: e.target.value } })} /></label>
        <label>{t('Anglická nápověda k odemčení', 'English unlock hint')}<input maxLength={160} value={level.requirement.hintEn} onChange={e => change(index, { requirement: { ...level.requirement, hintEn: e.target.value } })} /></label>
      </fieldset>)}
      {canManage && <button type="submit" disabled={busy || !dirty}>{busy ? t('Ukládám…', 'Saving…') : t('Uložit změny', 'Save changes')}</button>}
    </form>}
    <button type="button" disabled={busy} onClick={() => { if (!dirty || window.confirm(t('Zahodit neuložené úpravy a načíst aktuální katalog?', 'Discard unsaved edits and reload the catalogue?'))) void load() }}>{t('Načíst aktuální verzi', 'Reload current version')}</button>
  </section></AuthGuard>
}
