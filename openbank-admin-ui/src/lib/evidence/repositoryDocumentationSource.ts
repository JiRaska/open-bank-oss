// SPDX-License-Identifier: Apache-2.0
import 'server-only'
import { readAdminUiContentSnapshot, contentSourceMetadata, type ContentSourceMetadata } from './contentSource'
import type { RepositoryDocumentation } from './repositoryDocumentation'

export async function resolveRepositoryDocumentation(): Promise<{
  value: RepositoryDocumentation | null; moneyPathServices: Set<string> | null; contentSource: ContentSourceMetadata
}> {
  const result = await readAdminUiContentSnapshot()
  const value = result.snapshot?.content.repositoryDocumentation ?? null
  if (value) {
    const rows = result.snapshot!.content.catalog.services as Array<{ name: string; moneyPath: boolean }>
    return { value, moneyPathServices: new Set(rows.filter(row => row.moneyPath).map(row => row.name.replace(/^openbank-/, ''))),
      contentSource: contentSourceMetadata(result) }
  }
  return { value: null, moneyPathServices: null, contentSource: repositoryDocumentationFallback(false, result.error ?? 'Published content has no repository documentation') }
}

export function repositoryDocumentationFallback(available: boolean, detail?: string): ContentSourceMetadata {
  return contentSourceMetadata({ status: 'unavailable', snapshot: null, provenance: null, ...(detail ? { error: detail } : {}) },
    { imageFallbackAvailable: available })
}

/** Minimal classification boundary for an image fallback; a partial roster stays unknown. */
export function repositoryCatalogClassification(value: unknown): Map<string, boolean> | null {
  if (!value || typeof value !== 'object' || !('services' in value) || !Array.isArray(value.services) || value.services.length === 0) return null
  const result = new Map<string, boolean>()
  for (const row of value.services) {
    if (!row || typeof row !== 'object' || typeof row.name !== 'string' || !/^[a-z0-9][a-z0-9-]*$/.test(row.name)
      || typeof row.moneyPath !== 'boolean') return null
    const service = row.name.replace(/^openbank-/, '')
    if (!service || result.has(service)) return null
    result.set(service, row.moneyPath)
  }
  return result
}
