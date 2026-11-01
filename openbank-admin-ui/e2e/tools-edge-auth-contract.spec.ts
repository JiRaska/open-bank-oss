// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import { expect, test } from '@playwright/test'

// These HTTP request checks run only against the deployed Gateway, with no route mocks or local
// stand-ins. They cover the unauthenticated auth-edge contract for tool roots and nested URLs;
// they do not exercise browser clicks or authenticated tool rendering.
const toolPaths = [
  '/tools/grafana',
  '/tools/grafana/',
  '/tools/grafana/d/openbank-business-warehouse?orgId=1',
  '/tools/alertmanager',
  '/tools/alertmanager/',
  '/tools/alertmanager/api/v2/status',
  '/tools/pyrra',
  '/tools/pyrra/',
  '/tools/pyrra/objectives',
]

for (const path of toolPaths) {
  test(`tool edge sends ${path} to login without a session @deployed`, async ({ request }) => {
    const response = await request.get(path, { maxRedirects: 0 })
    expect(response.status(), 'Envoy tool-gate unauthenticated contract').toBe(302)

    const location = new URL(response.headers().location ?? '', response.url())
    expect(location.pathname).toBe('/auth/login')
    expect(location.searchParams.get('callbackUrl')).toBe(path)
    expect((await response.text()).length).toBeLessThan(2_000)
  })
}
