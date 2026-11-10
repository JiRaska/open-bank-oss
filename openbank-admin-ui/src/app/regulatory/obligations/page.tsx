// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { getRegulatoryObligations } from '@/lib/regulatory/obligations'
import { RegulatoryObligations } from '@/components/regulatory/RegulatoryObligations'

/**
 * Regulatory → Obligations (ADR-0367). A SERVER component reading the register baked into the
 * image; rendering is in a client component because the bilingual hook is client-only.
 */
export default function RegulatoryObligationsPage() {
  return <RegulatoryObligations register={getRegulatoryObligations()} />
}
