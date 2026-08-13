// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

export interface CatalogScopeConfig {
  claim: string
  read: string
  author: string
  publish: string
}

export function catalogScopeConfig(environment: NodeJS.ProcessEnv = process.env): CatalogScopeConfig {
  return {
    claim: environment.CATALOG_SCOPE_CLAIM || 'scope',
    read: environment.CATALOG_READ_SCOPE || 'catalog:read',
    author: environment.CATALOG_AUTHOR_SCOPE || 'catalog:author',
    publish: environment.CATALOG_PUBLISH_SCOPE || 'catalog:publish',
  }
}

export function extractCatalogScopeRoles(
  payload: Record<string, unknown>,
  config: CatalogScopeConfig = catalogScopeConfig(),
): string[] {
  const raw = payload[config.claim]
  const scopes = typeof raw === 'string'
    ? raw.split(' ')
    : Array.isArray(raw) ? raw.filter((scope): scope is string => typeof scope === 'string') : []
  return [
    ...(scopes.includes(config.read) ? ['CATALOG_SCOPE_READ'] : []),
    ...(scopes.includes(config.author) ? ['CATALOG_SCOPE_AUTHOR'] : []),
    ...(scopes.includes(config.publish) ? ['CATALOG_SCOPE_PUBLISH'] : []),
  ]
}
