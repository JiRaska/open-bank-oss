// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import { isUuid } from './resolveParty'

export type CreatedParty = { id: string }

export function parseCreatedParty(raw: unknown): CreatedParty | null {
  if (typeof raw !== 'object' || raw === null || Array.isArray(raw)) return null
  const id = (raw as Record<string, unknown>).id
  return typeof id === 'string' && isUuid(id) ? { id } : null
}
