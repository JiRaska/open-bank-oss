// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

import { useState } from 'react'
import { User, Bell, Shield, Globe, Key, Save, Eye, EyeOff } from 'lucide-react'
import { useSession } from 'next-auth/react'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { AuthGuard } from '@/components/auth/AuthGuard'
import { ROLE_LABELS } from '@/lib/auth/roles'

type Tab = 'profile' | 'notifications' | 'security' | 'api' | 'regional'

export default function SettingsPage() {
  const [tab, setTab] = useState<Tab>('profile')
  const { t } = useLanguage()

  return (
    <AuthGuard permission="settings:view">
    <div>
      <div className="page-header">
        <div>
          <div className="breadcrumb">
            <span>OpenBank</span>
            <span className="breadcrumb-sep">/</span>
            <span className="breadcrumb-current">{t('Nastavení', 'Settings')}</span>
          </div>
          <h1 className="page-title">{t('Nastavení', 'Settings')}</h1>
          <p className="page-subtitle">{t('Spravujte předvolby účtu a konfiguraci systému', 'Manage your account preferences and system configuration')}</p>
        </div>
      </div>

      <div style={{ display: 'flex', gap: '20px', alignItems: 'flex-start' }}>
        {/* Sidebar nav */}
        <div className="card" style={{ width: '200px', flexShrink: 0, padding: '8px' }}>
          {([
            { id: 'profile',       label: t('Profil', 'Profile'),             icon: <User size={14}/> },
            { id: 'notifications', label: t('Oznámení', 'Notifications'),     icon: <Bell size={14}/> },
            { id: 'security',      label: t('Zabezpečení', 'Security'),       icon: <Shield size={14}/> },
            { id: 'api',           label: t('API Klíče', 'API Keys'),         icon: <Key size={14}/> },
            { id: 'regional',      label: t('Regionální', 'Regional'),        icon: <Globe size={14}/> },
          ] as { id: Tab; label: string; icon: React.ReactNode }[]).map(item => (
            <button
              key={item.id}
              onClick={() => setTab(item.id)}
              style={{
                width: '100%',
                display: 'flex', alignItems: 'center', gap: '9px',
                padding: '8px 10px',
                borderRadius: '6px',
                border: 'none',
                background: tab === item.id ? 'var(--accent-light)' : 'transparent',
                color: tab === item.id ? 'var(--accent)' : 'var(--text-secondary)',
                fontWeight: tab === item.id ? 600 : 400,
                fontSize: '13px',
                cursor: 'pointer',
                textAlign: 'left',
                transition: 'all 0.12s',
                fontFamily: 'inherit',
                borderLeft: tab === item.id ? '2px solid var(--accent)' : '2px solid transparent',
              }}
              onMouseEnter={e => { if (tab !== item.id) (e.currentTarget as HTMLElement).style.background = 'var(--surface-3)' }}
              onMouseLeave={e => { if (tab !== item.id) (e.currentTarget as HTMLElement).style.background = 'transparent' }}
            >
              {item.icon}
              {item.label}
            </button>
          ))}
        </div>

        {/* Content */}
        <div style={{ flex: 1 }}>
          {tab === 'profile'       && <ProfileTab />}
          {tab === 'notifications' && <NotificationsTab />}
          {tab === 'security'      && <SecurityTab />}
          {tab === 'api'           && <ApiKeysTab />}
          {tab === 'regional'      && <RegionalTab />}
        </div>
      </div>
    </div>
    </AuthGuard>
  )
}

