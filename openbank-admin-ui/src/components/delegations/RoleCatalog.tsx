// SPDX-License-Identifier: Apache-2.0
'use client'

import { useCallback, useEffect, useRef, useState } from 'react'
import { AlertTriangle, Check, LayoutGrid, Pencil, Plus, Shield, Table2, Trash2, X } from 'lucide-react'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { useAuth } from '@/lib/auth/useAuth'
import { trapDialogFocus } from '@/lib/a11y/trapDialogFocus'
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
  const [saving, setSaving] = useState(false)
  const [saveError, setSaveError] = useState(false)
  const [pendingRemoval, setPendingRemoval] = useState<RolePreset | null>(null)
  const [removing, setRemoving] = useState(false)
  const [removeError, setRemoveError] = useState(false)
  const removeReturnFocusRef = useRef<HTMLButtonElement | null>(null)
  const catalogTitleRef = useRef<HTMLHeadingElement | null>(null)
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
  const openEditor = (role: RolePreset) => {
    setSaveError(false)
    setEditing(role)
  }
  const closeEditor = () => {
    if (saving) return
    setEditing(null)
    setSaveError(false)
  }
  const save = async (role: RolePreset) => {
    const payload = { name: role.name.trim(), description: role.description.trim(), resourceType: role.resourceType, capabilities: role.capabilities }
    setSaving(true)
    setSaveError(false)
    try {
      const response = await fetch(role.id ? `/api/delegation-role-presets/${role.id}` : '/api/delegation-role-presets', {
        method: role.id ? 'PUT' : 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify(payload),
      })
      if (!response.ok) {
        setSaveError(true)
        return
      }
      setEditing(null)
      await load()
    } catch {
      setSaveError(true)
    } finally {
      setSaving(false)
    }
  }
  const requestRemoval = (role: RolePreset, trigger: HTMLButtonElement) => {
    removeReturnFocusRef.current = trigger
    setRemoveError(false)
    setPendingRemoval(role)
  }
  const closeRemoval = () => {
    if (removing) return
    setPendingRemoval(null)
    setRemoveError(false)
    queueMicrotask(() => removeReturnFocusRef.current?.focus())
  }
  const remove = async (role: RolePreset) => {
    setRemoving(true)
    setRemoveError(false)
    try {
      const response = await fetch(`/api/delegation-role-presets/${role.id}`, { method: 'DELETE' })
      if (!response.ok) {
        setRemoveError(true)
        return
      }
      setPendingRemoval(null)
      await load()
      queueMicrotask(() => catalogTitleRef.current?.focus())
    } catch {
      setRemoveError(true)
    } finally {
      setRemoving(false)
    }
  }

  return <section className="card" style={{ padding: 16, marginBottom: 20 }} aria-labelledby="role-catalog-title">
    <div style={{ display: 'flex', justifyContent: 'space-between', gap: 16, alignItems: 'flex-start', marginBottom: 14 }}>
      <div><h2 ref={catalogTitleRef} tabIndex={-1} id="role-catalog-title" style={{ fontSize: 16, fontWeight: 700, display: 'flex', gap: 8, alignItems: 'center' }}><Shield size={17} aria-hidden="true" color="var(--accent)" />{t('Dispoziční role a práva', 'Delegation roles and rights')}</h2>
        <p style={{ fontSize: 12, color: 'var(--text-tertiary)', marginTop: 3 }}>{t('Centrální presety pro nové delegace. Změna presetu nemění již udělená práva.', 'Central presets for new delegations. Changing one never alters existing grants.')}</p></div>
      {canManage && <button type="button" className="btn btn-primary" onClick={() => openEditor(emptyRole())}><Plus size={14} aria-hidden="true" />{t('Přidat roli', 'Add role')}</button>}
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
      {view === 'overview' && <RoleOverview roles={visibleRoles} canManage={canManage} edit={openEditor} remove={requestRemoval} />}
      {view === 'matrix' && <div style={{ overflowX: 'auto' }}><table className="table" style={{ width: '100%', minWidth: 760 }}><thead><tr><th style={stickyRoleStyle}>{t('Role', 'Role')}</th>
      {rights.map(right => <th key={right} title={rightLabel(right, t)} style={{ textAlign: 'center', fontSize: 10, minWidth: 92 }}>{rightLabel(right, t)}</th>)}{canManage && <th aria-label={t('Akce', 'Actions')} />}</tr></thead>
      <tbody>{visibleRoles.map(role => <tr key={role.id}><td style={stickyRoleStyle}><strong>{role.name}</strong><div style={{ fontSize: 11, color: 'var(--text-tertiary)', maxWidth: 280 }}>{role.description}</div></td>
        {rights.map(right => <td key={right} style={{ textAlign: 'center' }}>{role.capabilities.includes(right) ? <Check size={15} color="var(--success)" aria-label={t('Povoleno', 'Allowed')} /> : '—'}</td>)}
        {canManage && <td><div style={{ display: 'flex', gap: 4 }}><button type="button" className="btn btn-secondary" onClick={() => openEditor({ ...role, capabilities: [...role.capabilities] })} aria-label={`${t('Upravit', 'Edit')} ${role.name}`}><Pencil size={13} aria-hidden="true" /></button><button type="button" className="btn btn-secondary" onClick={event => requestRemoval(role, event.currentTarget)} aria-label={`${t('Smazat', 'Delete')} ${role.name}`}><Trash2 size={13} aria-hidden="true" /></button></div></td>}</tr>)}</tbody></table></div>
      }
    </>}
    {editing && <RoleEditor value={editing} busy={saving} failed={saveError} cancel={closeEditor} save={save} />}
    {pendingRemoval && <DeleteRoleDialog role={pendingRemoval} busy={removing} failed={removeError} onCancel={closeRemoval} onConfirm={() => void remove(pendingRemoval)} />}
  </section>
}

