// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import { expect, test } from '@playwright/test'
import { signInAsOperator } from './helpers/auth'

const STATUS = {
  argocd: { status: 'UP', latencyMs: 12, checkedAt: '2026-08-31T12:00:00Z' },
  postgres: { status: 'DOWN', latencyMs: 34, checkedAt: '2026-08-31T12:00:00Z' },
}

test.beforeEach(async ({ context, baseURL, page }) => {
  await signInAsOperator(context, baseURL!)
  await page.route('**/api/infra/status', route => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify(STATUS),
  }))
  await page.route('**/api/infra/lifecycle', route => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify({ components: [{ id: 'postgres', urgency: 'patch-available' }] }),
  }))
})

test('filters the live infrastructure map and keeps a DOWN state semantically explicit', async ({ page }) => {
  await page.goto('/infrastructure/topology')

  await expect(page.getByRole('heading', { name: 'Infrastructure Topology' })).toBeVisible()
  await expect(page.getByRole('button', { name: 'PostgreSQL' })).toBeVisible()

  await page.getByRole('button', { name: 'Platform / control plane' }).click()
  await expect(page.getByRole('button', { name: 'ArgoCD' })).toBeVisible()
  await expect(page.getByRole('button', { name: 'PostgreSQL' })).toHaveCount(0)

  await page.getByRole('button', { name: 'All' }).click()
  await page.getByRole('button', { name: 'PostgreSQL' }).click()

  const details = page.getByRole('region', { name: 'Infrastructure component details' })
  await expect(details).toBeVisible()
  await expect(details.getByText('Probe latency')).toBeVisible()
  await expect(details.getByText('34 ms')).toBeVisible()
  await expect(details.locator('.badge-danger', { hasText: 'DOWN' })).toBeVisible()

  const refresh = page.getByRole('button', { name: 'Refresh infrastructure status' })
  await refresh.click()
  await expect(refresh).toHaveAttribute('aria-busy', 'false')
})
