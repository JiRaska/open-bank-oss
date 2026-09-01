// SPDX-License-Identifier: Apache-2.0
'use client'

import Link from 'next/link'
import type { ReactNode } from 'react'
import { AlertTriangle, Crown, KeyRound, Layers3 } from 'lucide-react'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { CAPABILITIES_BY_RESOURCE, capabilityIntent, capabilityLabel, type RolePreset } from '@/lib/delegations/rolePresets'
import { DelegationStatusBadge, formatCeiling, type Grant } from '@/components/delegations/GrantView'

type SourceState = 'ok' | 'forbidden' | 'unavailable'
type Account = { id?: string; accountNumber?: string; nickname?: string | null; accountType?: string; currencyCode?: string; status?: string }
type Card = { id?: string; maskedPan?: string; cardType?: string; network?: string; status?: string; delegated?: boolean; delegationGrantId?: string | null }
type ResourceDetail = { key: string; resourceType: string; resourceId: string; state: SourceState; detail?: Account | Card }

export type EffectiveAccessPayload = {
  accounts: Account[]
  cards: Card[]
  grants: Grant[]
  presets: RolePreset[]
  resourceDetails: ResourceDetail[]
  resourceDetailsTruncated?: boolean
  sources: { accounts: SourceState; cards: SourceState; grants: SourceState; presets: SourceState }
}

export function isEffectiveAccessPayload(value: unknown): value is EffectiveAccessPayload {
  if (!value || typeof value !== 'object') return false
  const candidate = value as Partial<EffectiveAccessPayload>
  return Array.isArray(candidate.accounts) && Array.isArray(candidate.cards) &&
    Array.isArray(candidate.grants) && Array.isArray(candidate.presets) && Array.isArray(candidate.resourceDetails) &&
    !!candidate.sources && typeof candidate.sources === 'object'
}

const sameCapabilities = (left: string[] = [], right: string[] = []) =>
  left.length === right.length && [...left].sort().every((item, index) => item === [...right].sort()[index])

export function matchedRoleName(grant: Grant, presets: RolePreset[], language: 'cs' | 'en'): string {
  const preset = presets.find(item => item.resourceType === grant.resourceType && sameCapabilities(item.capabilities, grant.capabilities))
  if (preset) return preset.name
  return language === 'cs' ? 'Vlastní kombinace práv' : 'Custom rights set'
}

export function grantResourcePresentation(grant: Grant, details: ResourceDetail[], language: 'cs' | 'en') {
  const resolved = details.find(item => item.key === `${grant.resourceType}:${grant.resourceId}`)
  const detail = resolved?.detail
  if (grant.resourceType === 'CARD' && detail) {
    const card = detail as Card
    return { label: card.maskedPan || resourceLabel(grant.resourceType, language), meta: [card.network, card.cardType, card.status].filter(Boolean).join(' · ') }
  }
  if (detail) {
    const account = detail as Account & { goalName?: string | null }
    const kind = grant.resourceType === 'SAVINGS_GOAL' ? (account.goalName || resourceLabel(grant.resourceType, language)) : resourceLabel(grant.resourceType, language)
    return { label: account.nickname || maskedAccount(account.accountNumber, kind) || kind, meta: [account.accountType, account.currencyCode, account.status].filter(Boolean).join(' · ') }
  }
  return { label: `${resourceLabel(grant.resourceType, language)} · ${shortId(grant.resourceId)}`, meta: resolved?.state === 'forbidden' ? (language === 'cs' ? 'Detail zdroje není pro tuto roli povolen' : 'Resource detail is not permitted for this role') : '' }
}

export function grantConditions(grant: Grant, language: 'cs' | 'en') {
  const locale = language === 'cs' ? 'cs-CZ' : 'en-GB'
  const t = (cs: string, en: string) => language === 'cs' ? cs : en
  const conditions = [{
    label: t('Platnost', 'Validity'),
    value: grant.validTo ? `${t('do', 'until')} ${new Date(grant.validTo).toLocaleDateString(locale)}` : t('bez časového omezení', 'no end date'),
  }]
  if (!grant.capabilities.some(capability => capabilityIntent(capability) === 'act')) return conditions
  conditions.push({
    label: t('Schválení', 'Approval'),
    value: grant.approvalPolicy === 'N_OF_M'
      ? `${grant.requiredApprovals ?? '—'} ${t('schválení', 'approvals')}`
      : t('samostatně', 'independent'),
  })
  const limits = [
    [t('Jedna operace', 'Per operation'), grant.perTransactionLimit],
    [t('Denně', 'Daily'), grant.dailyLimit],
    [t('Měsíčně', 'Monthly'), grant.monthlyLimit],
  ] as const
  limits.forEach(([label, limit]) => conditions.push({ label, value: limit ? formatCeiling(limit, locale) : t('bez limitu', 'uncapped') }))
  return conditions
}

export type DelegationAttentionReason = {
  kind: 'expired' | 'expiring' | 'no-end-date' | 'uncapped'
  label: string
  detail: string
}

const REVIEW_WINDOW_DAYS = 30
const DAY_MS = 24 * 60 * 60 * 1000

