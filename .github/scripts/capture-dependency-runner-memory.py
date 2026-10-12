# SPDX-License-Identifier: Apache-2.0
"""Record public resource counters for the isolated dependency benchmark."""
import json
import os
from pathlib import Path
import sys

stage = sys.argv[1]
if stage not in ('before', 'after'):
    raise SystemExit('invalid memory observation stage')
mem = {}
for line in Path('/proc/meminfo').read_text().splitlines():
    key, value = line.split(':', 1)
    if key in ('MemTotal', 'MemAvailable'):
        mem[key + '_kib'] = int(value.split()[0])
if len(mem) != 2:
    raise SystemExit('runner memory budget unavailable')
for name in ('memory.max', 'memory.peak', 'memory.current'):
    path = Path('/sys/fs/cgroup') / name
    mem[name] = path.read_text().strip() if path.exists() else None
mem.update(stage=stage, sha=os.environ['GITHUB_SHA'])
out = Path(os.environ['RUNNER_TEMP']) / 'dependency-runner-memory'
out.mkdir(exist_ok=True)
(out / (stage + '.json')).write_text(json.dumps(mem, indent=2) + '\n')
print('Runner memory observation recorded: ' + stage)
