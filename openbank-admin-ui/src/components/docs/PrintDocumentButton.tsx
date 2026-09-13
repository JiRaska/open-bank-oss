'use client'

import { Printer } from 'lucide-react'
import { useLanguage } from '@/lib/i18n/LanguageContext'

/** Native print-to-PDF keeps long-form docs crisp and avoids a second server renderer. */
export function PrintDocumentButton() {
  const { t } = useLanguage()
  return (
    <button type="button" className="docs-print-action" onClick={() => window.print()}>
      <Printer size={14} aria-hidden="true" />
      {t('Exportovat PDF', 'Export PDF')}
    </button>
  )
}
