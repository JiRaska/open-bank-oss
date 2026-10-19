// SPDX-License-Identifier: Apache-2.0
import type { ContentSourceMetadata } from '@/lib/evidence/contentSource'

/** Provenance for reviewed documentation content; it is not an operational status. */
export function ContentSourceNote({ source }: { source?: ContentSourceMetadata | null }) {
  if (!source) return null
  const label = source.source === 'published'
    ? `Reviewed source ${source.sourceSha ?? 'unknown SHA'}`
    : source.source === 'image-baked' ? 'Image-baked source (may be stale)' : 'Content source unavailable'
  return (
    <p data-testid="content-source-note" style={{ margin: '0 0 16px', color: 'var(--text-tertiary)', fontSize: '11px' }}>
      {label}
      {source.source === 'published' && source.sourceCollectedAt
        ? ` · collected/published · sestaveno/publikováno ${source.sourceCollectedAt}`
        : source.source === 'image-baked' && source.imageBuiltAt
          ? ` · image built ${source.imageBuiltAt}`
          : ''}
      {source.fetchedAt ? ` · fetched ${source.fetchedAt}` : ''}
      {source.status === 'stale' ? ' · stale' : ''}
    </p>
  )
}