function RoleOverview({ roles, canManage, edit, remove }: { roles: RolePreset[]; canManage: boolean; edit: (role: RolePreset) => void; remove: (role: RolePreset, trigger: HTMLButtonElement) => void }) {
  const { t } = useLanguage()
  return <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(min(100%, 330px), 1fr))', gap: 12 }}>
    {roles.map(role => <article key={role.id} style={{ border: '1px solid var(--border)', borderRadius: 12, padding: 16, background: 'linear-gradient(145deg, var(--surface-1), var(--surface-2))' }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', gap: 12, alignItems: 'flex-start' }}>
        <div><h3 style={{ fontSize: 15, fontWeight: 750 }}>{role.name}</h3><p style={{ fontSize: 12, color: 'var(--text-tertiary)', marginTop: 4, lineHeight: 1.5 }}>{role.description}</p></div>
        {canManage && <div style={{ display: 'flex', gap: 4 }}><button type="button" className="btn btn-secondary" onClick={() => edit({ ...role, capabilities: [...role.capabilities] })} aria-label={`${t('Upravit', 'Edit')} ${role.name}`}><Pencil size={13} aria-hidden="true" /></button><button type="button" className="btn btn-secondary" onClick={event => remove(role, event.currentTarget)} aria-label={`${t('Smazat', 'Delete')} ${role.name}`}><Trash2 size={13} aria-hidden="true" /></button></div>}
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

export function DeleteRoleDialog({ role, busy, failed, onCancel, onConfirm }: { role: RolePreset; busy: boolean; failed: boolean; onCancel: () => void; onConfirm: () => void }) {
  const { t } = useLanguage()
  const dialogRef = useRef<HTMLDivElement>(null)
  const titleId = `delete-role-${role.id}-title`
  const impactId = `delete-role-${role.id}-impact`
  return <div
    ref={dialogRef}
    role="alertdialog"
    aria-modal="true"
    aria-labelledby={titleId}
    aria-describedby={impactId}
    aria-busy={busy}
    onKeyDown={event => {
      if (event.key === 'Escape' && !busy) onCancel()
      trapDialogFocus(event, dialogRef.current)
    }}
    style={{ position: 'fixed', inset: 0, zIndex: 1200, background: 'rgba(15,23,42,.65)', display: 'grid', placeItems: 'center', padding: 20 }}
  ><div className="card" style={{ width: 'min(440px, 100%)', padding: 22 }}>
    <div style={{ display: 'flex', gap: 10, alignItems: 'flex-start' }}>
      <AlertTriangle size={19} aria-hidden="true" style={{ color: 'var(--danger)', flexShrink: 0, marginTop: 2 }} />
      <div><h2 id={titleId} style={{ margin: 0, fontSize: 16, fontWeight: 750 }}>{t(`Smazat roli „${role.name}“?`, `Delete “${role.name}”?`)}</h2>
        <p id={impactId} style={{ margin: '6px 0 0', color: 'var(--text-secondary)', fontSize: 12.5, lineHeight: 1.5 }}>{t('Preset přestane být dostupný pro nové delegace. Již udělená práva se nezmění ani neodvolají.', 'The preset will no longer be available for new delegations. Existing grants will not change or be revoked.')}</p></div>
    </div>
    {failed && <p role="alert" style={{ margin: '14px 0 0', padding: '10px 12px', borderRadius: 8, color: 'var(--danger-text)', background: 'var(--danger-bg)', border: '1px solid var(--danger-border)', fontSize: 12 }}>{t('Roli se nepodařilo smazat. Nic se nezměnilo; zkuste to znovu.', 'The role could not be deleted. Nothing changed; try again.')}</p>}
    <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 8, marginTop: 18 }}>
      <button type="button" className="btn btn-secondary" autoFocus disabled={busy} onClick={onCancel}>{t('Ponechat roli', 'Keep role')}</button>
      <button type="button" className="btn btn-danger" disabled={busy} aria-busy={busy} onClick={onConfirm}>{busy ? t('Mažu…', 'Deleting…') : t('Smazat preset', 'Delete preset')}</button>
    </div>
  </div></div>
}

export function RoleEditor({ value, busy, failed, cancel, save }: { value: RolePreset; busy: boolean; failed: boolean; cancel: () => void; save: (role: RolePreset) => Promise<void> }) {
  const { t } = useLanguage(); const [role, setRole] = useState(value); const allowed = CAPABILITIES_BY_RESOURCE[role.resourceType]
  const dialogRef = useRef<HTMLDivElement>(null)
  const changeResource = (resourceType: DelegationResource) => setRole({ ...role, resourceType, capabilities: [] })
  const toggle = (right: string) => setRole({ ...role, capabilities: role.capabilities.includes(right) ? role.capabilities.filter(item => item !== right) : [...role.capabilities, right] })
  return <div ref={dialogRef} role="dialog" aria-modal="true" aria-labelledby="role-editor-title" aria-busy={busy} onKeyDown={event => { if (event.key === 'Escape' && !busy) cancel(); trapDialogFocus(event, dialogRef.current) }} style={{ position: 'fixed', inset: 0, zIndex: 1000, background: 'rgba(0,0,0,.55)', display: 'grid', placeItems: 'center', padding: 16 }}><div className="card" style={{ padding: 20, width: 'min(620px, 100%)', maxHeight: 'calc(100dvh - 32px)', overflowY: 'auto' }}>
    <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 16 }}><h2 id="role-editor-title" style={{ fontWeight: 700 }}>{t('Nastavení dispoziční role', 'Delegation role settings')}</h2><button type="button" className="btn btn-secondary" disabled={busy} onClick={cancel} aria-label={t('Zavřít', 'Close')}><X size={14} aria-hidden="true" /></button></div>
    {failed && <p role="alert" style={{ margin: '0 0 14px', padding: '10px 12px', borderRadius: 8, color: 'var(--danger-text)', background: 'var(--danger-bg)', border: '1px solid var(--danger-border)', fontSize: 12 }}>{t('Roli se nepodařilo uložit. Nic se nezměnilo; zkontrolujte údaje a zkuste to znovu.', 'The role could not be saved. Nothing changed; check the details and try again.')}</p>}
    <label style={labelStyle}>{t('Název', 'Name')}<input autoFocus className="input" maxLength={100} value={role.name} onChange={event => setRole({ ...role, name: event.target.value })} /></label>
    <label style={labelStyle}>{t('Popis', 'Description')}<textarea className="input" maxLength={500} value={role.description} onChange={event => setRole({ ...role, description: event.target.value })} /></label>
    <label style={labelStyle}>{t('Zdroj', 'Resource')}<select className="input" value={role.resourceType} onChange={event => changeResource(event.target.value as DelegationResource)}>{Object.keys(CAPABILITIES_BY_RESOURCE).map(resource => <option key={resource}>{resource}</option>)}</select></label>
    <fieldset style={{ border: 0, padding: 0, margin: '14px 0' }}><legend style={{ fontWeight: 700, fontSize: 13, marginBottom: 8 }}>{t('Práva', 'Rights')}</legend>{allowed.map(right => <label key={right} title={right} style={{ display: 'flex', gap: 8, padding: 8 }}><input type="checkbox" checked={role.capabilities.includes(right)} onChange={() => toggle(right)} /><span>{rightLabel(right, t)}</span></label>)}</fieldset>
    <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 8 }}><button type="button" className="btn btn-secondary" disabled={busy} onClick={cancel}>{t('Zrušit', 'Cancel')}</button><button type="button" className="btn btn-primary" disabled={busy || !role.name.trim() || !role.capabilities.length} aria-busy={busy} onClick={() => void save(role)}>{busy ? t('Ukládám…', 'Saving…') : t('Uložit', 'Save')}</button></div>
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
