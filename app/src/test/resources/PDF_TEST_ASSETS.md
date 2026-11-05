# PDF test assets

These files are used only by local unit tests. Tests do not access the network.

## `pdfs/basicapi-3pages.pdf`

- Source: Mozilla PDF.js, `test/pdfs/basicapi.pdf`
- URL: https://github.com/mozilla/pdf.js/blob/master/test/pdfs/basicapi.pdf
- SHA-256: `925853d98d67d5dae7473c635f932958e1695ce1029d23b2ecd2531cb65f1f14`
- Pages: 3; text-only, no personal or sensitive content.
- License: Apache License 2.0, see `pdfs/LICENSE-pdfjs.txt`.

## `pdf-assets/NotoSansSC-TestSubset.ttf`

- Source font: Noto Sans SC variable TTF, https://github.com/google/fonts/tree/main/ofl/notosanssc
- Local transformation: subset to ASCII plus the Chinese characters used by
  `PdfTextExtractorTest`, instanced at weight 400, and converted to a TrueType
  (`glyf`) subset accepted by PDFBox-Android; the original font is not committed.
- SHA-256: `4909ffc370038713e626ea36ce839c1fd6a37d636f128b259272cdac98eb4f3b`
- License: SIL Open Font License 1.1, see `pdf-assets/OFL-NotoSansCJK.txt`.
