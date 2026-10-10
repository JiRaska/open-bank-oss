// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Annuity partners (ADR-0334, pension-service API 1.2.0, #12383): the registry of insurers an
// ANNUITY payout may buy a policy from, and the purchases in flight. Registering or amending a
// partner leaves it in DRAFT; one operator requests activation and a DIFFERENT operator — one who
// neither edited the terms nor requested the activation — approves it.
//
// FOUR-EYES: the approve button is hidden from the editor and the requester. That is a courtesy,
// not the control — pension-service refuses a self-approval (403) whatever the UI shows, and the
// page renders the refusal readably.

'use client'

import { useCallback, useEffect, useMemo, useState, type FormEvent } from 'react'
import { useSession } from 'next-auth/react'
import { Handshake, RefreshCw } from 'lucide-react'
import { AuthGuard } from '@/components/auth/AuthGuard'
import { DataUnavailable, type UnavailableKind } from '@/components/feedback/DataUnavailable'
import { PageHeader } from '@/components/ui'
import { getJson, PENSION, pensionUrl, sendJson } from '@/components/pension/api'
import {
  annuityProviderListSchema, annuityProviderSchema, queueRowSchema, type AnnuityProvider,
} from '@/components/pension/contracts'
import { canApproveAnnuityProvider, canRequestAnnuityActivation, refusalText, statusLabel } from '@/components/pension/model'
import { PensionQueue } from '@/components/pension/PensionQueue'
import { principalNameFromToken } from '@/components/balance-sheet/model'
import { hasPermission } from '@/lib/auth/roles'
import { useLanguage } from '@/lib/i18n/LanguageContext'

export default function PensionAnnuityPage() {
  return (
    <AuthGuard permission="pension:view">
      <Annuity />
    </AuthGuard>
  )
}

type Notice = { tone: 'success' | 'danger'; text: string }

const ANNUITY_TYPES = ['LIFELONG', 'FIXED_TERM', 'GUARANTEE_PERIOD', 'JOINT_LIFE', 'INDEXED'] as const
const PARTNER_ID = /^[a-z0-9][a-z0-9-]{1,62}$/
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i
const DATE = /^\d{4}-\d{2}-\d{2}$/
const DECIMAL = /^\d+(\.\d{1,2})?$/

type Draft = {
  partnerId: string; legalName: string; legalEntityPartyId: string; licenceRef: string; licenceAuthority: string
  jurisdictions: string; supportedTypes: string[]; currency: string; minPremium: string; maxPremium: string
  coolingOffDays: string; premiumIban: string; adapter: string; endpointUrl: string; effectiveFrom: string
}

const EMPTY: Draft = {
  partnerId: '', legalName: '', legalEntityPartyId: '', licenceRef: '', licenceAuthority: 'CNB',
  jurisdictions: 'CZ', supportedTypes: ['LIFELONG'], currency: 'CZK', minPremium: '', maxPremium: '',
  coolingOffDays: '30', premiumIban: '', adapter: 'reference-rest', endpointUrl: '', effectiveFrom: '',
}

/** The first problem with a draft, checked before the call so the operator sees which field. */
function annuityDraftProblem(d: Draft, t: (cs: string, en: string) => string): string | null {
  if (!PARTNER_ID.test(d.partnerId)) return t('ID partnera: malá písmena, číslice a pomlčky.', 'Partner id: lower-case letters, digits and hyphens.')
  if (!d.legalName.trim()) return t('Vyplňte název pojišťovny.', 'Enter the insurer\'s legal name.')
  if (!UUID.test(d.legalEntityPartyId.trim())) return t('ID právnické osoby musí být UUID.', 'The legal-entity party id must be a UUID.')
  if (!d.licenceRef.trim()) return t('Vyplňte číslo licence.', 'Enter the licence reference.')
  if (d.supportedTypes.length === 0) return t('Vyberte alespoň jeden druh renty.', 'Choose at least one annuity type.')
  if (!DECIMAL.test(d.minPremium) || !DECIMAL.test(d.maxPremium) || Number(d.minPremium) > Number(d.maxPremium)) {
    return t('Rozsah pojistného je neplatný.', 'The premium range is invalid.')
  }
  if (!/^\d{1,3}$/.test(d.coolingOffDays)) return t('Lhůta na rozmyšlenou je počet dní.', 'Cooling-off is a number of days.')
  if (!/^[A-Z]{2}\d{2}[A-Z0-9]{11,30}$/.test(d.premiumIban.replace(/\s/g, '').toUpperCase())) return t('IBAN pro pojistné je neplatný.', 'The premium IBAN is invalid.')
  if (d.endpointUrl && !d.endpointUrl.startsWith('https://')) return t('Adresa partnera musí být HTTPS.', 'The partner endpoint must be HTTPS.')
  if (!DATE.test(d.effectiveFrom)) return t('Zadejte datum účinnosti.', 'Enter the effective date.')
  return null
}

