// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { getCardCapabilities } from '@/lib/cards/capabilities'
import { CardCapabilityMatrix } from '@/components/cards/CardCapabilityMatrix'

/**
 * Card Center — the capability matrix (ADR-0283 phase 3, issue #8811).
 *
 * A SERVER component that reads the registry baked into the image. There is no client fetch,
 * so the page cannot render a copy other than the one that shipped with the build. Do not force
 * static rendering here: the root layout must read request headers to nonce bootstrap scripts
 * under the production strict-dynamic CSP.
 *
 * Rendering lives in [CardCapabilityMatrix], a client component, because the admin UI is bilingual
 * by default and `useLanguage` is a client hook. The split is not stylistic: `fs` is unavailable to
 * a client component and the language hook is unavailable to a server one, so one file could not
 * be both stale-proof and bilingual.
 */
export default function CardCapabilitiesPage() {
  return <CardCapabilityMatrix registry={getCardCapabilities()} />
}
