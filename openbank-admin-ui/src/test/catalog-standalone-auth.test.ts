// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { describe, expect, it } from 'vitest'
import { extractCatalogScopeRoles } from '@/lib/auth/catalogScopes'
import { requireSecurePublicUrl } from '@/lib/auth/publicUrl'
import { hasPermission, ROLES } from '@/lib/auth/roles'

describe('standalone Product Studio auth contract', () => {
  it('maps a provider-specific claim and scope vocabulary', () => {
    expect(extractCatalogScopeRoles(
      { permissions: ['products.read', 'products.approve'] },
      { claim: 'permissions', read: 'products.read', author: 'products.write', publish: 'products.approve' },
    )).toEqual(['CATALOG_SCOPE_READ', 'CATALOG_SCOPE_PUBLISH'])
  })

  it('derives catalog read only from named human pension approver realm roles', () => {
    const legalReader = extractCatalogScopeRoles({
      preferred_username: 'legal-reviewer',
      sub: 'legal-reviewer',
      realm_access: { roles: ['PENSION_LEGAL_APPROVER'] },
    })
    expect(hasPermission(legalReader, 'catalog:read')).toBe(true)
    expect(hasPermission(legalReader, 'catalog:pension:legal-approve')).toBe(true)
    expect(hasPermission(legalReader, 'catalog:pension:product-approve')).toBe(false)
    expect(hasPermission(legalReader, 'catalog:publish')).toBe(false)
    const productReader = extractCatalogScopeRoles({
      preferred_username: 'product-reviewer',
      sub: 'product-reviewer',
      realm_access: { roles: ['PENSION_PRODUCT_OWNER'] },
    })
    expect(hasPermission(productReader, 'catalog:read')).toBe(true)
    expect(hasPermission(productReader, 'catalog:pension:product-approve')).toBe(true)
    expect(hasPermission(productReader, 'catalog:pension:legal-approve')).toBe(false)
    const scopeOnly = extractCatalogScopeRoles({ scope: 'pension:legal-approve pension:product-approve' })
    expect(scopeOnly).toEqual([])
    const machineWithRole = extractCatalogScopeRoles({
      preferred_username: 'service-account-other',
      sub: 'machine-subject',
      realm_access: { roles: ['PENSION_LEGAL_APPROVER'] },
    })
    expect(machineWithRole).toEqual([])
  })

  it('keeps author and independent publisher capabilities separate', () => {
    const author = [ROLES.CATALOG_READ, ROLES.CATALOG_AUTHOR]
    const publisher = [ROLES.CATALOG_READ, ROLES.CATALOG_PUBLISH]

    expect(hasPermission(author, 'catalog:read')).toBe(true)
    expect(hasPermission(author, 'catalog:author')).toBe(true)
    expect(hasPermission(author, 'catalog:publish')).toBe(false)
    expect(hasPermission(publisher, 'catalog:read')).toBe(true)
    expect(hasPermission(publisher, 'catalog:author')).toBe(false)
    expect(hasPermission(publisher, 'catalog:publish')).toBe(true)
  })

  it('rejects remote plaintext production URLs and allows only an explicit loopback escape', () => {
    const production = { production: true, buildPhase: false, allowInsecureLoopback: false }
    expect(() => requireSecurePublicUrl('NEXTAUTH_URL', 'http://studio.example', production))
      .toThrow('NEXTAUTH_URL must use https:// in production')
    expect(requireSecurePublicUrl('NEXTAUTH_URL', 'https://studio.example', production))
      .toBe('https://studio.example')
    expect(requireSecurePublicUrl('NEXTAUTH_URL', 'http://localhost:3000', {
      ...production, allowInsecureLoopback: true,
    })).toBe('http://localhost:3000')
  })
})
