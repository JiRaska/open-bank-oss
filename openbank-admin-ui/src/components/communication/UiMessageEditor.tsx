// SPDX-License-Identifier: Apache-2.0
'use client'

import { useState } from 'react'
import { useLanguage } from '@/lib/i18n/LanguageContext'

export const UI_MESSAGE_KEYS = [
  ['status.loading', 'Načítání přehledu', 'Loading overview'],
  ['status.unavailable', 'Výpadek služby', 'Service unavailable'],
  ['err.loadAccounts', 'Nelze načíst zůstatky', 'Balances unavailable'],
  ['err.staleAccounts', 'Neaktuální zůstatky', 'Stale balances'],
  ['err.session', 'Vypršelé přihlášení', 'Session expired'],
  ['prod.loading', 'Načítání produktů', 'Loading products'],
  ['prod.unavailable', 'Produkty nedostupné', 'Products unavailable'],
  ['prod.retry', 'Tlačítko opakování', 'Retry button'],
  ['send.processing', 'Zpracování platby', 'Processing payment'],
  ['send.accepted.sub', 'Čekání na dokončení platby', 'Awaiting payment completion'],
] as const

export function UiMessageEditor({ value, onChange, disabled }: {
  value: Record<string, string>
  onChange: (value: Record<string, string>) => void
  disabled: boolean
}) {
  const { t } = useLanguage()
  const [locale, setLocale] = useState('cs')
  return <fieldset disabled={disabled} style={{ border: '1px solid var(--border)', borderRadius: 8, padding: 16 }}>
    <legend>{t('Hlášky lva a lvice v aplikaci', 'Lion and lioness app messages')}</legend>
    <p style={{ color: 'var(--text-secondary)', fontSize: 13 }}>
      {t('Krátce, srozumitelně a empaticky. Při výpadku se omluvíme; při čekání na platbu aktivně hledáme výsledek. Prázdné pole použije výchozí text aplikace. Změny projdou schválením.',
        'Keep it short, clear and empathetic. Apologise during outages; actively check pending payments. Empty fields use the bundled app text. Changes require approval.')}
    </p>
    <label>{t('Jazyk hlášek', 'Message language')}
      <select className="input" value={locale} onChange={e => setLocale(e.target.value)}>
        <option value="cs">Čeština</option><option value="en">English</option>
      </select>
    </label>
    {UI_MESSAGE_KEYS.map(([key, cs, en]) => {
      const fullKey = `${locale}.${key}`
      return <label key={fullKey} style={{ display: 'block', marginTop: 12, fontSize: 13 }}>
        {t(cs, en)}
        <textarea className="input" rows={2} maxLength={240} value={value[fullKey] ?? ''}
          placeholder={t('Výchozí text aplikace', 'Bundled app text')}
          onChange={e => {
            const next = { ...value }
            if (e.target.value.trim()) next[fullKey] = e.target.value
            else delete next[fullKey]
            onChange(next)
          }} />
        <small style={{ color: 'var(--text-muted)' }}>{(value[fullKey] ?? '').length}/240</small>
        {value[fullKey] && <p aria-label={t('Náhled hlášky', 'Message preview')} style={{ color: 'var(--text-primary)' }}>{value[fullKey]}</p>}
      </label>
    })}
  </fieldset>
}
