// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Shared SVG <defs> primitives for topology pages — a soft node drop-shadow and a
// small arrowhead marker. Render inside a page's own <defs>; each page supplies its
// own ids/colours so several markers can coexist.

export function NodeShadow({ id }: { id: string }) {
  return (
    <filter id={id} x="-40%" y="-40%" width="180%" height="180%">
      <feDropShadow dx="0" dy="1.5" stdDeviation="2.5" floodColor="#0f172a" floodOpacity="0.14" />
    </filter>
  )
}

export function ArrowMarker({ id, color }: { id: string; color: string }) {
  return (
    <marker id={id} markerWidth="7" markerHeight="7" refX="5.5" refY="3" orient="auto">
      <path d="M0,0 L0,6 L7,3 z" fill={color} />
    </marker>
  )
}
