// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.
package com.openbank.copilot.application.port.out

import com.openbank.copilot.application.HelpKnowledgeBase

/**
 * What the vector indexer consumes: the corpus, already chunked and keyed.
 *
 * A one-method port rather than a direct dependency on [HelpKnowledgeBase] so the indexer's
 * refusals — never re-embed an unchanged corpus, never prune against an empty one — are testable
 * against a corpus the test controls, instead of against whatever markdown happens to be bundled.
 * A test that can only run against the real corpus cannot construct the empty case, which is the
 * case that matters most.
 */
fun interface CorpusSource {
    fun chunks(): List<HelpKnowledgeBase.Chunk>
}
