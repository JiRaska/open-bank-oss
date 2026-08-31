// SPDX-License-Identifier: Apache-2.0
'use client'

import { useCallback, useEffect, useState } from 'react'
import { Check, LayoutGrid, Pencil, Plus, Shield, Table2, Trash2, X } from 'lucide-react'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { useAuth } from '@/lib/auth/useAuth'
import { CAPABILITIES_BY_RESOURCE, capabilityIntent, capabilityLabel, type CapabilityIntent, type DelegationResource, type RolePreset } from '@/lib/delegations/rolePresets'

const emptyRole = (): RolePreset => ({ id: '', name: '', description: '', resourceType: 'ACCOUNT', capabilities: [] })

export function RoleCatalog() {
  const { t } = useLanguage()
  const { hasRole } = useAuth()
  const canManage = hasRole('ROLE_ADMIN')
  const [roles, setRoles] = useState<RolePreset[]>([])
  const [resource, setResource] = useState<DelegationResource>('ACCOUNT')
  const [view, setView] = useState<'overview' | 'matrix'>('overview')
  const [editing, setEditing] = useState<RolePreset | null>(null)
  const [state, setState] = useState<'loading' | 'ready' | 'error'>('loading')

  const load = useCallback(async () => {
    setState('loading')
    try {
      const response = await fetch('/api/delegation-role-presets', { cache: 'no-store', signal: AbortSignal.timeout(6000) })
      if (!response.ok) throw new Error('load failed')
      setRoles(await response.json() as RolePreset[])
      setState('ready')
    } catch { setState('error') }
  }, [])
  useEffect(() => {
    queueMicrotask(() => { void load() })
  }, [load])

  const rights = CAPABILITIES_BY_RESOURCE[resource]
  const visibleRoles = roles.filter(role => role.resourceType === resource)
  const save = async (role: RolePreset) => {
    const payload = { name: role.name.trim(), description: role.description.trim(), resourceType: role.resourceType, capabilities: role.capabilities }
    const response = await fetch(role.id ? `/api/delegation-role-presets/${role.id}` : '/api/delegation-role-presets', {
      method: role.id ? 'PUT' : 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify(payload),
    })
    if (!response.ok) return
    setEditing(null); await load()
  }
  const remove = async (role: RolePreset) => {
    if (!window.confirm(t(`Smazat roli „${role.name}“? Existující granty se nezmění.`, `Delete “${role.name}”? Existing grants will not change.`))) return
    const response = await fetch(`/api/delegation-role-presets/${role.id}`, { method: 'DELETE' })
    if (response.ok) await load()
  }

  return <section className="card" style={{ padding: 16, marginBottom: 20 }} aria-labelledby="role-catalog-title">
    <div style={{ display: 'flex', justifyContent: 'space-between', gap: 16, alignItems: 'flex-start', marginBottom: 14 }}>
      <div><h2 id="role-catalog-title" style={{ fontSize: 16, fontWeight: 700, display: 'flex', gap: 8, alignItems: 'center' }}><Shield size={17} color="var(--accent)" />{t('Dispoziční role a práva', 'Delegation roles and rights')}</h2>
        <p style={{ fontSize: 12, color: 'var(--text-tertiary)', marginTop: 3 }}>{t('Centrální presety pro nové delegace. Změna presetu nemění již udělená práva.', 'Central presets for new delegations. Changing one never alters existing grants.')}</p></div>
      {canManage && <button className="btn btn-primary" onClick={() => setEditing(emptyRole())}><Plus size={14} />{t('Přidat roli', 'Add role')}</button>}
    </div>
    {state === 'loading' && <div style={{ padding: 20, color: 'var(--text-tertiary)' }}>{t('Načítám katalog…', 'Loading catalog…')}</div>}
    {state === 'error' && <div style={{ padding: 20, color: 'var(--text-tertiary)' }}>{t('Katalog rolí není dostupný.', 'Role catalog is unavailable.')}</div>}
    {state === 'ready' && <>
      <div role="tablist" aria-label={t('Typ zdroje', 'Resource type')} style={{ display: 'flex', gap: 8, flexWrap: 'wrap', marginBottom: 12 }}>
        {(Object.keys(CAPABILITIES_BY_RESOURCE) as DelegationResource[]).map(item => {
          const count = roles.filter(role => role.resourceType === item).length
          return <button key={item} type="button" role="tab" aria-selected={resource === item} className={resource === item ? 'btn btn-primary' : 'btn btn-secondary'} onClick={() => setResource(item)}>
            {resourceLabel(item, t)} <span aria-label={t(`${count} rolí`, `${count} roles`)} style={{ opacity: .75 }}>({count})</span>
          </button>
        })}
      </div>
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 12, marginBottom: 12 }}>
        <p style={{ fontSize: 12, color: 'var(--text-tertiary)', margin: 0 }}>
          {t('Začněte nejmenším rozsahem práv, který člověk pro svou práci potřebuje.', 'Start with the smallest set of rights a person needs for their work.')}
        </p>
        <div role="group" aria-label={t('Zobrazení katalogu', 'Catalog view')} style={{ display: 'flex', gap: 4, flexShrink: 0 }}>
          <button type="button" className={view === 'overview' ? 'btn btn-primary' : 'btn btn-secondary'} aria-pressed={view === 'overview'} onClick={() => setView('overview')}><LayoutGrid size={14} />{t('Přehled', 'Overview')}</button>
          <button type="button" className={view === 'matrix' ? 'btn btn-primary' : 'btn btn-secondary'} aria-pressed={view === 'matrix'} onClick={() => setView('matrix')}><Table2 size={14} />{t('Porovnat', 'Compare')}</button>
        </div>
      </div>
      {view === 'overview' && <RoleOverview roles={visibleRoles} canManage={canManage} edit={setEditing} remove={remove} />}
      {view === 'matrix' && <div style={{ overflowX: 'auto' }}><table className="table" style={{ width: '100%', minWidth: 760 }}><thead><tr><th style={stickyRoleStyle}>{t('Role', 'Role')}</th>
      {rights.map(right => <th key={right} title={rightLabel(right, t)} style={{ textAlign: 'center', fontSize: 10, minWidth: 92 }}>{rightLabel(right, t)}</th>)}{canManage && <th aria-label={t('Akce', 'Actions')} />}</tr></thead>
      <tbody>{visibleRoles.map(role => <tr key={role.id}><td style={stickyRoleStyle}><strong>{role.name}</strong><div style={{ fontSize: 11, color: 'var(--text-tertiary)', maxWidth: 280 }}>{role.description}</div></td>
        {rights.map(right => <td key={right} style={{ textAlign: 'center' }}>{role.capabilities.includes(right) ? <Check size={15} color="var(--success)" aria-label={t('Povoleno', 'Allowed')} /> : '—'}</td>)}
        {canManage && <td><div style={{ display: 'flex', gap: 4 }}><button className="btn btn-secondary" onClick={() => setEditing({ ...role, capabilities: [...role.capabilities] })} aria-label={`${t('Upravit', 'Edit')} ${role.name}`}><Pencil size={13} /></button><button className="btn btn-secondary" onClick={() => void remove(role)} aria-label={`${t('Smazat', 'Delete')} ${role.name}`}><Trash2 size={13} /></button></div></td>}</tr>)}</tbody></table></div>
      }
    </>}
    {editing && <Editor value={editing} cancel={() => setEditing(null)} save={save} />}
  </section>
}

