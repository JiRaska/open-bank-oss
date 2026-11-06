// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
'use client'

import { useState } from 'react'
import { PERMISSIONS, ROLES, type Permission } from '@/lib/auth/roles'
import { PERMISSION_DESCRIPTIONS, ROLE_DESCRIPTIONS, matchesPermissionSearch } from '@/lib/auth/access-descriptions'
import { useLanguage } from '@/lib/i18n/LanguageContext'

/** UI policy projection only. Never use this component as an authorization decision. */
export function AccessPreview() {
  const { t } = useLanguage()
  const [query, setQuery] = useState('')
  const [roleFilter, setRoleFilter] = useState('')
  const roles = Object.values(ROLES)
  const columns = roles.filter(role => !roleFilter || role === roleFilter)
  const entries = (Object.entries(PERMISSIONS) as [Permission, readonly string[]][]).filter(([permission]) => matchesPermissionSearch(permission, query))
  return <section className="card" style={{ marginTop: 24, padding: 20, minWidth: 0 }} aria-labelledby="access-preview-title">
    <h2 id="access-preview-title" style={{ marginTop: 0 }}>{t('Role a oprávnění', 'Roles and permissions')} · Preview</h2>
    <p>{t('Pouze pro čtení · Neúplný katalog pravidel admin UI, nikoli úplný seznam rolí a efektivních práv v bance.', 'Read only · Incomplete catalog of admin UI rules, not a complete inventory of bank roles and effective access.')}</p>
    <p style={{ color: 'var(--text-secondary)' }}>{t('✓ = role je uvedena v pravidle UI. — = v tomto pravidle uvedena není; neprokazuje zákaz na backendu. Keycloak spravuje role a jejich přiřazení. Backend, OPA, kontext a pravidla čtyř očí rozhodují o skutečném přístupu. Přehled neověřuje živou konfiguraci Keycloaku ani oprávnění konkrétních lidí.', '✓ = role is listed in the UI rule. — = not listed in this rule; this does not establish a backend denial. Keycloak manages roles and assignments. Backend, OPA, context and four-eyes rules determine actual access. This preview does not verify live Keycloak configuration or individual users’ access.')}</p>
    <div style={{ display: 'flex', flexWrap: 'wrap', gap: 16, marginBottom: 16 }}>
      <label className="field">{t('Hledat oprávnění', 'Find permission')}<input className="input" value={query} onChange={event => setQuery(event.target.value)} placeholder={t('Např. schválení platby', 'E.g. approve payment')} /></label>
      <label className="field">{t('Role', 'Role')}<select className="input" value={roleFilter} onChange={event => setRoleFilter(event.target.value)}><option value="">{t('Všechny role UI', 'All UI roles')}</option>{roles.map(role => <option key={role} value={role}>{t(ROLE_DESCRIPTIONS[role].name.cs, ROLE_DESCRIPTIONS[role].name.en)} · {role}</option>)}</select></label>
    </div>
    <p aria-live="polite">{entries.length} {t('oprávnění', 'permissions')} · {columns.length} {t('rolí', 'roles')}</p>
    <div role="region" aria-label={t('Matice přístupových pravidel UI', 'UI access rule matrix')} tabIndex={0} style={{ overflow: 'auto', maxHeight: 560 }}>
      <table style={{ borderCollapse: 'separate', borderSpacing: 0, width: '100%', fontSize: 12 }}>
        <caption style={{ textAlign: 'left', paddingBottom: 12 }}>{t('Zdroj: katalog admin UI (src/lib/auth/roles.ts) ve verzi této aplikace. Role bez grantů UI jsou také zahrnuty.', 'Source: admin UI catalog (src/lib/auth/roles.ts) bundled with this application. Roles without UI grants are included.')}</caption>
        <thead><tr><th scope="col" style={{ position: 'sticky', top: 0, left: 0, background: 'var(--surface-1)', padding: 10, zIndex: 3 }}>{t('Oprávnění UI', 'UI permission')}</th>{columns.map(role => <th scope="col" key={role} style={{ position: 'sticky', top: 0, background: 'var(--surface-1)', padding: 10, minWidth: 220, maxWidth: 260, verticalAlign: 'top', zIndex: 2 }}><div>{t(ROLE_DESCRIPTIONS[role].name.cs, ROLE_DESCRIPTIONS[role].name.en)}</div><p style={{ fontWeight: 400, whiteSpace: 'normal', margin: '6px 0' }}>{t(ROLE_DESCRIPTIONS[role].description.cs, ROLE_DESCRIPTIONS[role].description.en)}</p><code style={{ fontWeight: 400, overflowWrap: 'anywhere' }}>{role}</code></th>)}</tr></thead>
        <tbody>{entries.map(([permission, grants]) => <tr key={permission}><th scope="row" style={{ position: 'sticky', left: 0, background: 'var(--surface-1)', padding: 10, textAlign: 'left', minWidth: 280, maxWidth: 340, borderBottom: '1px solid var(--border)' }}><div>{t(PERMISSION_DESCRIPTIONS[permission].name.cs, PERMISSION_DESCRIPTIONS[permission].name.en)}</div><p style={{ fontWeight: 400, margin: '6px 0' }}>{t(PERMISSION_DESCRIPTIONS[permission].description.cs, PERMISSION_DESCRIPTIONS[permission].description.en)}</p><code style={{ fontWeight: 400, overflowWrap: 'anywhere' }}>{permission}</code></th>{columns.map(role => <td key={role} style={{ textAlign: 'center', padding: 10, borderBottom: '1px solid var(--border)' }}>{(grants as readonly string[]).includes(role) ? <span aria-label={t('Uvedeno v pravidle UI', 'Listed in UI rule')}>✓</span> : <span aria-label={t('Neuvedeno v pravidle UI', 'Not listed in UI rule')}>—</span>}</td>)}</tr>)}</tbody>
      </table>
      {entries.length === 0 && <p>{t('Žádné odpovídající oprávnění.', 'No matching permissions.')}</p>}
    </div>
  </section>
}
