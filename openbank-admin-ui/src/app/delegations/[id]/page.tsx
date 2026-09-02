// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// ADR-0230 + ADR-0232: single-grant detail — capabilities, ceilings, status timeline, and a
// side-effect-free resource access eligibility check.
//
// The bank-side actions (suspend / reinstate / revoke) are stated as UNAVAILABLE in place rather
// than rendered as disabled buttons. A greyed-out button says "you lack the right"; the true
// reason is that the platform has nowhere to put the proposal — rules.yaml's own four-eyes
// assessment for this service records that delegation.suspend "lands via the fraud pipeline, not
// an operator console, so there is no maker/checker pair to gate yet", and ADR-0230 forbids the
// direct write that would be the only alternative. Saying that plainly is the honest UI.

'use client'

import { useEffect, useState } from 'react'
import { useParams } from 'next/navigation'
import Link from 'next/link'
import { ArrowLeft, ShieldQuestion, Lock } from 'lucide-react'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { classifyBffFailure } from '@/lib/services/bff'
import { DataUnavailable, type UnavailableKind } from '@/components/feedback/DataUnavailable'
import { PageHeader } from '@/components/ui/PageHeader'
import { EntityChip } from '@/components/entities/EntityChip'
import { DelegationAuditTimeline } from '@/components/delegations/DelegationAuditTimeline'
import { CoverageProbe } from '@/components/delegations/CoverageProbe'
import { LegacyCapabilityEvidence } from '@/components/delegations/LegacyCapabilityEvidence'
import { isAssignablePresetCapability } from '@/lib/delegations/rolePresets'
import {
  DelegationStatusBadge,
  capabilityLabels,
  counterpartyLabel,
  formatCeiling,
  type Grant,
} from '@/components/delegations/GrantView'

function formatDelegationTimestamp(
  value: string | null | undefined,
  locale: string,
): string {
  if (!value) return '—'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return date.toLocaleString(locale, { dateStyle: 'medium', timeStyle: 'short' })
}

