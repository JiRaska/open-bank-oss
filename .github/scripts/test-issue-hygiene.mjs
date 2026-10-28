// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

// Execute the actual checkoutless workflow script, not a duplicate of its matching logic.
// Optional workflow path permits a counterfactual against the unchanged old workflow.
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

const workflow = process.argv[2] ?? fileURLToPath(new URL('../workflows/issue-hygiene.yml', import.meta.url));
const text = readFileSync(workflow, 'utf8');
const marker = '          script: |\n';
assert.equal(text.split(marker).length, 2, 'fixture expects the single actual github-script body');
const script = text.split(marker)[1].split(/\r?\n/).map(line => {
  assert.ok(line.length === 0 || line.startsWith('            '), 'unexpected script block boundary');
  return line.slice(12);
}).join('\n');
const execute = new Function('context', 'core', script);
const cases = [
  ['actual PR-preservation incident', 'does not supersede or close #12886', true],
  ['actual issue-preservation incident', 'does not close #7284', true],
  ['actual claim-to draft incident', 'does not claim to close #7284', true],
  ['claim-to contraction', "doesn’t claim to fix #42", true],
  ['claim ownership control', 'does not claim ownership and fixes #42', false],
  ['direct negation', 'not close #42', true],
  ['replace phrase', 'will not replace or resolve #42', true],
  ['contraction', "doesn't fix #42", true],
  ['curly contraction', 'can’t resolve #42', true],
  ['wrapped case', 'DOES NOT SUPERSEDE OR\nCLOSE #42', true],
  ['outside linked section', '## Linked issues\nRefs #1\n## Notes\ndoes not close #42', true],
  ['bot cannot exempt safety', 'does not close #42', true, { user: { login: 'updater[bot]' } }],
  ['skip cannot exempt safety', 'does not close #42', true, { labels: [{ name: 'skip-hygiene' }] }],
  ['release cannot exempt safety', 'does not close #42', true, { title: 'chore: release' }],
  ['literal example policy', '`does not close #42` is an unsafe example', true],
  ['ordinary close', 'Closes #42', false],
  ['ordinary fix', 'Fixes #42', false],
  ['ordinary resolve', 'Resolves #42', false],
  ['safe reference', 'Refs #42; acceptance remains separate', false],
  ['safe preservation', 'does not supersede PR #42', false],
  ['placeholder example', 'does not close #<number>\nRefs #42', false],
  ['distant negation', 'not changing runtime and fixes #42', false],
  ['sentence boundary', 'will not supersede. Closes #42', false],
  ['semicolon boundary', 'will not supersede; closes #42', false],
  ['linked issues ordinary', '## Linked issues\nCloses #42\n## Notes\nNothing else.', false],
  ['explicit N/A', 'N/A — documentation-only correction', false],
  ['empty body fails existing hygiene', '', true],
  ['wrong base fails existing hygiene', 'Refs #42', true, { base: { ref: 'feature/other' } }],
  ['bot exemption retained', '', false, { user: { login: 'updater[bot]' } }],
  ['skip exemption retained', '', false, { labels: [{ name: 'skip-hygiene' }] }],
  ['release exemption retained', '', false, { title: 'chore: release' }],
];
assert.ok(cases.length >= 31, 'at least 31 issue-hygiene workflow fixtures must run');
let failures = 0;
for (const [name, body, expectFailure, overrides = {}] of cases) {
  const errors = [];
  const pr = { body, title: 'test: example', user: { login: 'contributor' }, labels: [],
    head: { ref: 'test/example' }, base: { ref: 'main' }, ...overrides };
  execute({ payload: { pull_request: pr } }, { setFailed: message => errors.push(message), notice: () => {} });
  try {
    assert.equal(errors.length > 0, expectFailure, name);
    if (expectFailure && !['empty body fails existing hygiene', 'wrong base fails existing hygiene'].includes(name)) {
      assert.match(errors[0], /negated closing phrase/);
      assert.match(errors[0], /placeholder/);
    }
    console.log(`PASS ${name}`);
  } catch (error) {
    failures += 1;
    console.error(`FAIL ${name}: ${error.message}`);
  }
}
console.log(`${cases.length - failures}/${cases.length} workflow fixtures passed`);
console.log(`SUBJECTS=${cases.length}  # issue-hygiene fixtures only, not total governance-suite cases`);
if (failures) process.exitCode = 1;
