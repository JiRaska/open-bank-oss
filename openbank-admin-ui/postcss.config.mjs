// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Tailwind v4 moved the PostCSS plugin into its own package (@tailwindcss/postcss);
// v3 wired tailwind through Next.js's built-in detection with no PostCSS config at all.
// v4 requires the plugin to be declared explicitly.
export default {
  plugins: {
    '@tailwindcss/postcss': {},
  },
}
