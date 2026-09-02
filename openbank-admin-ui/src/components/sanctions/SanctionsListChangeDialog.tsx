// SPDX-License-Identifier: Apache-2.0

'use client'

import { useRef } from 'react'
import { AlertTriangle } from 'lucide-react'
import { trapDialogFocus } from '@/lib/a11y/trapDialogFocus'
import { useLanguage } from '@/lib/i18n/LanguageContext'

export interface SanctionsList {
  id: string; listType: string; displayName: string; sourceUrl: string
  enabled: boolean; lastUpdatedAt?: string; lastEntryCount?: number
  cronHour: number; cronMinute: number; cronDays: string
}

export type SanctionsListChangeError = 'unauthorized' | 'unconfirmed' | null

export function retainEnabledSelectedListTypes(selectedListTypes: string[], lists: SanctionsList[]) {
  const enabledListTypes = new Set(lists.filter(list => list.enabled).map(list => list.listType))
  const retained = selectedListTypes.filter(listType => enabledListTypes.has(listType))
  return retained.length === selectedListTypes.length ? selectedListTypes : retained
}

export function SanctionsListChangeDialog({ list, enabled, busy, error, onCancel, onConfirm }: {
  list: SanctionsList
  enabled: boolean
  busy: boolean
  error: SanctionsListChangeError | boolean
  onCancel: () => void
  onConfirm: () => void
}) {
  const { t, language } = useLanguage()
  const dialogRef = useRef<HTMLDivElement>(null)
  const titleId = `sanctions-list-${list.id}-change-title`
  const impactId = `sanctions-list-${list.id}-change-impact`
  const governanceId = `sanctions-list-${list.id}-change-governance`
  const numberLocale = language === 'cs' ? 'cs-CZ' : 'en-GB'
  const failedUnauthorized = error === 'unauthorized'

  return <div
    ref={dialogRef}
    role="alertdialog"
    aria-modal="true"
    aria-labelledby={titleId}
    aria-describedby={`${impactId} ${governanceId}`}
    aria-busy={busy}
    onKeyDown={event => {
      if (event.key === 'Escape' && !busy) onCancel()
      trapDialogFocus(event, dialogRef.current)
    }}
    style={{ position: 'fixed', inset: 0, zIndex: 1200, background: 'rgba(15,23,42,.68)', display: 'grid', placeItems: 'center', padding: 20 }}
  ><div className="card" style={{ width: 'min(580px, 100%)', maxHeight: 'calc(100dvh - 40px)', overflowY: 'auto', padding: 22 }}>
    <div style={{ display: 'flex', gap: 10, alignItems: 'flex-start' }}>
      <AlertTriangle aria-hidden="true" size={19} style={{ color: enabled ? 'var(--success)' : 'var(--danger)', flexShrink: 0, marginTop: 2 }} />
      <div>
        <h2 id={titleId} style={{ margin: 0, fontSize: 17, fontWeight: 750 }}>
          {enabled
            ? t(`Obnovit automatické aktualizace pro „${list.displayName}“?`, `Resume automatic updates for “${list.displayName}”?`)
            : t(`Pozastavit automatické aktualizace pro „${list.displayName}“?`, `Pause automatic updates for “${list.displayName}”?`)}
        </h2>
        <p id={impactId} style={{ margin: '6px 0 0', fontSize: 12.5, lineHeight: 1.55, color: 'var(--text-secondary)' }}>
          {enabled
            ? t('Plánované stahování a funkce „stáhnout vše“ se obnoví. Seznam bude znovu dostupný pro výběr v nových manuálních kontrolách z této konzole; do aktuálního rozsahu ho ale nepřidáme bez vašeho výběru.', 'Scheduled and refresh-all downloads resume. The list becomes available for selection in new manual checks from this console, but it is not added to the current scope without your choice.')
            : t('Budoucí plánovaná stahování a spuštění „stáhnout vše“ tento seznam přeskočí; již probíhající stahování může doběhnout. Tato konzole odebere seznam z nových manuálních kontrol. Importované záznamy a dosavadní kontroly zůstanou uložené. API kontroly mimo tuto konzoli toto nastavení nevyloučí.', 'Future scheduled and refresh-all runs will skip this list; a download already in progress may finish. This console will remove the list from new manual checks. Imported entries and existing checks stay stored. API screenings outside this console are not excluded by this setting.')}
        </p>
      </div>
    </div>

    <div style={{ marginTop: 14, padding: '11px 12px', borderRadius: 8, background: 'var(--surface-2)', border: '1px solid var(--border)', fontSize: 12.5 }}>
      <div style={{ fontWeight: 750 }}>{list.displayName}</div>
      <div style={{ marginTop: 4, fontFamily: 'var(--font-mono)', color: 'var(--text-tertiary)' }}>{list.listType}</div>
      <div style={{ marginTop: 7 }}>
        {typeof list.lastEntryCount === 'number'
          ? `${list.lastEntryCount.toLocaleString(numberLocale)} ${t('záznamů', 'entries')}`
          : t('Počet záznamů není synchronizován', 'Entry count not synced')}
      </div>
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginTop: 10, fontWeight: 750 }}>
        <span>{list.enabled ? t('POVOLENO', 'ENABLED') : t('POZASTAVENO', 'PAUSED')}</span>
        <span aria-hidden="true" style={{ color: 'var(--text-tertiary)' }}>→</span>
        <span>{enabled ? t('POVOLENO', 'ENABLED') : t('POZASTAVENO', 'PAUSED')}</span>
      </div>
    </div>

    <p id={governanceId} style={{ margin: '12px 0 0', padding: '10px 12px', borderRadius: 8, color: 'var(--warning-text)', background: 'var(--warning-bg)', border: '1px solid var(--warning-border)', fontSize: 12, lineHeight: 1.5 }}>
      {t('Služba provede změnu ihned po potvrzení; požadavek nevstupuje do čtyřokého schvalování a současné API neukládá důvod změny.', 'The service applies this change immediately after confirmation; it does not enter the four-eyes approval queue, and the current API does not store a change reason.')}
    </p>

    {error && <p role="alert" style={{ margin: '12px 0 0', padding: '10px 12px', borderRadius: 8, color: 'var(--danger-text)', background: 'var(--danger-bg)', border: '1px solid var(--danger-border)', fontSize: 12, lineHeight: 1.5 }}>
      {failedUnauthorized
        ? t('Tato relace nemá oprávnění ke změně. Služba nový stav nepotvrdila; obnovte stav listů.', 'This session is not authorised to make the change. The service did not confirm a new state; refresh the list status.')
        : t('Služba změnu nepotvrdila. Zobrazený stav může být zastaralý; zopakovat stejný cílový stav je bezpečné.', 'The service did not confirm the change. The displayed state may be stale; retrying the same target state is safe.')}
    </p>}

    <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 8, marginTop: 18 }}>
      <button type="button" autoFocus className="btn btn-secondary" disabled={busy} onClick={onCancel}>
        {error
          ? t('Zavřít a obnovit stav', 'Close and refresh status')
          : enabled
            ? t('Ponechat pozastavené', 'Keep updates paused')
            : t('Ponechat automatické aktualizace', 'Keep automatic updates')}
      </button>
      <button type="button" className={enabled ? 'btn btn-primary' : 'btn btn-danger'} disabled={busy} aria-busy={busy} onClick={onConfirm}>
        {busy
          ? t('Provádím změnu…', 'Applying change…')
          : enabled
            ? t('Obnovit automatické aktualizace', 'Resume automatic updates')
            : t('Pozastavit automatické aktualizace', 'Pause automatic updates')}
      </button>
    </div>
  </div></div>
}
