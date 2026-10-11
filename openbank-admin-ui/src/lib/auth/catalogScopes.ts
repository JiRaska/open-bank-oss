// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

export interface CatalogScopeConfig {
  claim: string
  read: string
  author: string
  publish: string
  legalApproval: string
  productApproval: string
}

export function catalogScopeConfig(environment: NodeJS.ProcessEnv = process.env): CatalogScopeConfig {
  return {
    claim: environment.CATALOG_SCOPE_CLAIM || 'scope',
    read: environment.CATALOG_READ_SCOPE || 'catalog:read',
    author: environment.CATALOG_AUTHOR_SCOPE || 'catalog:author',
    publish: environment.CATALOG_PUBLISH_SCOPE || 'catalog:publish',
    legalApproval: environment.PENSION_LEGAL_APPROVAL_SCOPE || 'pension:legal-approve',
    productApproval: environment.PENSION_PRODUCT_APPROVAL_SCOPE || 'pension:product-approve',
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
  const realmAccess = payload.realm_access as { roles?: unknown } | undefined
  const realmRoles = Array.isArray(realmAccess?.roles) ? realmAccess.roles : []
  const username = payload.preferred_username
  const subject = payload.sub
  const human = typeof username === 'string' && username.length > 0 &&
    !username.startsWith('service-account-') && !username.startsWith('agent:') &&
    !(typeof subject === 'string' && subject.startsWith('agent:'))
  const legalApprover = human && realmRoles.includes('ROLE_PENSION_LEGAL_COUNSEL') && scopes.includes(config.legalApproval)
  const productOwner = human && realmRoles.includes('ROLE_PENSION_PRODUCT_OWNER') && scopes.includes(config.productApproval)
  return [
    ...(scopes.includes(config.read) || legalApprover || productOwner ? ['CATALOG_SCOPE_READ'] : []),
    ...(scopes.includes(config.author) ? ['CATALOG_SCOPE_AUTHOR'] : []),
    ...(scopes.includes(config.publish) ? ['CATALOG_SCOPE_PUBLISH'] : []),
    ...(legalApprover ? ['PENSION_LEGAL_APPROVER'] : []),
    ...(productOwner ? ['PENSION_PRODUCT_OWNER'] : []),
  ]
}
