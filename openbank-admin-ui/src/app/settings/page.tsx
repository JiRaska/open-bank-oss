// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

'use client'

import { useState } from 'react'
import { User, Bell, Shield, Globe, Key, Info } from 'lucide-react'
import { useSession } from 'next-auth/react'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { AuthGuard } from '@/components/auth/AuthGuard'
import { ROLE_LABELS } from '@/lib/auth/roles'
import { PageHeader } from '@/components/ui/PageHeader'

type Tab = 'profile' | 'notifications' | 'security' | 'api' | 'regional'

export default function SettingsPage() {
  const [tab, setTab] = useState<Tab>('profile')
  const { t } = useLanguage()
  const tabs: { id: Tab; label: string; icon: React.ReactNode }[] = [
    { id: 'profile', label: t('Profil', 'Profile'), icon: <User size={14} aria-hidden="true" /> },
    { id: 'notifications', label: t('Oznámení', 'Notifications'), icon: <Bell size={14} aria-hidden="true" /> },
    { id: 'security', label: t('Zabezpečení', 'Security'), icon: <Shield size={14} aria-hidden="true" /> },
    { id: 'api', label: t('API klíče', 'API Keys'), icon: <Key size={14} aria-hidden="true" /> },
    { id: 'regional', label: t('Jazyk', 'Language'), icon: <Globe size={14} aria-hidden="true" /> },
  ]

  return <AuthGuard permission="settings:view"><div>
    <PageHeader
      breadcrumb={<div className="breadcrumb"><span>OpenBank</span><span className="breadcrumb-sep">/</span><span className="breadcrumb-current">{t('Nastavení', 'Settings')}</span></div>}
      icon={<User size={20} aria-hidden="true" />}
      title={t('Nastavení', 'Settings')}
      subtitle={t('Skutečné předvolby a přístup k účtu', 'Actual account preferences and access')}
    />
    <div style={{ display: 'flex', gap: '20px', alignItems: 'flex-start' }}>
      <div role="tablist" aria-label={t('Sekce nastavení', 'Settings sections')} className="card" style={{ width: '200px', flexShrink: 0, padding: '8px' }}>
        {tabs.map(item => <button key={item.id} id={`settings-tab-${item.id}`} role="tab" type="button" onClick={() => setTab(item.id)} aria-selected={tab === item.id} aria-controls={`settings-panel-${item.id}`} style={{ width: '100%', display: 'flex', alignItems: 'center', gap: '9px', padding: '8px 10px', borderRadius: '6px', border: 'none', borderLeft: tab === item.id ? '2px solid var(--accent)' : '2px solid transparent', background: tab === item.id ? 'var(--accent-light)' : 'transparent', color: tab === item.id ? 'var(--accent)' : 'var(--text-secondary)', fontWeight: tab === item.id ? 600 : 400, fontSize: '13px', cursor: 'pointer', textAlign: 'left', fontFamily: 'inherit' }}>{item.icon}{item.label}</button>)}
      </div>
      <div id={`settings-panel-${tab}`} role="tabpanel" aria-labelledby={`settings-tab-${tab}`} style={{ flex: 1 }}>
        {tab === 'profile' && <ProfileTab />}
        {tab === 'notifications' && <UnavailableSettings title={t('Předvolby oznámení', 'Notification preferences')} detail={t('Osobní předvolby zatím nemají podporovaný backendový kontrakt. Konzole proto nezobrazuje falešné přepínače ani neukládá zdánlivé změny.', 'Personal notification preferences do not yet have a supported backend contract. This console therefore does not show fake switches or pretend to save changes.')} />}
        {tab === 'security' && <UnavailableSettings title={t('Zabezpečení a relace', 'Security and sessions')} detail={t('Hesla, relace a odvolání přístupu spravuje Keycloak SSO. Konzole zobrazuje skutečnou přihlášenou identitu a nebude simulovat změnu hesla ani odvolání relace.', 'Passwords, sessions and access revocation are managed by Keycloak SSO. The console shows only the real signed-in identity and does not simulate password changes or session revocation.')} />}
        {tab === 'api' && <UnavailableSettings title={t('API klíče', 'API keys')} detail={t('Správa API klíčů není v tomto admin UI integrována s autoritativním systémem identit. Nezobrazujeme proto vzorové klíče ani akce generovat či odvolat.', 'API-key management is not integrated with the authoritative identity system in this admin UI. Sample keys and generate/revoke actions are therefore not displayed.')} />}
        {tab === 'regional' && <RegionalTab />}
      </div>
    </div>
  </div></AuthGuard>
}

