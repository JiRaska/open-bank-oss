// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import { describe, expect, it } from 'vitest'
import { catalogServiceCount } from '@/lib/services/catalogCount'

describe('service documentation catalog count', () => {
  it('counts services without treating runnable components or libraries as microservices', () => {
    expect(catalogServiceCount([
      { kind: 'service' },
      { kind: 'service' },
      { kind: 'component' },
      { kind: 'library' },
      { kind: 'ui' },
    ])).toBe(2)
  })
})
