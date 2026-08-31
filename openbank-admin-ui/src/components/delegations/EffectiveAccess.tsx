// SPDX-License-Identifier: Apache-2.0
'use client'

import Link from 'next/link'
import type { ReactNode } from 'react'
import { Crown, KeyRound, Layers3 } from 'lucide-react'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { CAPABILITIES_BY_RESOURCE, capabilityLabel, type RolePreset } from '@/lib/delegations/rolePresets'
import { DelegationStatusBadge, type Grant } from '@/components/delegations/GrantView'

type SourceState = 'ok' | 'forbidden' | 'unavailable'
type Account = { id?: string; accountNumber?: string; nickname?: string | null; accountType?: string; currencyCode?: string; status?: string }
type Card = { id?: string; maskedPan?: string; cardType?: string; network?: string; status?: string; delegated?: boolean; delegationGrantId?: string | null }

export type EffectiveAccessPayload = {
  accounts: Account[]
  cards: Card[]
  grants: Grant[]
  presets: RolePreset[]
  sources: { accounts: SourceState; cards: SourceState; grants: SourceState; presets: SourceState }
}

const sameCapabilities = (left: string[] = [], right: string[] = []) =>
  left.length === right.length && [...left].sort().every((item, index) => item === [...right].sort()[index])

export function matchedRoleName(grant: Grant, presets: RolePreset[], language: 'cs' | 'en'): string {
  const preset = presets.find(item => item.resourceType === grant.resourceType && sameCapabilities(item.capabilities, grant.capabilities))
  if (preset) return preset.name
  return language === 'cs' ? 'Vlastní kombinace práv' : 'Custom rights set'
}

export function EffectiveAccess({ data }: { data: EffectiveAccessPayload }) {
  const { t, language } = useLanguage()
  const partial = Object.entries(data.sources).filter(([, state]) => state !== 'ok')

  return <section className="card" style={{ padding: 16, marginBottom: 20 }} aria-labelledby="effective-access-title">
    <div style={{ display: 'flex', gap: 10, alignItems: 'flex-start' }}>
      <Layers3 size={18} color="var(--accent)" aria-hidden="true" />
      <div><h2 id="effective-access-title" style={{ fontSize: 16, fontWeight: 750 }}>{t('Efektivní přístup klienta', 'Customer effective access')}</h2>
        <p style={{ fontSize: 12, color: 'var(--text-tertiary)', marginTop: 3 }}>{t('Souhrn vlastnictví a přijatých delegací: jakou roli klient má, nad čím a s jakými právy.', 'Ownership and received delegations: which role the customer has, over what, and with which rights.')}</p></div>
    </div>

    {partial.length > 0 && <div role="status" style={{ marginTop: 12, padding: 10, borderRadius: 8, background: 'var(--surface-3)', fontSize: 12 }}>
      {t('Výsledek je částečný. Nedostupné nebo nepovolené zdroje:', 'The result is partial. Unavailable or forbidden sources:')} {' '}
      {partial.map(([source, state]) => `${source} (${state})`).join(', ')}
    </div>}

    <AccessSection title={t('Vlastní zdroje', 'Owned resources')} empty={data.accounts.length === 0 && data.cards.length === 0}>
      {data.accounts.map(account => <AccessCard key={`account-${account.id}`} icon={<Crown size={15} />} role={t('Majitel účtu', 'Account owner')} resource={account.nickname || maskedAccount(account.accountNumber, t('Účet', 'Account')) || t('Účet', 'Account')} meta={[account.accountType, account.currencyCode, account.status].filter(Boolean).join(' · ')} href={account.id ? `/accounts/${account.id}` : undefined} capabilities={[...CAPABILITIES_BY_RESOURCE.ACCOUNT]} />)}
      {data.cards.map(card => <AccessCard key={`card-${card.id}`} icon={<Crown size={15} />} role={card.delegated ? t('Držitel dodatkové karty', 'Additional cardholder') : t('Majitel karty', 'Card owner')} resource={card.maskedPan || t('Karta', 'Card')} meta={[card.network, card.cardType, card.status].filter(Boolean).join(' · ')} href={card.id ? `/cards/${card.id}` : undefined} capabilities={card.delegated ? delegatedCardCapabilities(card, data) : [...CAPABILITIES_BY_RESOURCE.CARD]} />)}
    </AccessSection>

    <AccessSection title={t('Delegovaná oprávnění', 'Delegated rights')} empty={!data.grants.some(grant => grant.status === 'ACTIVE')}>
      {data.grants.filter(grant => grant.status === 'ACTIVE').map(grant => <AccessCard key={`grant-${grant.id}`} icon={<KeyRound size={15} />} role={matchedRoleName(grant, data.presets, language)} resource={`${resourceLabel(grant.resourceType, language)} · ${shortId(grant.resourceId)}`} meta={<DelegationStatusBadge status={grant.status} />} href={`/delegations/${grant.id}`} capabilities={grant.capabilities} />)}
    </AccessSection>
  </section>
}