/* ── Profile ─────────────────────────────────────────────────────────────── */
function ProfileTab() {
  const { t } = useLanguage()
  const { data: session } = useSession()
  const name = session?.user?.name ?? ''
  const email = session?.user?.email ?? ''
  const roles = session?.user?.roles ?? []
  const initials = (name || email || '?').split(/\s+/).map(w => w[0]).join('').slice(0, 2).toUpperCase()

  return (
    <div className="card">
      <div className="card-header">
        <span className="card-header-title">{t('Informace o profilu', 'Profile Information')}</span>
      </div>
      <div style={{ padding: '20px', display: 'flex', flexDirection: 'column', gap: '16px' }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: '16px', paddingBottom: '16px', borderBottom: '1px solid var(--border)' }}>
          <div style={{
            width: '56px', height: '56px',
            borderRadius: '50%',
            background: 'var(--accent)',
            display: 'flex', alignItems: 'center', justifyContent: 'center',
            fontSize: '20px', fontWeight: 700, color: '#fff',
            flexShrink: 0,
          }}>{initials}</div>
          <div>
            <div style={{ fontSize: '14px', fontWeight: 600, color: 'var(--text-primary)' }}>{name || '—'}</div>
            <div style={{ fontSize: '12px', color: 'var(--text-tertiary)', marginTop: '2px' }}>{email || '—'}</div>
          </div>
        </div>

        <div>
          <div style={{ fontSize: '12px', fontWeight: 600, color: 'var(--text-secondary)', marginBottom: '8px' }}>
            {t('Role (Keycloak)', 'Roles (Keycloak)')}
          </div>
          <div style={{ display: 'flex', flexWrap: 'wrap', gap: '6px' }}>
            {roles.length === 0 && <span style={{ fontSize: '12px', color: 'var(--text-tertiary)' }}>—</span>}
            {roles.map(r => {
              const meta = ROLE_LABELS[r]
              return (
                <span key={r} style={{
                  fontSize: '11px', fontWeight: 600, padding: '3px 8px', borderRadius: '10px',
                  color: meta?.color ?? 'var(--text-secondary)', background: meta?.bg ?? 'var(--surface-3)',
                }}>
                  {meta?.label ?? r}
                </span>
              )
            })}
          </div>
          <div style={{ fontSize: '11px', color: 'var(--text-tertiary)', marginTop: '10px' }}>
            {t('Profil se spravuje v Keycloaku; tato obrazovka zobrazuje skutečnou přihlášenou identitu.',
               'Your profile is managed in Keycloak; this screen shows the real signed-in identity.')}
          </div>
        </div>
      </div>
    </div>
  )
}

/* ── Notifications ───────────────────────────────────────────────────────── */
function NotificationsTab() {
  const { t } = useLanguage()
  const items = [
    { label: t('Výstrahy stavu služeb', 'Service health alerts'),    desc: t('Upozornit při přechodu služby do stavu DOWN nebo DEGRADED', 'Notify when a service goes DOWN or DEGRADED'), defaultOn: true  },
    { label: t('Selhané transakce', 'Failed transactions'),      desc: t('Upozornit na selhání transakcí nad prahovou hodnotou', 'Alert on transaction failures above threshold'), defaultOn: true  },
    { label: t('Nová otevření účtů', 'New account openings'),     desc: t('Upozornit při každém otevření nového účtu', 'Notify on each new account created'),            defaultOn: false },
    { label: t('Otevření circuit breakeru', 'Circuit breaker trips'),    desc: t('Upozornit při otevření circuit breakeru', 'Alert when a circuit breaker opens'),            defaultOn: true  },
    { label: t('Varování o limitu požadavků', 'Rate limit warnings'),      desc: t('Varovat při poklesu kapacity limitu pod 20 %', 'Warn when rate limit capacity drops below 20%'), defaultOn: false },
    { label: t('Události auditního záznamu', 'Audit log events'),         desc: t('Upozornit na vysoce závažné události auditu', 'Notify on high-severity audit events'),          defaultOn: false },
  ]
  return (
    <div className="card">
      <div className="card-header">
        <span className="card-header-title">{t('Předvolby oznámení', 'Notification Preferences')}</span>
      </div>
      <div>
        {items.map((item, i) => (
          <NotifRow key={i} label={item.label} desc={item.desc} defaultOn={item.defaultOn} last={i === items.length - 1} />
        ))}
      </div>
      <div style={{ padding: '14px 18px', borderTop: '1px solid var(--border)', display: 'flex', justifyContent: 'flex-end' }}>
        <button className="btn btn-primary"><Save size={13}/> {t('Uložit předvolby', 'Save preferences')}</button>
      </div>
    </div>
  )
}

function NotifRow({ label, desc, defaultOn, last }: { label: string; desc: string; defaultOn: boolean; last: boolean }) {
  const [on, setOn] = useState(defaultOn)
  return (
    <div style={{
      display: 'flex', alignItems: 'center', justifyContent: 'space-between',
      padding: '13px 18px',
      borderBottom: last ? 'none' : '1px solid var(--border)',
    }}>
      <div>
        <div style={{ fontSize: '13px', fontWeight: 500, color: 'var(--text-primary)' }}>{label}</div>
        <div style={{ fontSize: '12px', color: 'var(--text-tertiary)', marginTop: '2px' }}>{desc}</div>
      </div>
      <Toggle on={on} onChange={setOn} />
    </div>
  )
}