function RoleOverview({ roles, canManage, edit, remove }: { roles: RolePreset[]; canManage: boolean; edit: (role: RolePreset) => void; remove: (role: RolePreset) => Promise<void> }) {
  const { t } = useLanguage()
  return <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(min(100%, 330px), 1fr))', gap: 12 }}>
    {roles.map(role => <article key={role.id} style={{ border: '1px solid var(--border)', borderRadius: 12, padding: 16, background: 'linear-gradient(145deg, var(--surface-1), var(--surface-2))' }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', gap: 12, alignItems: 'flex-start' }}>
        <div><h3 style={{ fontSize: 15, fontWeight: 750 }}>{role.name}</h3><p style={{ fontSize: 12, color: 'var(--text-tertiary)', marginTop: 4, lineHeight: 1.5 }}>{role.description}</p></div>
        {canManage && <div style={{ display: 'flex', gap: 4 }}><button className="btn btn-secondary" onClick={() => edit({ ...role, capabilities: [...role.capabilities] })} aria-label={`${t('Upravit', 'Edit')} ${role.name}`}><Pencil size={13} /></button><button className="btn btn-secondary" onClick={() => void remove(role)} aria-label={`${t('Smazat', 'Delete')} ${role.name}`}><Trash2 size={13} /></button></div>}
      </div>
      <div style={{ display: 'grid', gap: 10, marginTop: 14 }}>
        {(['view', 'act', 'manage'] as CapabilityIntent[]).map(intent => {
          const capabilities = role.capabilities.filter(capability => capabilityIntent(capability) === intent)
          if (!capabilities.length) return null
          return <div key={intent}><div style={{ fontSize: 10, fontWeight: 800, letterSpacing: '.08em', textTransform: 'uppercase', color: intentColor(intent) }}>{intentLabel(intent, t)}</div>
            <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6, marginTop: 6 }}>{capabilities.map(capability => <span key={capability} title={capability} style={{ borderRadius: 999, padding: '5px 9px', fontSize: 11, background: 'var(--surface-3)', border: '1px solid var(--border)' }}>{rightLabel(capability, t)}</span>)}</div>
          </div>
        })}
      </div>
    </article>)}
  </div>
}

