# Bundled fonts

`DejaVuSans.ttf` and `DejaVuSans-Bold.ttf` are embedded (subset) into the visible
signature block of signed PDFs (see `PadesSigning.stampSignatureBlock`). They replace
the Standard-14 Helvetica base font, which is WinAnsiEncoding (CP1252) and cannot encode
Czech diacritics such as U+0159 (ř) — that gap failed the entire signing ceremony for
most Czech customer names.

DejaVu Fonts are distributed under a permissive Bitstream Vera / Arev license (free to
use, embed and redistribute, including in commercial and public projects). Full text:
https://dejavu-fonts.github.io/License.html
