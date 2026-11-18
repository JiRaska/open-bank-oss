// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.fetchbroker.infrastructure.render

import org.apache.pdfbox.Loader
import org.apache.pdfbox.io.MemoryUsageSetting
import org.apache.pdfbox.text.PDFTextStripper
import java.time.Duration
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * PDF text extraction with every resource bounded — a fetched PDF is attacker-shaped input (a
 * decompression or page-count bomb). Decoded streams are cached in main memory up to
 * [maxDecodedBytes] (PDFBox throws past it), at most [maxPages] pages are read, the text is cut at
 * [maxChars], and the whole extraction runs on a small bounded pool under a [timeout]. Any limit
 * hit or parse failure yields null, which the renderer reports as NOT_RENDERABLE.
 */
class PdfTextExtractor(
    private val maxDecodedBytes: Long = DEFAULT_MAX_DECODED_BYTES,
    private val maxPages: Int = DEFAULT_MAX_PAGES,
    private val maxChars: Int = DEFAULT_MAX_CHARS,
    private val timeout: Duration = Duration.ofSeconds(DEFAULT_TIMEOUT_S),
) {
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    fun extract(bytes: ByteArray): String? {
        val future: Future<String> = try {
            POOL.submit<String> { run(bytes) }
        } catch (_: RejectedExecutionException) {
            return null
        }
        return try {
            future.get(timeout.toMillis(), TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            future.cancel(true)
            null
        } catch (_: ExecutionException) {
            null
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        }
    }

    private fun run(bytes: ByteArray): String {
        val cache = MemoryUsageSetting.setupMainMemoryOnly(maxDecodedBytes).streamCache
        return Loader.loadPDF(bytes, "", null, null, cache).use { doc ->
            val stripper = PDFTextStripper().apply {
                startPage = 1
                endPage = maxPages
            }
            val text = stripper.getText(doc)
            if (text.length > maxChars) text.substring(0, maxChars) else text
        }
    }

    companion object {
        const val DEFAULT_MAX_DECODED_BYTES = 32L * 1024 * 1024
        const val DEFAULT_MAX_PAGES = 200
        const val DEFAULT_MAX_CHARS = 1_000_000
        const val DEFAULT_TIMEOUT_S = 20L
        private const val THREADS = 2
        private const val QUEUE = 4

        /** Bounded: two workers, four queued; beyond that a PDF is refused rather than queued. */
        private val POOL = ThreadPoolExecutor(
            THREADS,
            THREADS,
            0L,
            TimeUnit.MILLISECONDS,
            ArrayBlockingQueue(QUEUE),
            { r -> Thread(r, "fetch-broker-pdf").apply { isDaemon = true } },
            ThreadPoolExecutor.AbortPolicy(),
        )
    }
}
