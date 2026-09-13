// SPDX-License-Identifier: Apache-2.0
'use client'

import { useLanguage } from '@/lib/i18n/LanguageContext'
import { capabilityLabel, isAssignablePresetCapability } from '@/lib/delegations/rolePresets'

export function LegacyCapabilityEvidence({ capabilities }: { capabilities: string[] }) {
  const { t, language } = useLanguage()
  const legacyCapabilities = capabilities.filter(capability => !isAssignablePresetCapability(capability))
  if (legacyCapabilities.length === 0) return null

  return <div role="note" aria-label={t('Historická nepodporovaná práva', 'Legacy unsupported capabilities')} style={{ marginTop: 8, padding: '7px 9px', borderRadius: 7, color: 'var(--warning-text)', background: 'var(--warning-bg)', border: '1px solid var(--warning-border)', fontSize: 10 }}>
    <strong style={{ display: 'block' }}>{t('Pouze historický záznam — nejde o účinné právo', 'Historical evidence only — not effective authority')}</strong>
    <div style={{ display: 'flex', flexWrap: 'wrap', gap: 5, marginTop: 5 }}>{legacyCapabilities.map(capability => <span key={capability} title={capability}>{capabilityLabel(capability, language)} · {t('nevymáháno', 'not enforced')}</span>)}</div>
  </div>
}