function Editor({ value, cancel, save }: { value: RolePreset; cancel: () => void; save: (role: RolePreset) => Promise<void> }) {
  const { t } = useLanguage(); const [role, setRole] = useState(value); const allowed = CAPABILITIES_BY_RESOURCE[role.resourceType]
  const changeResource = (resourceType: DelegationResource) => setRole({ ...role, resourceType, capabilities: [] })
  const toggle = (right: string) => setRole({ ...role, capabilities: role.capabilities.includes(right) ? role.capabilities.filter(item => item !== right) : [...role.capabilities, right] })
  return <div role="dialog" aria-modal="true" aria-labelledby="role-editor-title" style={{ position: 'fixed', inset: 0, zIndex: 1000, background: 'rgba(0,0,0,.55)', display: 'grid', placeItems: 'center', padding: 16 }}><div className="card" style={{ padding: 20, width: 'min(620px, 100%)' }}>
    <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 16 }}><h2 id="role-editor-title" style={{ fontWeight: 700 }}>{t('Nastavení dispoziční role', 'Delegation role settings')}</h2><button className="btn btn-secondary" onClick={cancel} aria-label={t('Zavřít', 'Close')}><X size={14} /></button></div>
    <label style={labelStyle}>{t('Název', 'Name')}<input className="input" maxLength={100} value={role.name} onChange={event => setRole({ ...role, name: event.target.value })} /></label>
    <label style={labelStyle}>{t('Popis', 'Description')}<textarea className="input" maxLength={500} value={role.description} onChange={event => setRole({ ...role, description: event.target.value })} /></label>
    <label style={labelStyle}>{t('Zdroj', 'Resource')}<select className="input" value={role.resourceType} onChange={event => changeResource(event.target.value as DelegationResource)}>{Object.keys(CAPABILITIES_BY_RESOURCE).map(resource => <option key={resource}>{resource}</option>)}</select></label>
    <fieldset style={{ border: 0, padding: 0, margin: '14px 0' }}><legend style={{ fontWeight: 700, fontSize: 13, marginBottom: 8 }}>{t('Práva', 'Rights')}</legend>{allowed.map(right => <label key={right} title={right} style={{ display: 'flex', gap: 8, padding: 8 }}><input type="checkbox" checked={role.capabilities.includes(right)} onChange={() => toggle(right)} /><span>{rightLabel(right, t)}</span></label>)}</fieldset>
    <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 8 }}><button className="btn btn-secondary" onClick={cancel}>{t('Zrušit', 'Cancel')}</button><button className="btn btn-primary" disabled={!role.name.trim() || !role.capabilities.length} onClick={() => void save(role)}>{t('Uložit', 'Save')}</button></div>
  </div></div>
}
const labelStyle = { display: 'grid', gap: 6, fontSize: 13, fontWeight: 600, marginBottom: 12 } as const
const stickyRoleStyle = { position: 'sticky', left: 0, zIndex: 1, minWidth: 240, background: 'var(--surface-1)' } as const
const RESOURCE_LABELS: Record<DelegationResource, [string, string]> = {
  ACCOUNT: ['Účet', 'Account'],
  CARD: ['Karta', 'Card'],
  SAVINGS_GOAL: ['Spoření', 'Savings'],
  PAYMENT: ['Platba', 'Payment'],
  STATEMENT: ['Výpis', 'Statement'],
  DOCUMENT: ['Dokument', 'Document'],
}
const resourceLabel = (resource: DelegationResource, t: (cs: string, en: string) => string) => {
  const label = RESOURCE_LABELS[resource]
  return t(label[0], label[1])
}
const intentLabel = (intent: CapabilityIntent, t: (cs: string, en: string) => string) => ({
  view: t('Může vidět', 'Can view'),
  act: t('Může provádět', 'Can act'),
  manage: t('Může spravovat', 'Can manage'),
})[intent]
const intentColor = (intent: CapabilityIntent) => ({ view: 'var(--success)', act: 'var(--warning)', manage: 'var(--accent)' })[intent]
const rightLabel = (right: string, t: (cs: string, en: string) => string) => {
  return t(capabilityLabel(right, 'cs'), capabilityLabel(right, 'en'))
}