export default function DelegationDetailPage() {
  const { t, language } = useLanguage()
  const numberLocale = language === 'cs' ? 'cs-CZ' : 'en-GB'
  const dateLocale = language === 'cs' ? 'cs-CZ' : 'en-GB'
  const params = useParams<{ id: string }>()
  const id = params?.id

  const [grant, setGrant] = useState<Grant | null>(null)
  const [unavail, setUnavail] = useState<UnavailableKind | null>(null)

  useEffect(() => {
    if (!id) return
    const controller = new AbortController()
    void (async () => {
      // Cross the effect boundary before updating React state and abort the old request when the
      // route changes. A slow response for grant A must never overwrite already-selected grant B.
      await Promise.resolve()
      if (controller.signal.aborted) return
      setUnavail(null)
      setGrant(null)
      try {
        const res = await fetch(`/api/delegations/${id}`, {
          cache: 'no-store',
          signal: AbortSignal.any([controller.signal, AbortSignal.timeout(8000)]),
        })
        if (controller.signal.aborted) return
        if (!res.ok) {
          const failure = await classifyBffFailure(res)
          if (!controller.signal.aborted) setUnavail(failure)
          return
        }
        const nextGrant = (await res.json()) as Grant
        if (!controller.signal.aborted) setGrant(nextGrant)
      } catch {
        if (!controller.signal.aborted) setUnavail('unreachable')
      }
    })()
    return () => controller.abort()
  }, [id])

  return (
    <div>
      <PageHeader
        icon={<ShieldQuestion size={18} aria-hidden="true" />}
        breadcrumb={<div className="breadcrumb"><Link href="/delegations">{t('Delegace', 'Delegations')}</Link><span className="breadcrumb-sep">/</span><span className="breadcrumb-current">{t('Detail', 'Detail')}</span></div>}
        title={t('Detail delegace', 'Delegation detail')}
        subtitle={t('Udělená práva, stropy a časová osa stavu (ADR-0232).', 'Granted rights, ceilings and status timeline (ADR-0232).')}
        actions={<Link href="/delegations" className="btn btn-secondary"><ArrowLeft size={14} aria-hidden="true" />{t('Zpět na delegace', 'Back to delegations')}</Link>}
      />

      {unavail && (
        <DataUnavailable
          kind={unavail}
          service="delegation-service"
          feature={t('detail delegace', 'delegation detail')}
          lang={language}
        />
      )}

      {!unavail && grant && (
        <>
          <div className="card" style={{ padding: '16px', marginTop: '16px' }}>
            <dl style={{ display: 'grid', gridTemplateColumns: 'minmax(160px, 240px) 1fr', gap: '10px 16px', fontSize: '13px' }}>
              <dt style={{ color: 'var(--text-tertiary)' }}>{t('Stav', 'Status')}</dt>
              <dd><DelegationStatusBadge status={grant.status} /></dd>

              <dt style={{ color: 'var(--text-tertiary)' }}>{t('Udělil', 'Grantor')}</dt>
              <dd><EntityChip type="party" id={grant.grantorPartyId} label={counterpartyLabel(grant.grantorName)} /></dd>

              <dt style={{ color: 'var(--text-tertiary)' }}>{t('Obdržel', 'Grantee')}</dt>
              <dd><EntityChip type="party" id={grant.granteePartyId} label={counterpartyLabel(grant.granteeName)} /></dd>

              <dt style={{ color: 'var(--text-tertiary)' }}>{t('Typ zdroje', 'Resource type')}</dt>
              <dd>{grant.resourceType}</dd>

              <dt style={{ color: 'var(--text-tertiary)' }}>{t('Oprávnění', 'Capabilities')}</dt>
              <dd>{capabilityLabels(grant.capabilities.filter(isAssignablePresetCapability))}<LegacyCapabilityEvidence capabilities={grant.capabilities} /></dd>

              <dt style={{ color: 'var(--text-tertiary)' }}>{t('Režim schvalování', 'Approval policy')}</dt>
              <dd>{grant.approvalPolicy ?? '—'}</dd>

              <dt style={{ color: 'var(--text-tertiary)' }}>{t('Strop na transakci', 'Per-transaction cap')}</dt>
              <dd>{formatCeiling(grant.perTransactionLimit, numberLocale)}</dd>

              <dt style={{ color: 'var(--text-tertiary)' }}>{t('Denní strop', 'Daily cap')}</dt>
              <dd>{formatCeiling(grant.dailyLimit, numberLocale)}</dd>

              <dt style={{ color: 'var(--text-tertiary)' }}>{t('Měsíční strop', 'Monthly cap')}</dt>
              <dd>{formatCeiling(grant.monthlyLimit, numberLocale)}</dd>

              <dt style={{ color: 'var(--text-tertiary)' }}>{t('Platnost od', 'Valid from')}</dt>
              <dd>{formatDelegationTimestamp(grant.validFrom, dateLocale)}</dd>

              <dt style={{ color: 'var(--text-tertiary)' }}>{t('Platnost do', 'Valid until')}</dt>
              <dd>{grant.validTo ? formatDelegationTimestamp(grant.validTo, dateLocale) : t('bez omezení', 'no expiry')}</dd>

              <dt style={{ color: 'var(--text-tertiary)' }}>{t('Vytvořeno', 'Created')}</dt>
              <dd>{formatDelegationTimestamp(grant.createdAt, dateLocale)}</dd>

              <dt style={{ color: 'var(--text-tertiary)' }}>{t('Naposledy změněno', 'Last changed')}</dt>
              <dd>{formatDelegationTimestamp(grant.updatedAt, dateLocale)}</dd>

              <dt style={{ color: 'var(--text-tertiary)' }}>{t('Ukončeno', 'Closed')}</dt>
              <dd>{grant.closedAt ? `${formatDelegationTimestamp(grant.closedAt, dateLocale)} — ${grant.closedReason ?? ''}` : '—'}</dd>
            </dl>
          </div>

          <DelegationAuditTimeline grantId={grant.id} currentStatus={grant.status} />
          <CoverageProbe key={grant.id} grant={grant} />
          <BankSideActions />
        </>
      )}
    </div>
  )
}

function BankSideActions() {
  const { t } = useLanguage()
  return (
    <div className="card" style={{ padding: '16px', marginTop: '16px' }}>
      <h2 style={{ fontSize: '15px', fontWeight: 700, marginBottom: '2px' }}>
        <Lock size={15} color="var(--text-tertiary)" style={{ verticalAlign: 'middle', marginRight: '6px' }} />
        {t('Zásahy banky', 'Bank-side actions')}
      </h2>
      <p style={{ fontSize: '13px', color: 'var(--text-tertiary)' }}>
        {t(
          'Pozastavení, obnovení a odvolání z této konzole zatím nejdou. Delegační služba pro ně nemá frontu schvalování dvěma osobami a přímý zápis z konzole ADR-0230 zakazuje. Dnes je provádí fraud pipeline; operátorská cesta je samostatný krok.',
          'Suspend, reinstate and revoke are not available from this console yet. delegation-service has no four-eyes queue for them, and ADR-0230 forbids the direct write that would be the only alternative. The fraud pipeline performs them today; the operator path is a separate piece of work.',
        )}
      </p>
    </div>
  )
}