/**
 * Facts that deserve an operator review. This deliberately does not assign a subjective risk
 * score: every item states the exact condition that caused it to appear.
 */
export function delegationAttentionReasons(grant: Grant, now: Date, language: 'cs' | 'en'): DelegationAttentionReason[] {
  if (grant.status !== 'ACTIVE') return []
  const t = (cs: string, en: string) => language === 'cs' ? cs : en
  const reasons: DelegationAttentionReason[] = []
  const hasActionRights = grant.capabilities.some(capability => capabilityIntent(capability) === 'act')

  if (grant.validTo) {
    const validTo = new Date(grant.validTo)
    const remainingMs = validTo.getTime() - now.getTime()
    if (!Number.isNaN(validTo.getTime()) && remainingMs <= 0) {
      reasons.push({
        kind: 'expired',
        label: t('Platnost už skončila', 'Validity has ended'),
        detail: t('Delegace je stále označená jako aktivní. Ověřte její stav.', 'The delegation is still marked active. Verify its status.'),
      })
    } else if (!Number.isNaN(validTo.getTime()) && remainingMs <= REVIEW_WINDOW_DAYS * DAY_MS) {
      const days = Math.max(1, Math.ceil(remainingMs / DAY_MS))
      reasons.push({
        kind: 'expiring',
        label: t(`Končí do ${days} dnů`, `Expires within ${days} days`),
        detail: t('Ověřte, zda má přístup pokračovat.', 'Confirm whether access should continue.'),
      })
    }
  } else if (hasActionRights) {
    reasons.push({
      kind: 'no-end-date',
      label: t('Akční práva bez konce platnosti', 'Action rights without an end date'),
      detail: t('Přístup zůstane aktivní, dokud jej někdo nezmění nebo neodvolá.', 'Access remains active until someone changes or revokes it.'),
    })
  }

  if (hasActionRights) {
    const uncapped = [
      [t('jedna operace', 'per operation'), grant.perTransactionLimit],
      [t('den', 'daily'), grant.dailyLimit],
      [t('měsíc', 'monthly'), grant.monthlyLimit],
    ].filter(([, limit]) => !limit).map(([label]) => label)
    if (uncapped.length > 0) reasons.push({
      kind: 'uncapped',
      label: t('Akční práva bez finančního stropu', 'Action rights without a financial ceiling'),
      detail: `${t('Bez limitu', 'Uncapped')}: ${uncapped.join(', ')}.`,
    })
  }
  return reasons
}

