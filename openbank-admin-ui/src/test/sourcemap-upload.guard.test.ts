// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// #3235: GlitchTip captured admin-ui errors and could not name a line — every event stored a
// minified title and an empty culprit, which is the information the reporter already had.
//
// The fix has three parts, and each one is silent when it regresses:
//
//   1. the build runs WEBPACK. Turbopack emits no client source maps at all (measured on Next
//      16.2.12: 0 `.map` files in `.next/static` against 1056 in `.next/server`), and
//      `productionBrowserSourceMaps` is read only by `next/dist/build/webpack*`. Dropping
//      `--webpack` therefore does not fail anything — it just quietly removes the input to the
//      upload, and the console goes back to unattributable frames.
//   2. `productionBrowserSourceMaps` stays OFF. It forces `devtool: 'source-map'`, which appends
//      a `//# sourceMappingURL=` comment to every client chunk (198 of them, measured). Sentry's
//      own default is `hidden-source-map`: same maps on disk for the upload, no pointer in the
//      served bundle.
//   3. the image never carries a client `.map`. The Sentry plugin deletes them after upload, but
//      that is an option on a plugin that may not run; the Dockerfile prunes them again so a
//      plugin regression cannot become a full source disclosure of the operator console.
//
// This guard asserts the CONSTRUCTS, not the presence of strings in the files — the comments in
// next.config.mjs and the Dockerfile name every one of these settings while explaining them, so a
// whole-file grep would pass on a tree where the setting itself had been deleted.

import { describe, expect, it } from 'vitest'
import { readFileSync } from 'fs'
import { join } from 'path'

const UI_ROOT = process.cwd()

/** Strip `#` comment lines from a Dockerfile so a rule is never satisfied by prose about it. */
function stripDockerComments(src: string): string {
  return src
    .split('\n')
    .filter((line) => !/^\s*#/.test(line))
    .join('\n')
}

/** Strip `//` line comments from JS so a rule is never satisfied by prose about it. */
function stripJsLineComments(src: string): string {
  return src
    .split('\n')
    .filter((line) => !/^\s*\/\//.test(line))
    .join('\n')
}

describe('client source maps reach GlitchTip and never the browser (#3235)', () => {
  it('builds with webpack — the only bundler that emits client source maps', () => {
    const pkg = JSON.parse(readFileSync(join(UI_ROOT, 'package.json'), 'utf8'))
    expect(pkg.scripts.build).toContain('--webpack')
  })

  it('does not set productionBrowserSourceMaps, which would publish a sourceMappingURL', () => {
    const config = stripJsLineComments(readFileSync(join(UI_ROOT, 'next.config.mjs'), 'utf8'))
    expect(config).not.toMatch(/productionBrowserSourceMaps\s*:/)
  })

  it('deletes uploaded source maps rather than shipping them', () => {
    const config = stripJsLineComments(readFileSync(join(UI_ROOT, 'next.config.mjs'), 'utf8'))
    expect(config).toMatch(/deleteSourcemapsAfterUpload\s*:\s*true/)
  })

  it('does not ask GlitchTip to create a release — that path is not allow-listed at the edge', () => {
    const config = stripJsLineComments(readFileSync(join(UI_ROOT, 'next.config.mjs'), 'utf8'))
    expect(config).toMatch(/create\s*:\s*false/)
  })

  it('uploads to the self-hosted GlitchTip, never sentry.io, and sends no telemetry', () => {
    const config = stripJsLineComments(readFileSync(join(UI_ROOT, 'next.config.mjs'), 'utf8'))
    expect(config).toMatch(/sentryUrl:\s*'https:\/\/glitchtip\.open-bank\.tech'/)
    expect(config).toMatch(/telemetry\s*:\s*false/)
  })

  it('prunes every client .map out of the image after the build', () => {
    const dockerfile = stripDockerComments(readFileSync(join(UI_ROOT, 'Dockerfile'), 'utf8'))
    // The prune must run in the build stage, i.e. before .next/static is copied to runtime.
    const pruneIndex = dockerfile.indexOf(".next/static -name '*.map' -type f -delete")
    const copyIndex = dockerfile.indexOf('COPY --from=build /app/.next/static')
    expect(pruneIndex).toBeGreaterThan(-1)
    expect(copyIndex).toBeGreaterThan(-1)
    expect(pruneIndex).toBeLessThan(copyIndex)
  })
})
