// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { expect, test } from '@playwright/test'
import { signInAsOperator } from './helpers/auth'

test.describe('SDD mandate recovery', () => {
  test.beforeEach(async ({ context, baseURL }) => {
    await signInAsOperator(context, baseURL!)
  })

  test('keeps the last mandate snapshot visible when refresh becomes unavailable', async ({ page }) => {
    let requests = 0
    let failRefresh = false

    await page.route('**/api/svc/sdd-service/api/v1/sdd/mandates/recent**', async route => {
      requests += 1
      expect(route.request().url()).toContain('limit=50')

      if (!failRefresh) {
        await route.fulfill({
          contentType: 'application/json',
          body: JSON.stringify([{
            id: '11111111-1111-1111-1111-111111111111',
            accountId: '22222222-2222-2222-2222-222222222222',
            umr: 'UMR-EVIDENCE-42',
            creditorIdentifier: 'CZ98ZZZ00000000001',
            creditorName: 'Verified Utilities SE',
            debtorName: 'Example Manufacturing a.s.',
            debtorIban: 'CZ6508000000192000145399',
            status: 'ACTIVE',
            scheme: 'B2B',
            sequenceType: 'RCUR',
            signatureDate: '2026-01-15',
            b2bConfirmed: true,
            lastCollectionDate: '2026-08-31',
            lastPreNotificationDate: '2026-08-20',
            createdAt: '2026-08-31T08:00:00Z',
            amendments: [],
          }]),
        })
        return
      }

      await route.fulfill({ status: 503, contentType: 'application/json', body: '{}' })
    })

    await page.goto('/sdd')
    await expect(page.getByText('UMR-EVIDENCE-42')).toBeVisible()
    await expect(page.getByText('Verified Utilities SE')).toBeVisible()
    await expect(page.getByText(/B2B potvrzeno|B2B confirmed/)).toBeVisible()
    await expect(page.getByText('B2B · RCUR')).toBeVisible()

    const initialRequests = requests
    failRefresh = true
    await page.getByRole('button', { name: /Refresh direct debit mandates|Obnovit mandáty inkas/ }).click()

    await expect(page.getByText('UMR-EVIDENCE-42')).toBeVisible()
    await expect(page.getByText('Verified Utilities SE')).toBeVisible()
    await expect(page.getByText(/Failed to load: Direct debit mandates|Načtení selhalo: Mandáty inkas/)).toBeVisible()
    expect(requests).toBe(initialRequests + 1)
  })
})