function AccessSection({ title, empty, children }: { title: string; empty: boolean; children: ReactNode }) {
  const { t } = useLanguage()
  return <div style={{ marginTop: 16 }}><h3 style={{ fontSize: 12, fontWeight: 800, letterSpacing: '.06em', textTransform: 'uppercase' }}>{title}</h3>
    {empty ? <p style={{ fontSize: 13, color: 'var(--text-tertiary)', marginTop: 8 }}>{t('Žádné položky z dostupných zdrojů.', 'No items from the available sources.')}</p>
      : <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(min(100%, 360px), 1fr))', gap: 12, marginTop: 8 }}>{children}</div>}
  </div>
}

function AccessCard({ icon, role, resource, meta, href, capabilities }: { icon: ReactNode; role: string; resource: string; meta: ReactNode; href?: string; capabilities: string[] }) {
  const { t, language } = useLanguage()
  const content = <><div style={{ display: 'flex', gap: 8, alignItems: 'center', color: 'var(--accent)' }}>{icon}<strong style={{ color: 'var(--text-primary)' }}>{role}</strong></div>
    <div style={{ fontSize: 13, fontWeight: 650, marginTop: 9 }}>{resource}</div><div style={{ fontSize: 11, color: 'var(--text-tertiary)', marginTop: 3 }}>{meta}</div>
    <div aria-label={t('Práva', 'Rights')} style={{ display: 'flex', flexWrap: 'wrap', gap: 5, marginTop: 12 }}>{capabilities.map(capability => <span key={capability} title={capability} style={{ borderRadius: 999, padding: '4px 8px', fontSize: 10, background: 'var(--surface-3)', border: '1px solid var(--border)' }}>{capabilityLabel(capability, language)}</span>)}</div></>
  const style = { display: 'block', padding: 14, border: '1px solid var(--border)', borderRadius: 11, background: 'var(--surface-1)', color: 'inherit', textDecoration: 'none' }
  return href ? <Link href={href} style={style}>{content}</Link> : <div style={style}>{content}</div>
}

const presetCapabilities = (presets: RolePreset[], name: string) => presets.find(preset => preset.name === name)?.capabilities ?? []
const delegatedCardCapabilities = (card: Card, data: EffectiveAccessPayload) =>
  data.grants.find(grant => grant.id === card.delegationGrantId)?.capabilities ?? presetCapabilities(data.presets, 'Držitel dodatkové karty')
const maskedAccount = (number: string | undefined, label: string) => number ? `${label} •••• ${number.replace(/\s/g, '').slice(-4)}` : undefined
const shortId = (id?: string) => id ? `${id.slice(0, 8)}…` : '—'
const resourceLabel = (resource: string, language: 'cs' | 'en') => ({ ACCOUNT: language === 'cs' ? 'Účet' : 'Account', CARD: language === 'cs' ? 'Karta' : 'Card', SAVINGS_GOAL: language === 'cs' ? 'Spoření' : 'Savings' }[resource] ?? resource)