/* ── Security ────────────────────────────────────────────────────────────── */
function SecurityTab() {
  const { t } = useLanguage()
  const [showCurrent, setShowCurrent] = useState(false)
  const [showNew, setShowNew]         = useState(false)

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: '14px' }}>
      {/* Change password */}
      <div className="card">
        <div className="card-header"><span className="card-header-title">{t('Změna hesla', 'Change Password')}</span></div>
        <div style={{ padding: '20px', display: 'flex', flexDirection: 'column', gap: '14px' }}>
          <PasswordField label={t('Současné heslo', 'Current password')} show={showCurrent} onToggle={() => setShowCurrent(v => !v)} />
          <PasswordField label={t('Nové heslo', 'New password')}     show={showNew}    onToggle={() => setShowNew(v => !v)} />
          <PasswordField label={t('Potvrdit nové heslo', 'Confirm new password')} show={showNew} onToggle={() => setShowNew(v => !v)} />
          <div style={{ display: 'flex', justifyContent: 'flex-end' }}>
            <button className="btn btn-primary"><Save size={13}/> {t('Aktualizovat heslo', 'Update password')}</button>
          </div>
        </div>
      </div>

      {/* Sessions */}
      <div className="card">
        <div className="card-header"><span className="card-header-title">{t('Aktivní relace', 'Active Sessions')}</span></div>
        {[
          { device: 'Chrome · macOS',  ip: '192.168.1.10', time: t('Právě teď', 'Now'),        current: true  },
          { device: 'Firefox · Linux', ip: '10.0.0.5',     time: t('Před 2 hod.', '2h ago'),     current: false },
        ].map((s, i) => (
          <div key={i} style={{
            display: 'flex', alignItems: 'center', justifyContent: 'space-between',
            padding: '12px 18px',
            borderBottom: i === 0 ? '1px solid var(--border)' : 'none',
          }}>
            <div>
              <div style={{ fontSize: '13px', fontWeight: 500, color: 'var(--text-primary)', display: 'flex', alignItems: 'center', gap: '8px' }}>
                {s.device}
                {s.current && <span className="pill pill-success" style={{ fontSize: '10px' }}>{t('aktuální', 'current')}</span>}
              </div>
              <div style={{ fontSize: '12px', color: 'var(--text-tertiary)', marginTop: '2px' }}>
                {s.ip} · {s.time}
              </div>
            </div>
            {!s.current && (
              <button className="btn btn-ghost" style={{ fontSize: '12px', color: 'var(--danger)' }}>{t('Odvolat', 'Revoke')}</button>
            )}
          </div>
        ))}
      </div>
    </div>
  )
}

