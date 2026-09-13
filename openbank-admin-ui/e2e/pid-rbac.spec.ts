// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { expect, test } from '@playwright/test'
import { signInWithRoles } from './helpers/auth'

test('stops a payments viewer before the PID PII workspace renders', async ({ context, page, baseURL }) => {
  await signInWithRoles(context, baseURL!, ['ROLE_VIEWER'])

  await page.goto('/pid')

  await expect(page).toHaveURL(/\/auth\/forbidden\?path=%2Fpid$/)
  await expect(page.getByRole('heading', { name: /Tato oblast není součástí vaší role|This area is not in your role/ })).toBeVisible()
  await expect(page.getByText('/pid', { exact: true })).toBeVisible()
  await expect(page.getByRole('heading', { name: /Osobní identifikační údaje|Personal Identification Data/ })).toHaveCount(0)
})
