// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { getCardCapabilities } from '@/lib/cards/capabilities'
import { CardCapabilityMatrix } from '@/components/cards/CardCapabilityMatrix'

/**
 * Card Center — the capability matrix (ADR-0283 phase 3, issue #8811).
 *
 * A SERVER component that does one thing: read the registry baked into the image. There is no
 * client fetch and no loading state, so the page cannot render a copy of the registry other than
 * the one that shipped with the build.
 *
 * Rendering lives in [CardCapabilityMatrix], a client component, because the admin UI is bilingual
 * by default and `useLanguage` is a client hook. The split is not stylistic: `fs` is unavailable to
 * a client component and the language hook is unavailable to a server one, so one file could not
 * be both stale-proof and bilingual.
 */
export const dynamic = 'force-static'

export default function CardCapabilitiesPage() {
  return <CardCapabilityMatrix registry={getCardCapabilities()} />
}
