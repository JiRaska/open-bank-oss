// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { describe, expect, it } from 'vitest'
import { buildSentryOptions } from '@/lib/telemetry/glitchtip'

describe('Sentry 11 operator data collection', () => {
  it.each(['browser', 'server'] as const)('keeps identifying categories disabled in %s', (runtime) => {
    const options = buildSentryOptions(runtime)
    expect(options.dataCollection).toMatchObject({
      userInfo: false,
      cookies: false,
      httpHeaders: { request: false, response: false },
      httpBodies: [],
      urlQueryParams: false,
      genAI: { inputs: false, outputs: false },
      databaseQueryData: false,
      queues: false,
      graphQL: { document: false, variables: false },
    })
  })
})