function terms(d: Draft) {
  return {
    legalName: d.legalName.trim(),
    legalEntityPartyId: d.legalEntityPartyId.trim(),
    licenceRef: d.licenceRef.trim(),
    licenceAuthority: d.licenceAuthority.trim(),
    jurisdictions: d.jurisdictions.split(',').map(j => j.trim().toUpperCase()).filter(Boolean),
    supportedTypes: d.supportedTypes,
    currency: d.currency.trim().toUpperCase(),
    minPremium: Number(d.minPremium),
    maxPremium: Number(d.maxPremium),
    coolingOffDays: Number(d.coolingOffDays),
    premiumIban: d.premiumIban.replace(/\s/g, '').toUpperCase(),
    adapter: d.adapter.trim(),
    endpointUrl: d.endpointUrl.trim() || null,
    effectiveFrom: d.effectiveFrom,
  }
}

function Annuity() {
  const { t, language } = useLanguage()
  const { data: session } = useSession()
  const roles = useMemo(() => session?.user?.roles ?? [], [session?.user?.roles])
  const canOperate = hasPermission(roles, 'pension:operate')
  const actor = principalNameFromToken(session?.user?.accessToken)

  const [providers, setProviders] = useState<AnnuityProvider[] | null>(null)
  const [unavailable, setUnavailable] = useState<{ kind: UnavailableKind } | null>(null)
  const [notice, setNotice] = useState<Notice | null>(null)
  const [busy, setBusy] = useState(false)
  const [draft, setDraft] = useState<Draft>(EMPTY)
  const [hint, setHint] = useState<string | null>(null)

  const load = useCallback(async () => {
    const res = await getJson(pensionUrl('/operator/annuity-providers'), annuityProviderListSchema)
    if (!res.ok) { setUnavailable({ kind: res.kind }); setProviders(null); return }
    setUnavailable(null)
    setProviders(res.data)
  }, [])

  useEffect(() => { void load() }, [load])

  const act = async (partnerId: string, step: 'activation-request' | 'activation-approval' | 'disable', label: string) => {
    setBusy(true)
    const res = await sendJson('POST', pensionUrl(`/operator/annuity-providers/${encodeURIComponent(partnerId)}/${step}`), undefined, annuityProviderSchema)
    setBusy(false)
    setNotice(res.ok
      ? { tone: 'success', text: t(`${label}: partner je ve stavu ${statusLabel(res.data.status, t)}.`, `${label}: the partner is now ${statusLabel(res.data.status, t)}.`) }
      : { tone: 'danger', text: refusalText(res, label, t) })
    if (res.ok) void load()
  }

  const register = async (e: FormEvent) => {
    e.preventDefault()
    const problem = annuityDraftProblem(draft, t)
    setHint(problem)
    if (problem) return
    setBusy(true)
    const res = await sendJson('POST', pensionUrl('/operator/annuity-providers'), { partnerId: draft.partnerId, terms: terms(draft) }, annuityProviderSchema)
    setBusy(false)
    const label = t('Registrace partnera', 'Partner registration')
    setNotice(res.ok
      ? { tone: 'success', text: t('Partner je založen jako koncept; aktivaci musí schválit jiný operátor.', 'The partner is registered as a draft; another operator must approve its activation.') }
      : { tone: 'danger', text: refusalText(res, label, t) })
    if (res.ok) { setDraft(EMPTY); void load() }
  }

  const field = (key: keyof Draft, cs: string, en: string, placeholder?: string) => (
    <label style={{ display: 'flex', flexDirection: 'column', gap: 4, fontSize: 12 }}>
      {t(cs, en)}
      <input
        className="input"
        value={draft[key] as string}
        placeholder={placeholder}
        onChange={e => setDraft(d => ({ ...d, [key]: e.target.value }))}
      />
    </label>
  )

  return (
    <div>
      <PageHeader
        title={t('Anuitní partneři', 'Annuity partners')}
        subtitle={t('Pojišťovny, od kterých si účastník může koupit rentu, a rozpracované nákupy.', 'Insurers a participant may buy an annuity from, and the purchases in flight.')}
        icon={<Handshake size={20} aria-hidden="true" />}
      />

      {notice && (
        <div role="status" className="card" style={{ marginBottom: 16, color: notice.tone === 'danger' ? 'var(--danger-text)' : 'var(--success-text)' }}>
          {notice.text}
        </div>
      )}

      <section className="card" style={{ marginBottom: 16, overflowX: 'auto' }}>
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 8 }}>
          <h2 style={{ fontSize: 15, margin: 0 }}>{t('Registr partnerů', 'Partner registry')}</h2>
          <button type="button" className="btn btn-secondary btn-sm" onClick={() => void load()} aria-label={t('Obnovit', 'Refresh')}>
            <RefreshCw size={14} aria-hidden="true" />
          </button>
        </div>
        {unavailable ? (
          <DataUnavailable kind={unavailable.kind} service={PENSION} feature={t('registr anuitních partnerů', 'annuity partner registry')} lang={language} dense />
        ) : providers === null ? null : providers.length === 0 ? (
          <DataUnavailable kind="no_data" service={PENSION} feature={t('registr anuitních partnerů', 'annuity partner registry')} lang={language} dense />
        ) : (
          <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 13 }}>
            <thead>
              <tr>
                <th scope="col" style={{ textAlign: 'left' }}>{t('Partner', 'Partner')}</th>
                <th scope="col" style={{ textAlign: 'left' }}>{t('Stav', 'Status')}</th>
                <th scope="col" style={{ textAlign: 'left' }}>{t('Platná verze', 'Live version')}</th>
                <th scope="col" style={{ textAlign: 'left' }}>{t('Navrhl', 'Proposed by')}</th>
                <th scope="col" style={{ textAlign: 'left' }}>{t('Aktivaci žádal', 'Activation requested by')}</th>
                <th scope="col" style={{ textAlign: 'left' }}>{t('Schválil', 'Approved by')}</th>
                {canOperate && <th scope="col" style={{ textAlign: 'left' }}>{t('Akce', 'Actions')}</th>}
              </tr>
            </thead>
            <tbody>
              {providers.map(p => {
                const name = (p.liveTerms?.legalName ?? p.proposedTerms?.legalName) as string | undefined
                return (
                  <tr key={p.partnerId}>
                    <td>{p.partnerId}{name ? ` — ${name}` : ''}</td>
                    <td>{statusLabel(p.status, t)}</td>
                    <td>{p.liveVersion ?? '—'}</td>
                    <td>{p.proposedBy ?? '—'}</td>
                    <td>{p.activationRequestedBy ?? '—'}</td>
                    <td>{p.approvedBy ?? '—'}</td>
                    {canOperate && (
                      <td style={{ display: 'flex', gap: 6, flexWrap: 'wrap' }}>
                        {canRequestAnnuityActivation(p) && (
                          <button type="button" className="btn btn-secondary btn-sm" disabled={busy}
                            onClick={() => void act(p.partnerId, 'activation-request', t('Žádost o aktivaci', 'Activation request'))}>
                            {t('Požádat o aktivaci', 'Request activation')}
                          </button>
                        )}
                        {canApproveAnnuityProvider(p, actor) && (
                          <button type="button" className="btn btn-primary btn-sm" disabled={busy}
                            onClick={() => void act(p.partnerId, 'activation-approval', t('Schválení aktivace', 'Activation approval'))}>
                            {t('Schválit aktivaci', 'Approve activation')}
                          </button>
                        )}
                        {p.status === 'PENDING_ACTIVATION' && !canApproveAnnuityProvider(p, actor) && (
                          <span style={{ fontSize: 12, color: 'var(--text-muted)' }}>
                            {t('Schvaluje jiný operátor', 'Another operator approves')}
                          </span>
                        )}
                        {p.status === 'ACTIVE' && (
                          <button type="button" className="btn btn-secondary btn-sm" disabled={busy}
                            onClick={() => void act(p.partnerId, 'disable', t('Vypnutí partnera', 'Disabling the partner'))}>
                            {t('Vypnout', 'Disable')}
                          </button>
                        )}
                      </td>
                    )}
                  </tr>
                )
              })}
            </tbody>
          </table>
        )}
      </section>

      {canOperate && (
        <form className="card" onSubmit={register} style={{ marginBottom: 16 }}>
          <h2 style={{ fontSize: 15, marginTop: 0 }}>{t('Registrovat partnera', 'Register a partner')}</h2>
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(220px, 1fr))', gap: 8 }}>
            {field('partnerId', 'ID partnera', 'Partner id', 'acme-life')}
            {field('legalName', 'Název pojišťovny', 'Legal name')}
            {field('legalEntityPartyId', 'ID právnické osoby (party)', 'Legal-entity party id')}
            {field('licenceRef', 'Číslo licence', 'Licence reference')}
            {field('licenceAuthority', 'Orgán dohledu', 'Licensing authority')}
            {field('jurisdictions', 'Jurisdikce (čárkou)', 'Jurisdictions (comma-separated)')}
            {field('currency', 'Měna', 'Currency')}
            {field('minPremium', 'Min. pojistné', 'Min premium')}
            {field('maxPremium', 'Max. pojistné', 'Max premium')}
            {field('coolingOffDays', 'Lhůta na rozmyšlenou (dny)', 'Cooling-off (days)')}
            {field('premiumIban', 'IBAN pro pojistné', 'Premium IBAN')}
            {field('adapter', 'Adaptér', 'Adapter')}
            {field('endpointUrl', 'Adresa partnera (HTTPS)', 'Partner endpoint (HTTPS)')}
            {field('effectiveFrom', 'Účinnost od (RRRR-MM-DD)', 'Effective from (YYYY-MM-DD)')}
          </div>
          <fieldset style={{ border: 'none', padding: 0, marginTop: 8, display: 'flex', gap: 12, flexWrap: 'wrap', fontSize: 12 }}>
            <legend style={{ fontSize: 12 }}>{t('Druhy renty', 'Annuity types')}</legend>
            {ANNUITY_TYPES.map(type => (
              <label key={type} style={{ display: 'flex', gap: 4, alignItems: 'center' }}>
                <input
                  type="checkbox"
                  checked={draft.supportedTypes.includes(type)}
                  onChange={e => setDraft(d => ({
                    ...d,
                    supportedTypes: e.target.checked ? [...d.supportedTypes, type] : d.supportedTypes.filter(x => x !== type),
                  }))}
                />
                {type}
              </label>
            ))}
          </fieldset>
          {hint && <div role="alert" style={{ fontSize: 12, color: 'var(--danger-text)', marginTop: 8 }}>{hint}</div>}
          <button type="submit" className="btn btn-primary btn-sm" disabled={busy} style={{ marginTop: 12 }}>
            {t('Založit jako koncept', 'Register as draft')}
          </button>
        </form>
      )}

      <PensionQueue
        title={t('Nákupy rent', 'Annuity purchases')}
        url={pensionUrl('/operator/annuity-purchases', { limit: '100' })}
        service={PENSION}
        feature={t('nákupy rent', 'annuity purchases')}
        columns={[
          { key: 'selectedPartnerId', cs: 'Partner', en: 'Partner' },
          { key: 'premium', cs: 'Pojistné', en: 'Premium' },
          { key: 'currency', cs: 'Měna', en: 'Currency' },
          { key: 'policyRef', cs: 'Pojistka', en: 'Policy' },
          { key: 'coolingOffEndsOn', cs: 'Konec lhůty na rozmyšlenou', en: 'Cooling-off ends' },
          { key: 'failureReason', cs: 'Důvod selhání', en: 'Failure reason' },
        ]}
      />
      {canOperate && <PurchaseSync onDone={setNotice} />}
    </div>
  )
}

