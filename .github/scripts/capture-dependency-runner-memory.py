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
# Counters belong to this process's cgroup, not necessarily the mount root.
# Keep group paths machine-local; publish only the resource counters.
mem.update({'memory.max': None, 'memory.peak': None, 'memory.current': None})
for line in Path('/proc/self/cgroup').read_text().splitlines():
    hierarchy, controllers, group = line.split(':', 2)
    if '..' in Path(group).parts:
        raise SystemExit('invalid cgroup membership')
    if hierarchy == '0' and not controllers:
        directory = Path('/sys/fs/cgroup') / group.lstrip('/')
        names = {'memory.max': 'memory.max', 'memory.peak': 'memory.peak',
                 'memory.current': 'memory.current'}
    elif 'memory' in controllers.split(','):
        directory = Path('/sys/fs/cgroup/memory') / group.lstrip('/')
        names = {'memory.max': 'memory.limit_in_bytes',
                 'memory.peak': 'memory.max_usage_in_bytes',
                 'memory.current': 'memory.usage_in_bytes'}
    else:
        continue
    for key, name in names.items():
        counter = directory / name
        if counter.is_file():
            mem[key] = counter.read_text().strip()
if any(mem[name] is None for name in ('memory.max', 'memory.peak', 'memory.current')):
    raise SystemExit('Required cgroup memory counters unavailable; benchmark memory is unproven')
for name in ('memory.max', 'memory.peak', 'memory.current'):
    value = mem[name]
    if not value.isdecimal() and not (name == 'memory.max' and value == 'max'):
        raise SystemExit('Invalid cgroup memory counter')
mem.update(stage=stage, sha=os.environ['GITHUB_SHA'])
out = Path(os.environ['RUNNER_TEMP']) / 'dependency-runner-memory'
out.mkdir(exist_ok=True)
(out / (stage + '.json')).write_text(json.dumps(mem, indent=2) + '\n')
print('Runner memory observation recorded: ' + stage)
