// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

function escapeAttribute(value: string): string {
  return value
    .replace(/&/g, '&amp;')
    .replace(/"/g, '&quot;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
}

/** Build a full-window shell without promoting preview HTML to a top-level document. */
export function sandboxedPreviewDocument(previewHtml: string, title: string): string {
  return `<!doctype html>
<html>
  <head>
    <meta charset="utf-8">
    <meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'; img-src data:; font-src data:">
    <title>${escapeAttribute(title)}</title>
    <style>html,body,iframe{width:100%;height:100%;margin:0;border:0;background:#fff}</style>
  </head>
  <body><iframe sandbox="" title="${escapeAttribute(title)}" srcdoc="${escapeAttribute(previewHtml)}"></iframe></body>
</html>`
}
