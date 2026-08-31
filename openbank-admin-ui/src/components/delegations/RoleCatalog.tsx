// SPDX-License-Identifier: Apache-2.0
'use client'

import { useCallback, useEffect, useMemo, useState } from 'react'
import { Check, Pencil, Plus, Shield, Trash2, X } from 'lucide-react'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { useAuth } from '@/lib/auth/useAuth'
import { CAPABILITIES_BY_RESOURCE, type DelegationResource, type RolePreset } from '@/lib/delegations/rolePresets'

const emptyRole = (): RolePreset => ({ id: '', name: '', description: '', resourceType: 'ACCOUNT', capabilities: [] })

export function RoleCatalog() {
  const { t } = useLanguage()
  const { hasRole } = useAuth()
  const canManage = hasRole('ROLE_ADMIN')
  const [roles, setRoles] = useState<RolePreset[]>([])
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

  const rights = useMemo(() => [...new Set(roles.flatMap(role => role.capabilities))].sort(), [roles])
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
    {state === 'ready' && <div style={{ overflowX: 'auto' }}><table className="table" style={{ width: '100%' }}><thead><tr><th>{t('Role', 'Role')}</th><th>{t('Zdroj', 'Resource')}</th>
      {rights.map(right => <th key={right} title={right} style={{ textAlign: 'center', fontSize: 10 }}>{shortRight(right)}</th>)}{canManage && <th aria-label={t('Akce', 'Actions')} />}</tr></thead>
      <tbody>{roles.map(role => <tr key={role.id}><td><strong>{role.name}</strong><div style={{ fontSize: 11, color: 'var(--text-tertiary)', maxWidth: 260 }}>{role.description}</div></td><td>{role.resourceType}</td>
        {rights.map(right => <td key={right} style={{ textAlign: 'center' }}>{role.capabilities.includes(right) ? <Check size={15} color="var(--success)" aria-label={t('Povoleno', 'Allowed')} /> : '—'}</td>)}
        {canManage && <td><div style={{ display: 'flex', gap: 4 }}><button className="btn btn-secondary" onClick={() => setEditing({ ...role, capabilities: [...role.capabilities] })} aria-label={`${t('Upravit', 'Edit')} ${role.name}`}><Pencil size={13} /></button><button className="btn btn-secondary" onClick={() => void remove(role)} aria-label={`${t('Smazat', 'Delete')} ${role.name}`}><Trash2 size={13} /></button></div></td>}</tr>)}</tbody></table></div>}
    {editing && <Editor value={editing} cancel={() => setEditing(null)} save={save} />}
  </section>
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
    <fieldset style={{ border: 0, padding: 0, margin: '14px 0' }}><legend style={{ fontWeight: 700, fontSize: 13, marginBottom: 8 }}>{t('Práva', 'Rights')}</legend>{allowed.map(right => <label key={right} style={{ display: 'flex', gap: 8, padding: 8 }}><input type="checkbox" checked={role.capabilities.includes(right)} onChange={() => toggle(right)} /><code>{right}</code></label>)}</fieldset>
    <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 8 }}><button className="btn btn-secondary" onClick={cancel}>{t('Zrušit', 'Cancel')}</button><button className="btn btn-primary" disabled={!role.name.trim() || !role.capabilities.length} onClick={() => void save(role)}>{t('Uložit', 'Save')}</button></div>
  </div></div>
}
const labelStyle = { display: 'grid', gap: 6, fontSize: 13, fontWeight: 600, marginBottom: 12 } as const
const shortRight = (right: string) => right.replace(/^(ACCOUNT|SAVINGS|CARD|OBJECT)_/, '')
