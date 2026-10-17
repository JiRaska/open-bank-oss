#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Keep evaluated threshold breaches advisory, never runtime/configuration errors."""
import argparse
import json
import os
import re
import subprocess
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--definition', required=True)
    parser.add_argument('--summary', required=True)
    parser.add_argument('--check-config', action='store_true')
    parser.add_argument('--required-fixtures', default='')
    parser.add_argument('--pass-env', action='append', default=[])
    parser.add_argument('--target-env', action='append', default=[])
    args = parser.parse_args()
    fixtures = args.required_fixtures.split()
    targets = args.target_env or ['BASE_URL']
    names = list(dict.fromkeys([*targets, 'PERF_READ_TOKEN', *fixtures, *args.pass_env]))
    if any(not re.fullmatch(r'[A-Z][A-Z0-9_]*', name) for name in names):
        parser.error('environment names must be literal uppercase identifiers')
    missing = [name for name in [*targets, 'PERF_READ_TOKEN', *fixtures] if not os.environ.get(name, '').strip()]
    if args.check_config:
        reason = 'Missing authenticated target or required seeded fixture configuration: ' + ', '.join(missing)
        output = f'run={str(not missing).lower()}\n'
        if missing:
            output += f'reason={reason}\n'
            print(f'::notice::{reason}; baseline not run.')
        with open(os.environ['GITHUB_OUTPUT'], 'a') as stream:
            stream.write(output)
        return 0
    if missing:
        print('::error::Required baseline configuration disappeared after resolution; baseline not run.')
        return 1
    summary = Path(args.summary)
    # Do not classify a retained result from an earlier execution as this run's evidence.
    summary.unlink(missing_ok=True)
    command = ['docker', 'run', '--rm', '--user', f'{os.getuid()}:{os.getgid()}']
    for name in names:
        command += ['--env', name]
    command += ['--volume', f'{Path.cwd()}:/work', '--workdir', '/work', os.environ['K6_IMAGE'],
                'run', '--summary-export', args.summary, args.definition]
    result = subprocess.run(command, check=False)
    if result.returncode not in (0, 99):
        print('::error::k6 runtime failed; this is not an advisory threshold breach.')
        return 1
    try:
        document = json.loads(summary.read_text())
        metrics = document['metrics']
        thresholds = [value for metric in metrics.values()
                      for value in metric.get('thresholds', {}).values()]
        if not thresholds or any(not isinstance(value, bool) for value in thresholds):
            raise ValueError('missing or invalid evaluated thresholds')
    except (OSError, ValueError, KeyError, TypeError, AttributeError):
        print('::error::k6 did not produce a valid evaluated-threshold summary.')
        return 1
    breaches = sum(thresholds)
    if result.returncode == 99 and not breaches:
        print('::error::k6 exit 99 lacks a threshold breach; refusing an unexplained failure.')
        return 1
    if breaches:
        print(f'::warning::{breaches} advisory threshold breach(s); retained in the performance evidence.')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
