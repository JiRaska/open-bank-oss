// SPDX-License-Identifier: Apache-2.0
import { z } from 'zod'
import type { PlatformVersions } from '../platform-versions'

const text = z.string().trim().min(1).max(1024)
const name = text.regex(/^[a-zA-Z0-9_.-]+$/)
const path = text.refine(value => !value.startsWith('/') && !value.split('/').includes('..') && !/[\x00-\x1f\\]/.test(value))
const count = z.number().int().nonnegative().safe()
const strings = z.array(name).max(1000)
const date = z.string().regex(/^\d{4}-\d\d-\d\d$/).refine(value => Number.isFinite(Date.parse(value)))
const pin = z.object({ version: text, source: path, pinnedIn: count.optional(), otherPins: z.record(text, z.array(path).max(1000)).optional() }).strict().nullable()
const source = z.discriminatedUnion('kind', [
  z.object({ kind: z.literal('helm-chart'), chart: name }).strict(),
  z.object({ kind: z.literal('manifest-path'), path }).strict(),
  z.object({ kind: z.literal('unknown') }).strict(),
])
const platform = z.object({
  schema: z.literal('openbank.platform-versions/v1'),
  kubernetesVersion: text,
  nodeGroup: z.object({ instanceType: name, desiredSize: count, minSize: count, maxSize: count }).strict()
    .refine(value => value.minSize <= value.desiredSize && value.desiredSize <= value.maxSize),
  loki: z.object({ chartVersion: text, appVersion: text }).strict(),
  eksLifecycle: z.object({
    _meta: z.object({ last_refreshed: date, pricing_note: text.optional() }).passthrough(),
    versions: z.record(text, z.object({ eks_release: date, end_of_standard_support: date, end_of_extended_support: date }).strict()),
  }).strict(),
  inventory: z.object({
    gitops: z.object({
      apps: strings, components: strings, clickhouse: z.boolean(), istio: z.boolean(), cilium: z.boolean(),
      applications: z.array(z.object({
        kind: z.enum(['application', 'unknown']), name: name.nullable(), namespace: name.nullable(), sourceFile: path,
        sources: z.array(source).min(1).max(100), workloadMapping: z.literal('unknown'),
      }).strict()).max(1000).optional(),
    }).strict(),
    iac: z.object({
      route53: strings, cloudfront: strings, acm: strings, alb: strings, cloudtrail: strings, awsConfig: strings, objectLock: strings,
      helmReleases: strings.optional(),
      helmReleaseDeclarations: z.array(z.object({ environment: name, sourceFile: path, address: text, instanceExpansion: z.literal('unknown') }).strict()).max(2000).optional(),
      moduleTraversalGaps: z.array(z.object({ environment: name, modulePath: z.union([z.literal(''), path]), reason: z.enum(['cycle', 'unresolved-local-module', 'external-or-dynamic-source']) }).strict()).max(2000).optional(),
    }).strict(),
  }).strict(),
  components: z.object({ postgres: pin, valkey: pin, apicurio: pin, kafka: pin, strimziOperator: pin }).strict(),
  sources: z.object({ kubernetesVersion: path, nodeGroup: path, loki: path, eksLifecycle: path }).strict(),
}).strict()

/** Repository declarations only. This validates no runtime health or observed versions. */
export function isPlatformDeclarations(value: unknown): value is PlatformVersions {
  return platform.safeParse(value).success
}
