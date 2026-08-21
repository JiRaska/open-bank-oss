// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { NodeSDK } from '@opentelemetry/sdk-node'
import { OTLPTraceExporter } from '@opentelemetry/exporter-trace-otlp-proto'
import { UndiciInstrumentation } from '@opentelemetry/instrumentation-undici'
import { HttpInstrumentation } from '@opentelemetry/instrumentation-http'
import { resourceFromAttributes } from '@opentelemetry/resources'
import { ATTR_SERVICE_NAME } from '@opentelemetry/semantic-conventions'

/**
 * Keeps the OpenTelemetry packages inside the Next.js standalone output.
 *
 * **This module starts nothing.** The SDK is started by `otel-bootstrap.cjs`, preloaded through
 * `NODE_OPTIONS=--require`, because `@opentelemetry/instrumentation-http` has to patch
 * `node:http` before the server loads it and `instrumentation.ts` runs too late for that — see
 * that file for the measurement.
 *
 * What the preload cannot do is put the packages in the image. Next.js copies a dependency into
 * `.next/standalone/node_modules` only when its file tracing sees an application module import
 * it, and a `--require` preload is invisible to that analysis. So these imports are the thing
 * that makes the bootstrap resolvable at runtime, and they must be real references rather than
 * bare side-effect imports, which the bundler is free to drop.
 *
 * The failure mode if this drops out is a container that crashes on `--require` with
 * MODULE_NOT_FOUND. `bff-tracing.test.ts` asserts against the built standalone output when one
 * is present, so a local `NEXT_STANDALONE=true npm run build` catches it; there is no cheap
 * assertion that catches it without a build.
 */
export function otelPackagesBundled(): string[] {
  return [
    NodeSDK.name,
    OTLPTraceExporter.name,
    UndiciInstrumentation.name,
    HttpInstrumentation.name,
    resourceFromAttributes.name,
    ATTR_SERVICE_NAME,
  ]
}
