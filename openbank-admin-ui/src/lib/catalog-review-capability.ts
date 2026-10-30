// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root for details.

export interface CatalogReviewModel {
  id: string
  provider: string
  sensitivity: string
}

export interface CatalogReviewCapability {
  status: 'checking' | 'available' | 'unavailable' | 'unauthenticated' | 'forbidden' | 'error'
  mode?: 'sandbox-hosted' | 'self-hosted'
  defaultModel?: string
  models: CatalogReviewModel[]
}

const empty = (status: CatalogReviewCapability['status']): CatalogReviewCapability => ({ status, models: [] })

/** Parse the per-workflow capability endpoint; model routing remains a server-side decision. */
export function resolveCatalogReviewCapability(
  status: number,
  ok: boolean,
  body: unknown,
): CatalogReviewCapability {
  if (status === 401) return empty('unauthenticated')
  if (status === 403) return empty('forbidden')
  if (!ok) return empty('error')
  if (!body || typeof body !== 'object' || Array.isArray(body)) return empty('error')
  const payload = body as { available?: unknown; mode?: unknown; defaultModel?: unknown; models?: unknown }
  if (typeof payload.available !== 'boolean' || !Array.isArray(payload.models)) return empty('error')
  if (!payload.models.every(isModel)) return empty('error')
  const models = payload.models
  if (!payload.available) {
    if (payload.defaultModel !== null && payload.defaultModel !== undefined) return empty('error')
    return { status: 'unavailable', models: [] }
  }
  if (!['sandbox-hosted', 'self-hosted'].includes(String(payload.mode)) ||
    typeof payload.defaultModel !== 'string' || !models.some(model => model.id === payload.defaultModel)) {
    return empty('error')
  }
  return {
    status: 'available',
    mode: payload.mode as CatalogReviewCapability['mode'],
    defaultModel: payload.defaultModel,
    models,
  }
}

function isModel(value: unknown): value is CatalogReviewModel {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return false
  const model = value as Partial<CatalogReviewModel>
  return typeof model.id === 'string' && model.id.trim().length > 0 &&
    typeof model.provider === 'string' && model.provider.trim().length > 0 &&
    typeof model.sensitivity === 'string' && ['HOSTED', 'SELF_HOSTED'].includes(model.sensitivity.trim().toUpperCase())
}
