// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import { expect, test } from '@playwright/test'
import { signInAsOperator } from './helpers/auth'

const services = [
  {
    serviceName: 'account-service', dataDomain: 'core', dataLineageRole: 'producer',
    lineage: { downstream: [{ serviceName: 'ledger-service', relationType: 'api' }], interfaces: { apis: ['/api/v1/accounts'] } },
  },
  {
    serviceName: 'ledger-service', dataDomain: 'core', dataLineageRole: 'consumer',
    lineage: { upstream: [{ serviceName: 'account-service', relationType: 'api' }] },
  },
]

test.describe('data lineage evidence', () => {
  test.beforeEach(async ({ context, baseURL }) => {
    await signInAsOperator(context, baseURL!)
  })

  test('retains technical refresh failures but purges evidence when authorization is lost', async ({ page }) => {
    let phase: 'verified' | 'malformed' | 'unauthorized' = 'verified'
    await page.route('**/api/catalog/governance', route => {
      if (phase === 'unauthorized') return route.fulfill({ status: 401, body: 'unauthorized' })
      return route.fulfill({
        contentType: 'application/json',
        body: JSON.stringify(phase === 'verified' ? { available: true, services } : { available: true, services: 'invalid' }),
      })
    })

    await page.goto('/docs/lineage')
    await expect(page.getByText('account', { exact: true })).toBeVisible()
    await expect(page.getByText('ledger', { exact: true })).toBeVisible()

    phase = 'malformed'
    await page.getByRole('button', { name: /Refresh|Obnovit/ }).click()
    await expect(page.getByText(/last verified map|poslední ověřená mapa/i)).toBeVisible()
    await expect(page.getByText('account', { exact: true })).toBeVisible()

    phase = 'unauthorized'
    await page.getByRole('button', { name: /Refresh|Obnovit/ }).click()
    await expect(page.getByText(/Session expired|Vypršela relace/i)).toBeVisible()
    await expect(page.getByText('account', { exact: true })).toHaveCount(0)
  })
})
