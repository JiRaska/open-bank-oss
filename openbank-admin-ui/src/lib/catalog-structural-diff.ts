// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import type { ProductRevision } from '@/lib/generated/product-catalog-v2'

export type CatalogDiffKind = 'ADDED' | 'REMOVED' | 'CHANGED'
export interface CatalogDiffEntry { path: string; kind: CatalogDiffKind; before?: unknown; after?: unknown }

function isObject(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
}

/** The editor and live comparison use one envelope so metadata does not create synthetic changes. */
export function catalogRevisionEditorDocument(revision: ProductRevision): Record<string, unknown> {
  return {
    schemaRef: revision.schemaRef,
    ...revision.content,
    effectiveFrom: revision.effectiveFrom ?? null,
    effectiveTo: revision.effectiveTo ?? null,
  }
}

export function diffCatalogDocuments(before: unknown, after: unknown, path = '$'): CatalogDiffEntry[] {
  if (Object.is(before, after)) return []
  if (isObject(before) && isObject(after)) {
    const keys = [...new Set([...Object.keys(before), ...Object.keys(after)])].sort()
    return keys.flatMap(key => {
      const child = `${path}.${key}`
      if (!(key in before)) return [{ path: child, kind: 'ADDED' as const, after: after[key] }]
      if (!(key in after)) return [{ path: child, kind: 'REMOVED' as const, before: before[key] }]
      return diffCatalogDocuments(before[key], after[key], child)
    })
  }
  if (Array.isArray(before) && Array.isArray(after)) {
    const length = Math.max(before.length, after.length)
    return Array.from({ length }, (_, index) => {
      const child = `${path}[${index}]`
      if (index >= before.length) return [{ path: child, kind: 'ADDED' as const, after: after[index] }]
      if (index >= after.length) return [{ path: child, kind: 'REMOVED' as const, before: before[index] }]
      return diffCatalogDocuments(before[index], after[index], child)
    }).flat()
  }
  return [{ path, kind: 'CHANGED', before, after }]
}
