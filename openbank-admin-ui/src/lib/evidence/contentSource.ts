// SPDX-License-Identifier: Apache-2.0
// Shared server-side consumer for the independently published reviewed-source content set.
import 'server-only'
import {
  createAdminUiContentSnapshotReader,
  type AdminUiContentSnapshot,
} from './contentSnapshot'
import type { EvidenceSnapshotResult } from './publishedSnapshot'

const reader = createAdminUiContentSnapshotReader()

export type ContentSourceState = 'ready' | 'stale' | 'unavailable'

export interface ContentSourceMetadata {
  source: 'published' | 'image-baked' | 'unavailable'
  status: ContentSourceState
  sourceSha: string | null
  sourceCollectedAt: string | null
  imageBuiltAt: string | null
  fetchedAt: string
  runId?: number
  runAttempt?: number
  detail?: string
}

export function readAdminUiContentSnapshot(): Promise<EvidenceSnapshotResult<AdminUiContentSnapshot>> {
  return reader.read()
}

/**
 * Content metadata deliberately separates source identity/time from this request's fetch time.
 * The image copy remains a usable source during publisher outages, but is always labeled stale.
 */
export function contentSourceMetadata(
  result: EvidenceSnapshotResult<AdminUiContentSnapshot>,
  options: { imageFallbackAvailable: boolean; fetchedAt?: string } = { imageFallbackAvailable: false },
): ContentSourceMetadata {
  const fetchedAt = options.fetchedAt ?? new Date().toISOString()
  if (result.snapshot && result.provenance) {
    return {
      source: 'published',
      status: result.status,
      sourceSha: result.provenance.sourceSha,
      sourceCollectedAt: result.provenance.collectedAt,
      imageBuiltAt: null,
      fetchedAt,
      runId: result.provenance.runId,
      runAttempt: result.provenance.runAttempt,
      ...(result.error ? { detail: result.error } : {}),
    }
  }

  if (!options.imageFallbackAvailable) {
    return {
      source: 'unavailable',
      status: 'unavailable',
      sourceSha: null,
      sourceCollectedAt: null,
      imageBuiltAt: null,
      fetchedAt,
      ...(result.error ? { detail: result.error } : {}),
    }
  }

  const rawSha = process.env.BUILD_GIT_SHA ?? process.env.NEXT_PUBLIC_BUILD_GIT_SHA
  const sourceSha = rawSha && /^[0-9a-f]{7,40}$/i.test(rawSha) ? rawSha.toLowerCase() : null
  const rawBuildDate = process.env.BUILD_DATE ?? process.env.NEXT_PUBLIC_BUILD_DATE
  const imageBuiltAt = rawBuildDate && Number.isFinite(Date.parse(rawBuildDate)) ? rawBuildDate : null
  return {
    source: 'image-baked',
    status: 'stale',
    sourceSha,
    sourceCollectedAt: null,
    imageBuiltAt,
    fetchedAt,
    ...(result.error ? { detail: result.error } : {}),
  }
}

export interface ResolvedContent<T> {
  value: T | null
  contentSource: ContentSourceMetadata
}

/**
 * Prefer the validated, independently published source tree. If it is absent,
 * retain the existing image-baked behavior but expose that it may be behind.
 * A stale published snapshot remains usable and keeps its original provenance.
 */
export async function resolveAdminUiContent<T>(
  selectPublished: (snapshot: AdminUiContentSnapshot) => T,
  loadImageBaked: () => Promise<T | null>,
): Promise<ResolvedContent<T>> {
  const result = await readAdminUiContentSnapshot()
  if (result.snapshot) {
    return {
      value: selectPublished(result.snapshot),
      contentSource: contentSourceMetadata(result),
    }
  }

  let value: T | null = null
  try { value = await loadImageBaked() } catch { /* report honest unavailable state below */ }
  return {
    value,
    contentSource: contentSourceMetadata(result, { imageFallbackAvailable: value !== null }),
  }
}
