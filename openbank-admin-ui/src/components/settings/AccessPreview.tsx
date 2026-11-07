// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
'use client'

import { useState } from 'react'
import { PERMISSIONS, ROLES, type Permission, type Role } from '@/lib/auth/roles'
import { PERMISSION_DESCRIPTIONS, ROLE_DESCRIPTIONS, matchesPermissionSearch } from '@/lib/auth/access-descriptions'
import styles from './AccessPreview.module.css'
import { useLanguage } from '@/lib/i18n/LanguageContext'

/** UI policy projection only. Never use this component as an authorization decision. */
export function AccessPreview() {
  const { t } = useLanguage()
  const [query, setQuery] = useState('')
  const [roleFilter, setRoleFilter] = useState('')
  const roles = Object.values(ROLES)
  const [selectedRoles, setSelectedRoles] = useState<Role[]>(roles)
  const [showDescriptions, setShowDescriptions] = useState(false)
  const [onlyGranted, setOnlyGranted] = useState(false)
  const columns = roles.filter(role => selectedRoles.includes(role))
  const entries = (Object.entries(PERMISSIONS) as [Permission, readonly string[]][]).filter(([permission, grants]) => matchesPermissionSearch(permission, query) && (!onlyGranted || columns.some(role => grants.includes(role))))
  return <section className={`card ${styles.panel}`} aria-labelledby="access-preview-title">
    <h2 id="access-preview-title" style={{ marginTop: 0 }}>{t('Role a oprávnění', 'Roles and permissions')} · Preview</h2>
    <p>{t('Pouze pro čtení · Neúplný katalog pravidel admin UI, nikoli úplný seznam rolí a efektivních práv v bance.', 'Read only · Incomplete catalog of admin UI rules, not a complete inventory of bank roles and effective access.')}</p>
    <p style={{ color: 'var(--text-secondary)' }}>{t('✓ = role je uvedena v pravidle UI. — = v tomto pravidle uvedena není; neprokazuje zákaz na backendu. Keycloak spravuje role a jejich přiřazení. Backend, OPA, kontext a pravidla čtyř očí rozhodují o skutečném přístupu. Přehled neověřuje živou konfiguraci Keycloaku ani oprávnění konkrétních lidí.', '✓ = role is listed in the UI rule. — = not listed in this rule; this does not establish a backend denial. Keycloak manages roles and assignments. Backend, OPA, context and four-eyes rules determine actual access. This preview does not verify live Keycloak configuration or individual users’ access.')}</p>
    <div className={styles.toolbar}>
      <label className="field">{t('Hledat oprávnění', 'Find permission')}<input className="input" value={query} onChange={event => setQuery(event.target.value)} placeholder={t('Např. schválení platby', 'E.g. approve payment')} /></label>
      <label className="field">{t('Role', 'Role')}<select className="input" value={roleFilter} onChange={event => { setRoleFilter(event.target.value); setSelectedRoles(event.target.value ? [event.target.value as Role] : roles) }}><option value="">{t('Všechny role UI', 'All UI roles')}</option>{roleFilter === 'custom' && <option value="custom" disabled>{t('Vlastní výběr', 'Custom selection')}</option>}{roles.map(role => <option key={role} value={role}>{t(ROLE_DESCRIPTIONS[role].name.cs, ROLE_DESCRIPTIONS[role].name.en)} · {role}</option>)}</select></label>
    </div>
    <details className={styles.picker}>
      <summary>{t('Vybrat role k porovnání', 'Choose roles to compare')} ({columns.length}/{roles.length})</summary>
      <p>{t('Výběr mění pouze zobrazení matice, nikoli přidělená práva.', 'Selection changes only the matrix display, not assigned access.')}</p>
      <div className={styles.roleActions}>
        <button type="button" className="btn btn-secondary" onClick={() => { setSelectedRoles(roles); setRoleFilter('') }}>{t('Vybrat všechny role', 'Select all roles')}</button>
        <button type="button" className="btn btn-secondary" onClick={() => { setSelectedRoles([]); setRoleFilter('custom') }}>{t('Zrušit výběr rolí', 'Clear role selection')}</button>
      </div>
      <div className={styles.roles}>{roles.map(role => <label key={role}><input type="checkbox" checked={selectedRoles.includes(role)} onChange={event => { setRoleFilter('custom'); setSelectedRoles(current => event.target.checked ? [...current, role] : current.filter(item => item !== role)) }} /><span>{t(ROLE_DESCRIPTIONS[role].name.cs, ROLE_DESCRIPTIONS[role].name.en)}<br /><code>{role}</code></span></label>)}</div>
    </details>
    <div className={styles.options}>
      <label><input type="checkbox" checked={showDescriptions} onChange={event => setShowDescriptions(event.target.checked)} />{t('Zobrazit popisy', 'Show descriptions')}</label>
      <label><input type="checkbox" checked={onlyGranted} onChange={event => setOnlyGranted(event.target.checked)} />{t('Jen oprávnění uvedená pro vybrané role', 'Only grants listed for selected roles')}</label>
    </div>
    <p aria-live="polite">{entries.length} {t('oprávnění', 'permissions')} · {columns.length} {t('rolí', 'roles')}</p>
    <p className={styles.source}>{t('Zdroj: katalog admin UI ve verzi této aplikace. Role bez grantů jsou zahrnuty.', 'Source: the admin UI catalog bundled with this application. Roles without grants are included.')}</p>
    <div role="region" aria-label={t('Matice přístupových pravidel UI', 'UI access rule matrix')} tabIndex={0} className={styles.scroll}>
      <table className={styles.table}>
        <caption className="sr-only">{t('Zdroj: katalog admin UI (src/lib/auth/roles.ts) ve verzi této aplikace. Role bez grantů UI jsou také zahrnuty.', 'Source: admin UI catalog (src/lib/auth/roles.ts) bundled with this application. Roles without UI grants are included.')}</caption>
        <thead><tr><th scope="col">{t('Oprávnění UI', 'UI permission')}</th>{columns.map(role => <th scope="col" key={role}><div>{t(ROLE_DESCRIPTIONS[role].name.cs, ROLE_DESCRIPTIONS[role].name.en)}</div><p className={styles.description} hidden={!showDescriptions}>{t(ROLE_DESCRIPTIONS[role].description.cs, ROLE_DESCRIPTIONS[role].description.en)}</p><code>{role}</code></th>)}</tr></thead>
        <tbody>{entries.map(([permission, grants]) => <tr key={permission}><th scope="row"><div>{t(PERMISSION_DESCRIPTIONS[permission].name.cs, PERMISSION_DESCRIPTIONS[permission].name.en)}</div><p className={styles.description} hidden={!showDescriptions}>{t(PERMISSION_DESCRIPTIONS[permission].description.cs, PERMISSION_DESCRIPTIONS[permission].description.en)}</p><code>{permission}</code></th>{columns.map(role => <td key={role}>{(grants as readonly string[]).includes(role) ? <span className={styles.grant} aria-label={t('Uvedeno v pravidle UI', 'Listed in UI rule')}>✓</span> : <span aria-label={t('Neuvedeno v pravidle UI', 'Not listed in UI rule')}>—</span>}</td>)}</tr>)}</tbody>
      </table>
      {columns.length === 0 && <p className={styles.empty}>{t('Vyberte alespoň jednu roli k porovnání.', 'Select at least one role to compare.')}</p>}
      {entries.length === 0 && columns.length > 0 && <p className={styles.empty}>{t('Žádné odpovídající oprávnění.', 'No matching permissions.')}</p>}
    </div>
  </section>
}
