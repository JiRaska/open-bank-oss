// SPDX-License-Identifier: Apache-2.0

import { readFileSync } from 'node:fs'
import path from 'node:path'
import { expect, test } from '@playwright/test'
import { signInAsOperator } from './helpers/auth'
import { LANG_COOKIE } from '../src/lib/i18n/language'

test('cluster dossier shows the generated GitOps snapshot and its source date', async ({ page, context, baseURL }) => {
  const snapshot = JSON.parse(readFileSync(path.join(process.cwd(), 'cluster-topology.json'), 'utf8')) as {
    generatedAt: string
    counts: Record<'namespaces' | 'networkPolicies' | 'externalSecrets' | 'clusterPolicies', number>
  }
  await signInAsOperator(context, baseURL!)

  const topologyResponse = page.waitForResponse((response) => response.url().endsWith('/api/cluster/topology'))
  await page.goto('/docs/cluster')
  const response = await topologyResponse
  expect(response.ok()).toBe(true)
  expect(await response.json()).toMatchObject({ generatedAt: snapshot.generatedAt, counts: snapshot.counts })

  for (const [label, count] of [
    ['Namespaces', snapshot.counts.namespaces],
    ['NetworkPolicies', snapshot.counts.networkPolicies],
    ['External Secrets', snapshot.counts.externalSecrets],
    ['Admission policies', snapshot.counts.clusterPolicies],
  ] as const) {
    const card = page.locator('.card').filter({ has: page.getByText(label, { exact: true }) }).first()
    await expect(card).toContainText(String(count))
  }
  await expect(page.locator('.card').filter({ has: page.getByText('NetworkPolicies', { exact: true }) }).first())
    .toContainText('declared in GitOps')
  await expect(page.locator('p').filter({ hasText: /GitOps/ }).last())
    .toContainText(String(new Date(snapshot.generatedAt).getFullYear()))
})

test('namespace guidance is understandable and does not mistake a fleet-wide policy count for local isolation', async ({ page, context, baseURL }) => {
  await signInAsOperator(context, baseURL!)
  await context.addCookies([{ name: LANG_COOKIE, value: 'en', url: baseURL! }])
  await page.goto('/docs/cluster')

  await expect(page.getByText(/The map assigns each banking domain a namespace for organizing resources/)).toBeVisible()
  await page.getByText('accounts', { exact: true }).click()
  await expect(page.locator('#cluster-ns-panel-accounts')).toContainText('Customer accounts')
  await expect(page.locator('#cluster-ns-panel-accounts')).toContainText('Fleet-wide NetworkPolicy count')
  await expect(page.locator('#cluster-ns-panel-accounts')).toContainText('does not prove isolation of this namespace')
})

test('Czech namespace guidance preserves the role and the honest policy caveat', async ({ page, context, baseURL }) => {
  await signInAsOperator(context, baseURL!)
  await context.addCookies([{ name: LANG_COOKIE, value: 'cs', url: baseURL! }])
  await page.goto('/docs/cluster')

  await expect(page.getByText(/Mapa přiřazuje bankovním doménám namespace pro správu prostředků/)).toBeVisible()
  await page.getByText('accounts', { exact: true }).click()
  await expect(page.locator('#cluster-ns-panel-accounts')).toContainText('Účty zákazníků')
  await expect(page.locator('#cluster-ns-panel-accounts')).toContainText('Počet NetworkPolicy v celé platformě')
  await expect(page.locator('#cluster-ns-panel-accounts')).toContainText('neprokazuje izolaci tohoto namespace')
  await expect(page.getByText(/pokrytí namespaců a runtime účinnost neověřeny/)).toBeVisible()
})