export function EffectiveAccess({ data }: { data: EffectiveAccessPayload }) {
  const { t, language } = useLanguage()
  const partial = Object.entries(data.sources).filter(([, state]) => state !== 'ok')
  const attention = data.grants
    .map(grant => ({ grant, reasons: delegationAttentionReasons(grant, new Date(), language) }))
    .filter(item => item.reasons.length > 0)

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
    {data.resourceDetailsTruncated && <div role="status" style={{ marginTop: 12, fontSize: 12, color: 'var(--warning)' }}>{t('Zobrazen je detail prvních 50 delegovaných zdrojů.', 'Showing details for the first 50 delegated resources.')}</div>}

    {attention.length > 0 && <aside aria-labelledby="delegation-attention-title" style={{ marginTop: 14, padding: 12, borderRadius: 10, border: '1px solid var(--warning-border)', background: 'var(--warning-bg)' }}>
      <div style={{ display: 'flex', gap: 8, alignItems: 'flex-start' }}>
        <AlertTriangle size={17} color="var(--warning-text)" aria-hidden="true" style={{ flexShrink: 0, marginTop: 1 }} />
        <div><h3 id="delegation-attention-title" style={{ fontSize: 13, fontWeight: 800, color: 'var(--warning-text)' }}>{t('Vyžaduje pozornost', 'Needs attention')}</h3>
          <p style={{ fontSize: 11, color: 'var(--text-secondary)', marginTop: 2 }}>{t('Transparentní podmínky k ověření — nejde o automatické hodnocení rizika.', 'Transparent conditions to verify — this is not an automated risk rating.')}</p></div>
      </div>
      <div style={{ display: 'grid', gap: 8, marginTop: 10 }}>
        {attention.map(({ grant, reasons }) => {
          const resource = grantResourcePresentation(grant, data.resourceDetails, language)
          return <Link key={`attention-${grant.id}`} href={`/delegations/${grant.id}`} style={{ padding: 10, borderRadius: 8, border: '1px solid var(--warning-border)', background: 'var(--surface-1)', color: 'inherit', textDecoration: 'none' }}>
            <strong style={{ display: 'block', fontSize: 12 }}>{matchedRoleName(grant, data.presets, language)} · {resource.label}</strong>
            <div style={{ display: 'grid', gap: 5, marginTop: 7 }}>{reasons.map(reason => <div key={reason.kind} style={{ fontSize: 11 }}><strong style={{ color: 'var(--warning-text)' }}>{reason.label}</strong><span style={{ color: 'var(--text-secondary)' }}> — {reason.detail}</span></div>)}</div>
          </Link>
        })}
      </div>
    </aside>}

    <AccessSection title={t('Vlastní zdroje', 'Owned resources')} empty={data.accounts.length === 0 && data.cards.length === 0}>
      {data.accounts.map(account => <AccessCard key={`account-${account.id}`} icon={<Crown size={15} />} role={t('Majitel účtu', 'Account owner')} resource={account.nickname || maskedAccount(account.accountNumber, t('Účet', 'Account')) || t('Účet', 'Account')} meta={[account.accountType, account.currencyCode, account.status].filter(Boolean).join(' · ')} href={account.id ? `/accounts/${account.id}` : undefined} capabilities={[...CAPABILITIES_BY_RESOURCE.ACCOUNT]} />)}
      {data.cards.map(card => <AccessCard key={`card-${card.id}`} icon={<Crown size={15} />} role={card.delegated ? t('Držitel dodatkové karty', 'Additional cardholder') : t('Majitel karty', 'Card owner')} resource={card.maskedPan || t('Karta', 'Card')} meta={[card.network, card.cardType, card.status].filter(Boolean).join(' · ')} href={card.id ? `/cards/${card.id}` : undefined} capabilities={card.delegated ? delegatedCardCapabilities(card, data) : [...CAPABILITIES_BY_RESOURCE.CARD]} />)}
    </AccessSection>

    <AccessSection title={t('Delegovaná oprávnění', 'Delegated rights')} empty={!data.grants.some(grant => grant.status === 'ACTIVE')}>
      {data.grants.filter(grant => grant.status === 'ACTIVE').map(grant => {
        const resource = grantResourcePresentation(grant, data.resourceDetails, language)
        const grantor = grant.grantorName?.trim() || shortId(grant.grantorPartyId)
        return <AccessCard key={`grant-${grant.id}`} icon={<KeyRound size={15} />} role={matchedRoleName(grant, data.presets, language)} resource={resource.label} meta={<div style={{ display: 'flex', alignItems: 'center', flexWrap: 'wrap', gap: 7 }}><DelegationStatusBadge status={grant.status} /><span>{t('Udělil', 'Granted by')}: {grantor}</span>{resource.meta && <span>· {resource.meta}</span>}</div>} href={`/delegations/${grant.id}`} capabilities={grant.capabilities} conditions={grantConditions(grant, language)} />
      })}
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

function AccessCard({ icon, role, resource, meta, href, capabilities, conditions = [] }: { icon: ReactNode; role: string; resource: string; meta: ReactNode; href?: string; capabilities: string[]; conditions?: { label: string; value: string }[] }) {
  const { t, language } = useLanguage()
  const content = <><div style={{ display: 'flex', gap: 8, alignItems: 'center', color: 'var(--accent)' }}>{icon}<strong style={{ color: 'var(--text-primary)' }}>{role}</strong></div>
    <div style={{ fontSize: 13, fontWeight: 650, marginTop: 9 }}>{resource}</div><div style={{ fontSize: 11, color: 'var(--text-tertiary)', marginTop: 3 }}>{meta}</div>
    <div aria-label={t('Práva', 'Rights')} style={{ display: 'flex', flexWrap: 'wrap', gap: 5, marginTop: 12 }}>{capabilities.map(capability => <span key={capability} title={capability} style={{ borderRadius: 999, padding: '4px 8px', fontSize: 10, background: 'var(--surface-3)', border: '1px solid var(--border)' }}>{capabilityLabel(capability, language)}</span>)}</div>
    {conditions.length > 0 && <div aria-label={t('Podmínky oprávnění', 'Access conditions')} style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(120px, 1fr))', gap: 6, marginTop: 12, paddingTop: 10, borderTop: '1px solid var(--border)' }}>{conditions.map(condition => <div key={condition.label} style={{ fontSize: 10 }}><span style={{ color: 'var(--text-tertiary)' }}>{condition.label}</span><strong style={{ display: 'block', marginTop: 2, color: 'var(--text-primary)' }}>{condition.value}</strong></div>)}</div>}</>
  const style = { display: 'block', padding: 14, border: '1px solid var(--border)', borderRadius: 11, background: 'var(--surface-1)', color: 'inherit', textDecoration: 'none' }
  return href ? <Link href={href} style={style}>{content}</Link> : <div style={style}>{content}</div>
}

const presetCapabilities = (presets: RolePreset[], name: string) => presets.find(preset => preset.name === name)?.capabilities ?? []
const delegatedCardCapabilities = (card: Card, data: EffectiveAccessPayload) =>
  data.grants.find(grant => grant.id === card.delegationGrantId)?.capabilities ?? presetCapabilities(data.presets, 'Držitel dodatkové karty')
const maskedAccount = (number: string | undefined, label: string) => number ? `${label} •••• ${number.replace(/\s/g, '').slice(-4)}` : undefined
const shortId = (id?: string) => id ? `${id.slice(0, 8)}…` : '—'
const resourceLabel = (resource: string, language: 'cs' | 'en') => ({ ACCOUNT: language === 'cs' ? 'Účet' : 'Account', CARD: language === 'cs' ? 'Karta' : 'Card', SAVINGS_GOAL: language === 'cs' ? 'Spoření' : 'Savings' }[resource] ?? resource)