/** Ask the partner for the purchase's current state (application, premium, policy). */
function PurchaseSync({ onDone }: { onDone: (n: Notice) => void }) {
  const { t } = useLanguage()
  const [id, setId] = useState('')
  const [busy, setBusy] = useState(false)
  const submit = async (e: FormEvent) => {
    e.preventDefault()
    const label = t('Synchronizace nákupu', 'Purchase sync')
    if (!UUID.test(id.trim())) { onDone({ tone: 'danger', text: t('ID nákupu (výplaty) musí být UUID.', 'The purchase (payout) id must be a UUID.') }); return }
    setBusy(true)
    const res = await sendJson('POST', pensionUrl(`/operator/annuity-purchases/${encodeURIComponent(id.trim())}/sync`), undefined, queueRowSchema)
    setBusy(false)
    onDone(res.ok
      ? { tone: 'success', text: t(`Nákup je ve stavu ${String(res.data.status ?? '—')}.`, `The purchase is now ${String(res.data.status ?? '—')}.`) }
      : { tone: 'danger', text: refusalText(res, label, t) })
  }
  return (
    <form className="card" onSubmit={submit} style={{ display: 'flex', gap: 8, flexWrap: 'wrap', alignItems: 'center' }}>
      <input className="input" value={id} onChange={e => setId(e.target.value)} placeholder={t('ID výplaty (UUID)', 'Payout id (UUID)')} aria-label={t('ID výplaty', 'Payout id')} style={{ flex: '1 1 320px' }} />
      <button type="submit" className="btn btn-secondary btn-sm" disabled={busy}>{t('Synchronizovat s partnerem', 'Sync with partner')}</button>
    </form>
  )
}
