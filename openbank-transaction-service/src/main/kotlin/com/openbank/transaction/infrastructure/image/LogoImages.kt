// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.transaction.infrastructure.image

import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.HexFormat
import javax.imageio.ImageIO
import javax.imageio.stream.MemoryCacheImageInputStream

/**
 * Turns an uploaded logo into the two square PNGs this service serves, or refuses it.
 *
 * **Re-encoding is the security control, not a convenience.** Bytes an operator supplies are
 * rendered later inside a banking app next to a real transaction, so passing them through
 * untouched would mean serving whatever was uploaded: an SVG (script-bearing), an HTML polyglot, a
 * file with a PNG header and a payload after `IEND`, or an image carrying EXIF GPS from whoever
 * photographed it. Decoding to pixels and writing a fresh PNG keeps only what an image actually
 * is — a grid of colours — and drops every byte that was not part of it.
 *
 * **Dimensions are checked before the pixels are allocated.** A 40 kB PNG can declare
 * 30000x30000, which is 3.6 GB of `int[]` the moment it is decoded — the classic decompression
 * bomb, and an out-of-memory kill of the whole pod rather than a rejected request. The header is
 * read on its own first, so an oversized declaration is a 400 that allocates nothing.
 *
 * Deliberately not in `domain`: this is I/O-shaped work over an image codec, and the domain layer
 * here holds no framework or platform machinery (ADR-0002).
 */
object LogoImages {
    /** The rendered variants, keyed by the pixel size a client asks for. */
    const val SIZE_SMALL = 64
    const val SIZE_LARGE = 128

    /** Largest upload accepted. A brand logo that needs more than this is the wrong asset. */
    const val MAX_UPLOAD_BYTES = 512 * 1024

    /** Largest source dimension accepted, checked from the header before any decode. */
    const val MAX_SOURCE_PIXELS = 4096

    /** What a browser is told these bytes are, and what [render] always produces. */
    const val CONTENT_TYPE = "image/png"

    // Written as hex strings rather than byte literals so the signatures stay readable against the
    // format specifications they come from.
    private val PNG_MAGIC = HexFormat.of().parseHex("89504E470D0A1A0A")
    private val JPEG_MAGIC = HexFormat.of().parseHex("FFD8FF")
    private val GIF_MAGIC = HexFormat.of().parseHex("47494638")

    /** A rejected upload and why, in a sentence an operator can act on. */
    class RejectedException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)

    /** The two variants plus the hash that identifies them. */
    data class Rendered(val small: ByteArray, val large: ByteArray, val contentHash: String) {
        // Data class over ByteArray: the generated equals/hashCode compare by reference, which is
        // never what a caller means. Both are defined over contentHash, which identifies the
        // pixels exactly and is what every other part of the system compares by.
        override fun equals(other: Any?): Boolean = other is Rendered && other.contentHash == contentHash

        override fun hashCode(): Int = contentHash.hashCode()
    }

    /**
     * Decode [upload], scale it into both variants, and re-encode as PNG.
     *
     * @throws RejectedException when the upload is too large, is not one of the accepted raster
     *   formats, declares implausible dimensions, or cannot be decoded as an image at all.
     */
    // Each throw below is a distinct, separately actionable rejection reason, and collapsing them
    // into one would tell an operator only that the upload was refused.
    @Suppress("ThrowsCount")
    fun render(upload: ByteArray): Rendered {
        if (upload.isEmpty()) throw RejectedException("logo upload is empty")
        if (upload.size > MAX_UPLOAD_BYTES) {
            throw RejectedException("logo upload is ${upload.size} bytes; the limit is $MAX_UPLOAD_BYTES")
        }
        // Sniff the real format rather than trusting a declared content type. SVG is refused here
        // by construction: it is not a raster format, so it matches no magic number, and its
        // scripting is why it must never reach a client.
        if (!startsWith(upload, PNG_MAGIC) && !startsWith(upload, JPEG_MAGIC) && !startsWith(upload, GIF_MAGIC)) {
            throw RejectedException("logo upload is not a PNG, JPEG or GIF image")
        }
        requirePlausibleDimensions(upload)

        // A malformed or truncated file fails inside the codec as an IIOException. Left to
        // propagate it is a 500 for what is plainly a bad upload, so it is translated here — the
        // same reason the header check above translates its own IOException.
        val source = try {
            ImageIO.read(upload.inputStream())
        } catch (e: java.io.IOException) {
            throw RejectedException("logo upload could not be decoded as an image: ${e.message}", e)
        } ?: throw RejectedException("logo upload could not be decoded as an image")

        val large = encodePng(square(source, SIZE_LARGE))
        val small = encodePng(square(source, SIZE_SMALL))
        return Rendered(small = small, large = large, contentHash = sha256Hex(large))
    }

    /** Lower-case hex SHA-256, the form used for both the ETag and the URL cache-busting token. */
    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /**
     * Reads just the image header to learn the declared dimensions, and refuses an implausible one.
     *
     * `getWidth`/`getHeight` on a reader parse the header only — no pixel buffer is allocated —
     * which is the entire point: the check has to happen before the decode it is protecting.
     */
    @Suppress("ThrowsCount")
    private fun requirePlausibleDimensions(upload: ByteArray) {
        MemoryCacheImageInputStream(upload.inputStream()).use { stream ->
            val readers = ImageIO.getImageReaders(stream)
            if (!readers.hasNext()) throw RejectedException("no image decoder recognises this upload")
            val reader = readers.next()
            try {
                reader.setInput(stream, true, true)
                val width = reader.getWidth(0)
                val height = reader.getHeight(0)
                if (width <= 0 || height <= 0) throw RejectedException("image declares a zero dimension")
                if (width > MAX_SOURCE_PIXELS || height > MAX_SOURCE_PIXELS) {
                    throw RejectedException(
                        "image declares ${width}x$height; the limit is " +
                            "${MAX_SOURCE_PIXELS}x$MAX_SOURCE_PIXELS",
                    )
                }
            } catch (e: java.io.IOException) {
                throw RejectedException("image header could not be read: ${e.message}", e)
            } finally {
                reader.dispose()
            }
        }
    }

    /**
     * Fits [source] inside a transparent [size]x[size] canvas, preserving its aspect ratio.
     *
     * Fitting rather than cropping or stretching: a logo is a trademark, and a client renders these
     * at icon size where a squashed or clipped wordmark is both ugly and, for the merchant, wrong.
     * The padding is transparent so the icon sits on whatever background the app uses, in either
     * theme.
     */
    private fun square(source: BufferedImage, size: Int): BufferedImage {
        val scale = minOf(size.toDouble() / source.width, size.toDouble() / source.height)
        val width = maxOf(1, Math.round(source.width * scale).toInt())
        val height = maxOf(1, Math.round(source.height * scale).toInt())
        val canvas = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val g = canvas.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.drawImage(source, (size - width) / 2, (size - height) / 2, width, height, null)
        } finally {
            g.dispose()
        }
        return canvas
    }

    private fun encodePng(image: BufferedImage): ByteArray {
        val out = ByteArrayOutputStream()
        if (!ImageIO.write(image, "png", out)) throw RejectedException("PNG encoder is unavailable")
        return out.toByteArray()
    }

    private fun startsWith(bytes: ByteArray, magic: ByteArray): Boolean =
        bytes.size >= magic.size && magic.indices.all { bytes[it] == magic[it] }
}
