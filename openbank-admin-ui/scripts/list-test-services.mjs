#!/usr/bin/env node
// SPDX-License-Identifier: Apache-2.0

import path from 'path'
import { fileURLToPath } from 'url'
import { releasedJvmServices } from './lib/service-inventory.mjs'

const here = path.dirname(fileURLToPath(import.meta.url))
const repoArg = process.argv.indexOf('--repo')
const repo = path.resolve(repoArg >= 0 ? process.argv[repoArg + 1] : path.join(here, '..', '..'))
process.stdout.write(`${releasedJvmServices(repo).join('\n')}\n`)
