"""WOFF2 dependency compatibility and recovery; these are not a CVE reproducer.

Exercise the real FontTools/Brotli path with an in-memory synthetic font rather
than a host font or a checked-in binary. Truncation checks decoder failure and
subsequent independent font decoding, not the upstream invalid-block-switch fix.
"""

from __future__ import annotations

from io import BytesIO
from struct import pack_into, unpack_from

import brotli
import pytest
from fontTools.fontBuilder import FontBuilder
from fontTools.pens.ttGlyphPen import TTGlyphPen
from fontTools.ttLib import TTFont
from fontTools.ttLib.woff2 import WOFF2DirectoryEntry, woff2DirectorySize


@pytest.fixture
def woff2_font() -> bytes:
    builder = FontBuilder(1000, isTTF=True)
    builder.setupGlyphOrder([".notdef", "A"])
    builder.setupCharacterMap({ord("A"): "A"})

    pen = TTGlyphPen(None)
    notdef = pen.glyph()
    pen = TTGlyphPen(None)
    pen.moveTo((100, 0))
    pen.lineTo((300, 700))
    pen.lineTo((500, 0))
    pen.closePath()
    builder.setupGlyf({".notdef": notdef, "A": pen.glyph()})
    builder.setupHorizontalMetrics({".notdef": (500, 0), "A": (600, 100)})
    builder.setupHorizontalHeader(ascent=800, descent=-200)
    builder.setupNameTable(
        {
            "familyName": "Synthetic Regression",
            "styleName": "Regular",
            "uniqueFontIdentifier": "SyntheticRegression-Regular",
            "fullName": "Synthetic Regression Regular",
            "psName": "SyntheticRegression-Regular",
        }
    )
    builder.setupOS2(sTypoAscender=800, sTypoDescender=-200, usWinAscent=800, usWinDescent=200)
    builder.setupPost()
    builder.setupMaxp()
    builder.font.flavor = "woff2"
    with BytesIO() as output:
        builder.font.save(output)
        return output.getvalue()


def _assert_font_contents(data: bytes) -> None:
    with TTFont(BytesIO(data)) as font:
        assert font.flavor == "woff2"
        assert font["head"].unitsPerEm == 1000
        assert font.getBestCmap() == {ord("A"): "A"}
        assert font["hmtx"]["A"] == (600, 100)
        glyph = font["glyf"]["A"]
        assert glyph.numberOfContours == 1
        assert list(glyph.coordinates) == [(100, 0), (300, 700), (500, 0)]


def test_woff2_roundtrip_preserves_glyphs_and_metrics(woff2_font):
    _assert_font_contents(woff2_font)


@pytest.mark.parametrize("missing_bytes", [1, 10, 100])
def test_truncated_woff2_fails_in_brotli_then_valid_font_decodes(woff2_font, missing_bytes):
    # WOFF2 may end in alignment padding. Locate the compressed payload through
    # the real variable-length directory so every case truncates Brotli data.
    num_tables = unpack_from(">H", woff2_font, 12)[0]
    compressed_size = unpack_from(">I", woff2_font, 20)[0]
    with BytesIO(woff2_font) as source:
        source.seek(woff2DirectorySize)
        for _ in range(num_tables):
            WOFF2DirectoryEntry().fromFile(source)
        payload_start = source.tell()
    assert compressed_size > missing_bytes
    payload_end = payload_start + compressed_size
    assert payload_end <= len(woff2_font)
    truncated = bytearray(woff2_font[: payload_end - missing_bytes])
    # Keep outer lengths consistent, eliminating a container-length rejection as
    # an alternative explanation for failure of the incomplete Brotli stream.
    pack_into(">I", truncated, 8, len(truncated))
    pack_into(">I", truncated, 20, compressed_size - missing_bytes)
    # Requiring brotli.error proves rejection reached the native decoder rather
    # than merely failing FontTools' outer container validation.
    with pytest.raises(brotli.error):
        TTFont(BytesIO(truncated))
    _assert_font_contents(woff2_font)