/* ── API Keys ────────────────────────────────────────────────────────────── */
function ApiKeysTab() {
  const { t } = useLanguage()
  const keys = [
    { name: 'CI/CD Pipeline',    prefix: 'ob_live_Kx9m…', created: '2026-01-15', lastUsed: t('Před 2 hod.', '2 hours ago'),  scope: 'read:all' },
    { name: 'Monitoring Agent',  prefix: 'ob_live_Rp2n…', created: '2026-02-01', lastUsed: t('Před 5 min.', '5 minutes ago'), scope: 'read:health' },
    { name: 'Audit Export',      prefix: 'ob_live_Tz7q…', created: '2026-03-10', lastUsed: t('Nikdy', 'Never'),         scope: 'read:audit' },
  ]
  return (
    <div className="card">
      <div className="card-header">
        <span className="card-header-title">{t('API klíče', 'API Keys')}</span>
        <button className="btn btn-primary" style={{ padding: '5px 12px', fontSize: '12px' }}>
          <Key size={12}/> {t('Vygenerovat klíč', 'Generate key')}
        </button>
      </div>
      <div style={{ overflowX: 'auto' }}>
        <table className="data-table">
          <thead>
            <tr>
              <th>{t('Název', 'Name')}</th>
              <th>{t('Prefix klíče', 'Key prefix')}</th>
              <th>{t('Rozsah', 'Scope')}</th>
              <th>{t('Vytvořeno', 'Created')}</th>
              <th>{t('Naposledy použito', 'Last used')}</th>
              <th style={{ textAlign: 'right' }}>{t('Akce', 'Actions')}</th>
            </tr>
          </thead>
          <tbody>
            {keys.map((k, i) => (
              <tr key={i}>
                <td style={{ fontWeight: 500 }}>{k.name}</td>
                <td><span className="mono" style={{ fontSize: '12px' }}>{k.prefix}</span></td>
                <td><span className="tag">{k.scope}</span></td>
                <td style={{ color: 'var(--text-secondary)', fontSize: '12px' }}>{k.created}</td>
                <td style={{ color: 'var(--text-secondary)', fontSize: '12px' }}>{k.lastUsed}</td>
                <td style={{ textAlign: 'right' }}>
                  <button className="btn btn-ghost" style={{ fontSize: '12px', color: 'var(--danger)', padding: '4px 10px' }}>{t('Odvolat', 'Revoke')}</button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <div style={{ padding: '12px 18px', borderTop: '1px solid var(--border)', background: 'var(--surface-2)', borderRadius: '0 0 var(--r-lg) var(--r-lg)' }}>
        <p style={{ fontSize: '12px', color: 'var(--text-tertiary)' }}>
          {t('API klíče umožňují programový přístup. Uložte je bezpečně — jsou zobrazeny pouze jednou při vytvoření.', 'API keys grant programmatic access. Store them securely — they are shown only once at creation.')}
        </p>
      </div>
    </div>
  )
}

/* ── Regional ────────────────────────────────────────────────────────────── */
function RegionalTab() {
  const { language, setLanguage, t } = useLanguage()

  return (
    <div className="card">
      <div className="card-header"><span className="card-header-title">{t('Regionální a lokalizace', 'Regional & Localisation')}</span></div>
      <div style={{ padding: '20px', display: 'flex', flexDirection: 'column', gap: '14px' }}>
        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '14px' }}>
          <SelectGroup label={t('Časové pásmo', 'Timezone')} value="Europe/Prague"  options={['Europe/Prague', 'Europe/London', 'UTC', 'America/New_York']} />
          <SelectGroup label={t('Formát data', 'Date format')} value="DD/MM/YYYY"     options={['DD/MM/YYYY', 'MM/DD/YYYY', 'YYYY-MM-DD']} />
          <SelectGroup label={t('Zobrazení měny', 'Currency display')} value="CZK"           options={['CZK', 'EUR', 'USD', 'GBP']} />
          <SelectGroup 
            label={t('Jazyk', 'Language')} 
            value={language}   
            onChange={(val) => setLanguage(val as 'en' | 'cs')}
            options={[{value: 'en', label: 'English'}, {value: 'cs', label: 'Čeština'}]} 
          />
        </div>
        <div style={{ display: 'flex', justifyContent: 'flex-end', paddingTop: '4px' }}>
          <button className="btn btn-primary"><Save size={13}/> {t('Uložit nastavení', 'Save settings')}</button>
        </div>
      </div>
    </div>
  )
}

/* ── Shared helpers ──────────────────────────────────────────────────────── */
function FieldGroup({ label, value, type = 'text' }: { label: string; value: string; type?: string }) {
  return (
    <div className="field">
      <label>{label}</label>
      <input className="input" type={type} defaultValue={value} />
    </div>
  )
}

function SelectGroup({ label, value, options, onChange }: { label: string; value: string; options: (string | {label: string, value: string})[], onChange?: (val: string) => void }) {
  const renderedOptions = options.map(o => {
    const optValue = typeof o === 'string' ? o : o.value
    const optLabel = typeof o === 'string' ? o : o.label
    return <option key={optValue} value={optValue}>{optLabel}</option>
  })

  return (
    <div className="field">
      <label>{label}</label>
      {onChange ? (
        <select className="input" value={value} onChange={e => onChange(e.target.value)}>
          {renderedOptions}
        </select>
      ) : (
        <select className="input" defaultValue={value}>
          {renderedOptions}
        </select>
      )}
    </div>
  )
}

function PasswordField({ label, show, onToggle }: { label: string; show: boolean; onToggle: () => void }) {
  return (
    <div className="field">
      <label>{label}</label>
      <div style={{ position: 'relative' }}>
        <input className="input" type={show ? 'text' : 'password'} style={{ width: '100%', paddingRight: '40px' }} />
        <button
          type="button"
          onClick={onToggle}
          style={{
            position: 'absolute', right: '10px', top: '50%', transform: 'translateY(-50%)',
            background: 'none', border: 'none', cursor: 'pointer', color: 'var(--text-tertiary)',
            display: 'flex', padding: '2px',
          }}
        >
          {show ? <EyeOff size={14}/> : <Eye size={14}/>}
        </button>
      </div>
    </div>
  )
}

function Toggle({ on, onChange }: { on: boolean; onChange: (v: boolean) => void }) {
  return (
    <button
      type="button"
      onClick={() => onChange(!on)}
      style={{
        width: '36px', height: '20px',
        borderRadius: '10px',
        background: on ? 'var(--accent)' : 'var(--border-strong)',
        border: 'none', cursor: 'pointer',
        position: 'relative', flexShrink: 0,
        transition: 'background 0.2s',
      }}
    >
      <span style={{
        position: 'absolute',
        top: '2px',
        left: on ? '18px' : '2px',
        width: '16px', height: '16px',
        borderRadius: '50%',
        background: '#fff',
        boxShadow: '0 1px 3px rgba(0,0,0,0.2)',
        transition: 'left 0.2s',
      }} />
    </button>
  )
}
