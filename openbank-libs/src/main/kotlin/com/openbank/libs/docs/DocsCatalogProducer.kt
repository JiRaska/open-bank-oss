// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.libs.docs

import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Produces
import jakarta.inject.Singleton

/**
 * Wires a singleton [DocsCatalog] for the service from its bundled
 * `docs/<slug>.md` classpath resources.
 *
 * Services with no `docs/` resources get an empty catalogue (no error,
 * no special-casing in the consuming endpoint) — the endpoint simply
 * returns `items: []` and `available: false`.
 */
@ApplicationScoped
class DocsCatalogProducer {

    @Produces
    @Singleton
    fun catalog(): DocsCatalog = DocsCatalog(ClasspathMarkdownLoader.load())
}