function ProfileTab() {
  const { t } = useLanguage()
  const { data: session } = useSession()
  const name = session?.user?.name ?? ''
  const email = session?.user?.email ?? ''
  const roles = session?.user?.roles ?? []
  const initials = (name || email || '?').split(/\s+/).map(word => word[0]).join('').slice(0, 2).toUpperCase()
  return <div className="card"><div className="card-header"><span className="card-header-title">{t('Informace o profilu', 'Profile information')}</span></div><div style={{ padding: '20px', display: 'flex', flexDirection: 'column', gap: '16px' }}>
    <div style={{ display: 'flex', alignItems: 'center', gap: '16px', paddingBottom: '16px', borderBottom: '1px solid var(--border)' }}><div aria-hidden="true" style={{ width: '56px', height: '56px', borderRadius: '50%', background: 'var(--accent)', display: 'grid', placeItems: 'center', fontSize: '20px', fontWeight: 700, color: '#fff' }}>{initials}</div><div><div style={{ fontSize: '14px', fontWeight: 600, color: 'var(--text-primary)' }}>{name || '—'}</div><div style={{ fontSize: '12px', color: 'var(--text-tertiary)', marginTop: '2px' }}>{email || '—'}</div></div></div>
    <div><div style={{ fontSize: '12px', fontWeight: 600, color: 'var(--text-secondary)', marginBottom: '8px' }}>{t('Role (Keycloak)', 'Roles (Keycloak)')}</div><div style={{ display: 'flex', flexWrap: 'wrap', gap: '6px' }}>{roles.length === 0 ? <span style={{ fontSize: '12px', color: 'var(--text-tertiary)' }}>—</span> : roles.map(role => { const meta = ROLE_LABELS[role]; return <span key={role} style={{ fontSize: '11px', fontWeight: 600, padding: '3px 8px', borderRadius: '10px', color: meta?.color ?? 'var(--text-secondary)', background: meta?.bg ?? 'var(--surface-3)' }}>{meta?.label ?? role}</span> })}</div><p style={{ fontSize: '12px', color: 'var(--text-tertiary)', margin: '10px 0 0' }}>{t('Profil a přístup spravuje Keycloak; údaje nahoře jsou skutečná přihlášená identita.', 'Keycloak manages the profile and access; the details above are the actual signed-in identity.')}</p></div>
  </div></div>
}

function RegionalTab() {
  const { language, setLanguage, t } = useLanguage()
  return <div className="card"><div className="card-header"><span className="card-header-title">{t('Jazyk', 'Language')}</span></div><div style={{ padding: '20px' }}><label className="field">{t('Jazyk rozhraní', 'Interface language')}<select className="input" value={language} onChange={event => setLanguage(event.target.value as 'en' | 'cs')}><option value="en">English</option><option value="cs">Čeština</option></select></label><p style={{ margin: '12px 0 0', fontSize: '12px', color: 'var(--text-tertiary)' }}>{t('Volba se uloží do tohoto prohlížeče. Ostatní regionální volby zatím nejsou podporované serverem.', 'The choice is saved in this browser. Other regional preferences are not yet supported by the server.')}</p></div></div>
}

function UnavailableSettings({ title, detail }: { title: string; detail: string }) {
  return <div className="card" style={{ padding: '24px', display: 'flex', alignItems: 'flex-start', gap: '12px' }}><Info size={18} aria-hidden="true" style={{ color: 'var(--info-text)', flexShrink: 0, marginTop: '1px' }} /><div><h2 style={{ margin: 0, fontSize: '15px', color: 'var(--text-primary)' }}>{title}</h2><p style={{ margin: '6px 0 0', fontSize: '13px', color: 'var(--text-secondary)', lineHeight: 1.55 }}>{detail}</p></div></div>
}
