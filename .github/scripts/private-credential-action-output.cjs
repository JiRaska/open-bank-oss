// SPDX-License-Identifier: Apache-2.0
// Preload only for the pinned credential action. Preserve masking commands and
// GitHub environment/state files; keep every diagnostic off the public stream.
'use strict';
const fs = require('node:fs');
const path = require('node:path');
const originalWrite = process.stdout.write.bind(process.stdout);
let fd;
try {
  for (const name of ['RUNNER_TEMP', 'GITHUB_ENV', 'GITHUB_STATE', 'GITHUB_OUTPUT']) {
    if (!process.env[name]) throw new Error('missing runner control file');
  }
  const dir = fs.mkdtempSync(path.join(process.env.RUNNER_TEMP, 'private-credential-action-'));
  fs.chmodSync(dir, 0o700);
  fd = fs.openSync(path.join(dir, 'diagnostics.log'), 'wx', 0o600);
} catch {
  originalWrite('::error::Private credential output capture could not start.\n');
  process.exit(1);
}
const pending = { stdout: Buffer.alloc(0), stderr: Buffer.alloc(0) };
function accept(line, complete) {
  if (line.subarray(0, 12).toString('ascii') === '::add-mask::') {
    // Never retain masking-command payloads: they can contain actual credentials.
    if (!complete) throw new Error('incomplete credential masking command');
    return originalWrite(line);
  }
  if (/^::(?:add-mask|save-state|set-output|add-path|set-env)(?: |::)/.test(line.toString('ascii'))) {
    throw new Error('unsupported credential control protocol');
  }
  fs.writeSync(fd, line);
  return true;
}
function capture(channel, chunk, encoding, callback) {
  if (typeof encoding === 'function') { callback = encoding; encoding = undefined; }
  const bytes = Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk, encoding || 'utf8');
  pending[channel] = Buffer.concat([pending[channel], bytes]);
  let accepted = true;
  while (pending[channel].length) {
    const newline = pending[channel].indexOf(10);
    const size = newline < 0 ? pending[channel].length : newline + 1;
    if (size > 65536) throw new Error('credential output line exceeds capture limit');
    if (newline < 0) break;
    const line = pending[channel].subarray(0, size);
    pending[channel] = pending[channel].subarray(size);
    accepted = accept(line, true) && accepted;
  }
  if (callback) queueMicrotask(callback);
  return accepted;
}
process.stdout.write = (...args) => capture('stdout', ...args);
process.stderr.write = (...args) => capture('stderr', ...args);
process.on('exit', (code) => {
  try {
    for (const buffer of Object.values(pending)) if (buffer.length) accept(buffer, false);
    fs.closeSync(fd);
  } catch {
    process.exitCode = 1;
  }
  originalWrite(code === 0 && !process.exitCode
    ? 'Credential action completed; diagnostics are private.\n'
    : '::error::Credential action failed; diagnostics are private.\n');
});
