// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
'use client'

import { useLanguage } from '@/lib/i18n/LanguageContext'
import type { EvidenceIntegrity } from '@/lib/lending/evidenceIntegrity'

/**
 * Integrity banner above the evidence trail (#11900). The tampered alarm is driven by the BUNDLE
 * flag, not by the rows in the table: the table shows only transitions, and an altered entry of any
 * other type must still be impossible to miss.
 */
export function EvidenceIntegrityNotice({ integrity }: { integrity: EvidenceIntegrity }) {
  const { t } = useLanguage()
  return (
    <>
      {integrity.tampered && (
        <div
          data-testid="evidence-tampered"
          role="alert"
          style={{ padding: 16, fontSize: 13, color: 'var(--danger)', borderBottom: '1px solid var(--border)' }}
        >
          {t(
            'Některý záznam této stopy neprošel kontrolou integrity — byl změněn po zápisu. Nespoléhejte na něj a eskalujte na compliance / bezpečnost.',
            'An entry in this trail failed its integrity check — it was changed after it was written. Do not rely on it; escalate to compliance / security.',
          )}
        </div>
      )}
      {integrity.truncated && (
        <div data-testid="evidence-truncated" style={{ padding: 12, fontSize: 12, color: 'var(--text-tertiary)', borderBottom: '1px solid var(--border)' }}>
          {t(
            'Stopa je zkrácená: existuje víc záznamů, než se zobrazilo. Nejde o úplnou historii.',
            'This trail is truncated: more entries exist than are shown. It is not the complete history.',
          )}
        </div>
      )}
      {integrity.source === 'audit-chain' && (
        <div data-testid="evidence-attestation" style={{ padding: '8px 14px', fontSize: 11, color: 'var(--text-tertiary)' }}>
          {t('Zdroj: auditní řetězec odolný proti změnám.', 'Source: tamper-evident audit chain.')}
        </div>
      )}
    </>
  )
}
