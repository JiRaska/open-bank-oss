// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

import Link from 'next/link'
import { EntityChip } from '@/components/entities/EntityChip'
import {
  effectiveResourceDetails,
  grantConditions,
  grantResourcePresentation,
  matchedRoleName,
  type EffectiveAccessPayload,
} from '@/components/delegations/EffectiveAccess'
import { LegacyCapabilityEvidence } from '@/components/delegations/LegacyCapabilityEvidence'
import {
  DelegationStatusBadge,
  grantCounterparty,
  type Grant,
} from '@/components/delegations/GrantView'
import { capabilityLabel, isAssignablePresetCapability } from '@/lib/delegations/rolePresets'
import { useLanguage } from '@/lib/i18n/LanguageContext'

type DirectionState = 'ok' | 'forbidden' | 'unavailable'

export function GrantTable({
  title, subtitle, grants, state, direction, effectiveAccess,
}: { title: string; subtitle: string; grants: Grant[]; state: DirectionState; direction: 'granted' | 'received'; effectiveAccess: EffectiveAccessPayload | null }) {
  const { t, language } = useLanguage()
  const details = effectiveAccess ? effectiveResourceDetails(effectiveAccess) : []

  return (
    <div className="card" style={{ padding: '16px', marginBottom: '20px' }}>
      <h2 style={{ fontSize: '15px', fontWeight: 700, marginBottom: '2px' }}>{title}</h2>
      <p style={{ fontSize: '12px', color: 'var(--text-tertiary)', marginBottom: '12px' }}>{subtitle}</p>

      {state !== 'ok' ? (
        <div style={{ padding: '16px', borderRadius: '8px', background: 'var(--surface-3)', fontSize: '13px' }}>
          {state === 'forbidden'
            ? t(
                'Tento pohled vám nebyl povolen — nezaměňujte za „žádné delegace“.',
                'This view was refused for your role — do not read it as “no delegations”.',
              )
            : t(
                'Tento pohled se teď nepodařilo načíst — nezaměňujte za „žádné delegace“.',
                'This view could not be loaded right now — do not read it as “no delegations”.',
              )}
        </div>
      ) : grants.length === 0 ? (
        <div style={{ padding: '16px', color: 'var(--text-tertiary)', fontSize: '13px' }}>
          {t('Žádné delegace.', 'No delegations.')}
        </div>
      ) : (
        <div style={{ overflowX: 'auto' }}>
          <table className="table" style={{ width: '100%' }}>
            <thead>
              <tr>
                <th>{t('Stav', 'Status')}</th>
                <th>{t('Protistrana', 'Counterparty')}</th>
                <th>{t('Role', 'Role')}</th>
                <th>{t('Zdroj', 'Resource')}</th>
                <th>{t('Práva', 'Rights')}</th>
                <th>{t('Podmínky', 'Conditions')}</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {grants.map(g => {
                const counterparty = grantCounterparty(g, direction)
                const resource = grantResourcePresentation(g, details, language)
                const role = effectiveAccess?.sources.presets === 'ok'
                  ? matchedRoleName(g, effectiveAccess.presets, language)
                  : t('Role není dostupná', 'Role unavailable')
                return <tr key={g.id}>
                  <td><DelegationStatusBadge status={g.status} /></td>
                  <td><EntityChip type="party" id={counterparty.id} label={counterparty.name} /></td>
                  <td style={{ fontSize: '12px', fontWeight: 650 }}>{role}</td>
                  <td style={{ fontSize: '12px' }}><strong style={{ display: 'block' }}>{resource.label}</strong>{resource.meta && <span style={{ color: 'var(--text-tertiary)' }}>{resource.meta}</span>}</td>
                  <td><div aria-label={t('Účinná práva', 'Effective rights')} style={{ display: 'flex', flexWrap: 'wrap', gap: 4 }}>{g.capabilities.filter(isAssignablePresetCapability).map(capability => <span key={capability} title={capability} style={{ borderRadius: 999, padding: '3px 7px', fontSize: 10, background: 'var(--surface-3)', border: '1px solid var(--border)' }}>{capabilityLabel(capability, language)}</span>)}</div><LegacyCapabilityEvidence capabilities={g.capabilities} /></td>
                  <td style={{ fontSize: '11px' }}>{grantConditions(g, language).map(condition => <div key={condition.label}><span style={{ color: 'var(--text-tertiary)' }}>{condition.label}:</span> <strong>{condition.value}</strong></div>)}</td>
                  <td>
                    <Link href={`/delegations/${g.id}`} className="btn btn-secondary" style={{ fontSize: '12px' }}>
                      {t('Detail', 'Detail')}
                    </Link>
                  </td>
                </tr>
              })}
            </tbody>
          </table>
        </div>
      )}
    </div>
  )
}
