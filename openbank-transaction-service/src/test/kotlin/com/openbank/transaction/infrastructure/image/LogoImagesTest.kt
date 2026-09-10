// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.transaction.infrastructure.image

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/**
 * These assert what [LogoImages] REFUSES, not what it accepts.
 *
 * The accept path is trivially visible — an image goes in, two PNGs come out — and would pass just
 * as happily if every guard were deleted. What makes re-encoding a security control rather than a
 * resize is that an SVG, a polyglot and a decompression bomb do not get through it, so each of
 * those is a test that fails the moment the corresponding guard stops working.
 */
class LogoImagesTest {

    private fun png(width: Int, height: Int, colour: Color = Color.RED): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        g.color = colour
        g.fillRect(0, 0, width, height)
        g.dispose()
        val out = ByteArrayOutputStream()
        ImageIO.write(image, "png", out)
        return out.toByteArray()
    }

    @Test
    fun `an SVG is refused, because a logo a client renders must not be able to carry script`() {
        val svg = """<svg xmlns="http://www.w3.org/2000/svg"><script>alert(1)</script></svg>"""
            .toByteArray(Charsets.UTF_8)

        assertThatThrownBy { LogoImages.render(svg) }
            .isInstanceOf(LogoImages.RejectedException::class.java)
            .hasMessageContaining("not a PNG, JPEG or GIF")
    }

    @Test
    fun `an HTML file named as an image is refused`() {
        val html = "<html><body><img src=x onerror=alert(1)></body></html>".toByteArray(Charsets.UTF_8)

        assertThatThrownBy { LogoImages.render(html) }
            .isInstanceOf(LogoImages.RejectedException::class.java)
    }

    /**
     * A polyglot: a real PNG with a payload appended after the image data. It decodes fine, which
     * is exactly why storing the upload verbatim would serve the payload along with the logo.
     */
    @Test
    fun `bytes appended after a valid image do not survive re-encoding`() {
        val payload = "<script>alert('polyglot')</script>".toByteArray(Charsets.UTF_8)
        val polyglot = png(64, 64) + payload

        val rendered = LogoImages.render(polyglot)

        assertThat(String(rendered.large, Charsets.ISO_8859_1)).doesNotContain("polyglot")
        assertThat(String(rendered.small, Charsets.ISO_8859_1)).doesNotContain("polyglot")
    }

    /**
     * The decompression-bomb guard. A header declaring a huge canvas is refused BEFORE the pixels
     * are allocated — decoding one would be gigabytes of `int[]` and an out-of-memory kill of the
     * pod, which is a denial of service rather than a rejected request.
     */
    @Test
    fun `an image declaring implausible dimensions is refused without being decoded`() {
        val oversized = png(LogoImages.MAX_SOURCE_PIXELS + 1, 8)

        assertThatThrownBy { LogoImages.render(oversized) }
            .isInstanceOf(LogoImages.RejectedException::class.java)
            .hasMessageContaining("the limit is")
    }

    @Test
    fun `an upload over the byte limit is refused`() {
        val tooBig = png(8, 8) + ByteArray(LogoImages.MAX_UPLOAD_BYTES)

        assertThatThrownBy { LogoImages.render(tooBig) }
            .isInstanceOf(LogoImages.RejectedException::class.java)
            .hasMessageContaining("bytes; the limit is")
    }

    @Test
    fun `an empty upload is refused`() {
        assertThatThrownBy { LogoImages.render(ByteArray(0)) }
            .isInstanceOf(LogoImages.RejectedException::class.java)
    }

    @Test
    fun `a truncated PNG is refused rather than stored half-decoded`() {
        val truncated = png(64, 64).copyOfRange(0, 40)

        assertThatThrownBy { LogoImages.render(truncated) }
            .isInstanceOf(LogoImages.RejectedException::class.java)
    }

    @Test
    fun `both variants are produced at the sizes clients request`() {
        val rendered = LogoImages.render(png(200, 200))

        val small = ImageIO.read(rendered.small.inputStream())
        val large = ImageIO.read(rendered.large.inputStream())
        assertThat(small.width).isEqualTo(LogoImages.SIZE_SMALL)
        assertThat(small.height).isEqualTo(LogoImages.SIZE_SMALL)
        assertThat(large.width).isEqualTo(LogoImages.SIZE_LARGE)
        assertThat(large.height).isEqualTo(LogoImages.SIZE_LARGE)
    }

    /**
     * A wide wordmark keeps its proportions and is centred in a transparent square. Stretching it
     * to fill would misrepresent a trademark, and cropping would cut it in half.
     */
    @Test
    fun `a non-square source is fitted, not stretched, and padded transparently`() {
        val rendered = LogoImages.render(png(400, 100))

        val large = ImageIO.read(rendered.large.inputStream())
        assertThat(large.width).isEqualTo(LogoImages.SIZE_LARGE)
        assertThat(large.height).isEqualTo(LogoImages.SIZE_LARGE)
        // The source is 4:1, so the drawn band is a quarter of the canvas high and the corners are
        // padding. Alpha 0 there proves the padding is transparent rather than black or white.
        assertThat(Color(large.getRGB(2, 2), true).alpha).isZero()
        assertThat(Color(large.getRGB(LogoImages.SIZE_LARGE / 2, LogoImages.SIZE_LARGE / 2), true).alpha)
            .isEqualTo(255)
    }

    /**
     * The hash is the ETag and the cache-busting token in the URL a client follows, so identical
     * pixels must hash identically (or every deploy would look like a changed logo) and different
     * pixels must not (or a corrected logo would never reach anyone).
     */
    @Test
    fun `the content hash is stable for the same pixels and differs for different ones`() {
        val first = LogoImages.render(png(120, 120, Color.RED))
        val same = LogoImages.render(png(120, 120, Color.RED))
        val other = LogoImages.render(png(120, 120, Color.BLUE))

        assertThat(first.contentHash).isEqualTo(same.contentHash)
        assertThat(first.contentHash).isNotEqualTo(other.contentHash)
        assertThat(first.contentHash).hasSize(64)
    }
}
