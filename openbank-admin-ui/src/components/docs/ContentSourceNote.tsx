// SPDX-License-Identifier: Apache-2.0
'use client'

import type { ContentSourceMetadata } from '@/lib/evidence/contentSource'
import { useLanguage } from '@/lib/i18n/LanguageContext'

/** Provenance for reviewed documentation content; it is not an operational status. */
export function ContentSourceNote({ source }: { source?: ContentSourceMetadata | null }) {
  const { t } = useLanguage()
  if (!source) return null
  const label = source.source === 'published'
    ? t(`Zdroj podle revize ${source.sourceSha ?? 'neznámé SHA'}`, `Reviewed source ${source.sourceSha ?? 'unknown SHA'}`)
    : source.source === 'image-baked'
      ? t('Zdroj z image (může být zastaralý)', 'Image-baked source (may be stale)')
      : t('Zdroj obsahu není dostupný', 'Content source unavailable')
  return (
    <p data-testid="content-source-note" style={{ margin: '0 0 16px', color: 'var(--text-tertiary)', fontSize: '11px' }}>
      {label}
      {source.source === 'published' && source.sourceCollectedAt
        ? ` · ${t('snímek sestaven', 'snapshot collected')} ${source.sourceCollectedAt}`
        : source.source === 'image-baked' && source.imageBuiltAt
          ? ` · ${t('vytvořeno v image', 'image built')} ${source.imageBuiltAt}`
          : ''}
      {source.fetchedAt ? ` · ${t('načteno', 'fetched')} ${source.fetchedAt}` : ''}
      {source.status === 'stale' ? ` · ${t('zastaralé', 'stale')}` : ''}
    </p>
  )
}
