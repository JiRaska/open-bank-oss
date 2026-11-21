#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
import argparse
import datetime
from decimal import Decimal
import importlib.util
import hashlib
import io
import json
import tarfile
from pathlib import Path
import subprocess
import time

parser = argparse.ArgumentParser(description="Run synthetic Customer Edge load with bounded durable reporting.")
parser.add_argument("output", type=Path)
parser.add_argument("--auth-fixture", type=Path, required=True)
parser.add_argument("--policy-cpus", help="Experimental CPU affinity for local OPA instances")
parser.add_argument("--remote-namespace", default="regulatory-perf-16356")
parser.add_argument("--remote-context", help="Use an existing isolated worker pod in this test context")
parser.add_argument("--device", help="Reporting volume backing block device in Docker VM")
args = parser.parse_args()
if not args.remote_context and not args.device:
    parser.error("--device is required for a local reporting worker")
repo = Path(__file__).resolve().parents[2]
root = args.output.resolve()
root.mkdir(mode=0o700, parents=True, exist_ok=False)
auth = args.auth_fixture.resolve()
report = 'ob16356-report-durable'
volume = 'ob16356-report-durable-data'
load_name = 'ob16356-k6-durable'
bank_cpus = '0-7' if args.policy_cpus else '0-9'
policy_cpus = args.policy_cpus or bank_cpus
report_cpus = '10-11' if not args.remote_context else None
namespace = args.remote_namespace
remote_pod = 'reporting-worker'
kube = ['kubectl', '--context', args.remote_context, '-n', namespace] if args.remote_context else None
services = ('domestic-payment', 'opa-domestic', 'balance-service', 'opa-balance',
            'account-service', 'opa-account', 'party-service', 'opa-party',
            'edge', 'opa-edge', 'broker', 'cache', 'auth', 'db')
def stamp():
    return datetime.datetime.now(datetime.timezone.utc).isoformat()
phases = {}
stats = {}
(root/'workload.json').write_text(json.dumps({
    "syntheticClients": 3_000_000,
    "activeSyntheticIdentities": len(json.loads((auth/'customers.json').read_text())),
    "readRequestsPerSecondPerApi": 30,
    "virtualUsersPerApi": 36,
    "reportingJournalLines": 6_000_000,
    "replayLinesPerSecond": 50_000,
    "bankCpuSet": bank_cpus,
    "policyCpuSet": policy_cpus,
    "reportingCpuSet": report_cpus,
    "reportingPlacement": "remote-kubernetes-pod" if kube else "local-docker-worker",
    "reportingReadBytesPerSecond": None if kube else 10_485_760,
    "reportingWriteBytesPerSecond": None if kube else 10_485_760,
    "outageSeconds": 45,
    "opaEnforced": True,
}, indent=2))

def docker(*args):
    return subprocess.check_output(['docker', *args], text=True).strip()

def report_command(*command):
    if kube:
        return [*kube, 'exec', remote_pod, '--', *command]
    return ['docker', 'exec', report, *command]


def report_exec(*command):
    return subprocess.check_output(report_command(*command), text=True).strip()


def remove_worker():
    if kube:
        subprocess.run([*kube, 'delete', 'pod', remote_pod, '--wait=true', '--timeout=60s'], check=True,
                       stdout=subprocess.DEVNULL)
    else:
        docker('rm', '-f', report)


def snapshot(name):
    keys = ('cpuset.cpus.effective', 'cpu.max', 'cpu.stat', 'io.stat', 'io.max', 'memory.max',
            'memory.peak', 'memory.swap.max', 'memory.swap.current', 'memory.events')
    read = report_exec('sh', '-ec',
                  'for key do echo "===$key"; '
                  'if test -r "/sys/fs/cgroup/$key"; then cat "/sys/fs/cgroup/$key"; '
                  'else echo unavailable; fi; done', 'sh', *keys)
    stats[name] = {part.splitlines()[0]: '\n'.join(part.splitlines()[1:]) for part in read.split('===')[1:]}
    (root/'cgroups.json').write_text(json.dumps(stats, indent=2))

def worker(*args):
    return subprocess.Popen(report_command('sh', '-ec',
                             'echo "$$" > /fixture/worker.pid; exec python -B "$@"', 'sh', *args),
                            stdout=worker_log, stderr=worker_log)

def flush_artifacts(paths):
    script = (
        "import os,sys\n"
        "for path in sys.argv[1:]:\n"
        "    fd = os.open(path, os.O_RDONLY)\n"
        "    try:\n"
        "        os.fsync(fd)\n"
        "    finally:\n"
        "        os.close(fd)\n"
    )
    report_exec('python', '-B', '-c', script, *paths)

def finish(process):
    if process.wait(timeout=300):
        raise RuntimeError('reporting worker failed')

def phase(name, action):
    start = stamp()
    action()
    phases[name] = [start, stamp()]
    (root/'phases.json').write_text(json.dumps(phases, indent=2))

assert docker('network', 'inspect', '-f', '{{.Internal}}', 'ob16356-isolated') == 'true'
containers = ['ob16356-' + service for service in services]
for config in json.loads(docker('inspect', *containers)):
    assert config['State']['Running']
    assert set(config['NetworkSettings']['Networks']) == {'ob16356-isolated'}
    bindings = config['HostConfig']['PortBindings'] or {}
    assert not bindings or (
        config['Name'] == '/ob16356-auth'
        and all(binding['HostIp'] == '127.0.0.1' for group in bindings.values() for binding in group)
    )
    if config['Name'].removeprefix('/ob16356-') in {
        'domestic-payment', 'balance-service', 'account-service', 'party-service', 'edge',
    }:
        assert 'AUTHZ_ENFORCE=true' in config['Config']['Env']
datasets = (
    ('ob_perf_parties', 'parties'),
    ('ob_perf_accounts', 'accounts'),
    ('ob_perf_balances', 'balances'),
    ('ob_perf_domestic', 'domestic_payments'),
)
counts = {}
for database, table in datasets:
    count = int(docker('exec', 'ob16356-db', 'psql', '-h', '127.0.0.1', '-U', 'postgres',
                       '-XqAt', '-d', database, '-c', f'SELECT count(*) FROM {table}'))
    if count < 3_000_000:
        raise RuntimeError(f'{table} has fewer than three million rows')
    counts[table] = count
(root/'dataset-counts.json').write_text(json.dumps(counts, indent=2))
policy_containers = [name for name in containers if name.startswith('ob16356-opa-')]
bank_containers = [name for name in containers if name not in policy_containers]
docker('update', '--cpuset-cpus', bank_cpus, *bank_containers)
docker('update', '--cpuset-cpus', policy_cpus, *policy_containers)
for name in containers:
    if name.startswith('ob16356-opa-'):
        status = docker('run', '--rm', '--pid', f'container:{name}', '--network', 'none',
                        'python:3.12-alpine', 'cat', '/proc/1/status')
        allowed = next(line.split()[1] for line in status.splitlines()
                       if line.startswith('Cpus_allowed_list:'))
    else:
        allowed = docker('exec', name, 'cat', '/sys/fs/cgroup/cpuset.cpus.effective')
    expected = policy_cpus if name in policy_containers else bank_cpus
    assert allowed == expected, (name, allowed, expected)

def start_worker():
    if kube:
        manifest = root/'remote-pod.json'
        subprocess.run([*kube, 'create', '-f', str(manifest)], check=True, stdout=subprocess.DEVNULL)
        subprocess.run([*kube, 'wait', f'pod/{remote_pod}', '--for=condition=Ready', '--timeout=150s'],
                       check=True, stdout=subprocess.DEVNULL)
        return
    docker('run', '-d', '--name', report, '--network', 'none', '--read-only',
           '--cpuset-cpus', report_cpus, '--cpus', '2', '--memory', '1g', '--memory-swap', '1g',
           '--device-read-bps', f'{args.device}:10mb', '--device-write-bps', f'{args.device}:10mb',
           '-v', f'{volume}:/fixture', '-v', f'{repo}/scripts/regulatory:/source:ro',
           'python:3.12-alpine', 'sleep', 'infinity')

if kube:
    pod = json.loads(subprocess.check_output([*kube, 'get', 'pod', remote_pod, '-o', 'json']))
    if pod['status']['phase'] != 'Running':
        raise RuntimeError('Remote reporting worker is not ready')
    spec = pod['spec']
    resources = spec['containers'][0]['resources']
    assert resources['requests'] == resources['limits']
    assert resources['limits']['cpu'] == '2'
    assert resources['limits']['memory'] == '1Gi'
    pvc = json.loads(subprocess.check_output([*kube, 'get', 'pvc', 'reporting-data', '-o', 'json']))
    assert pvc['status']['phase'] == 'Bound'
    pv = json.loads(subprocess.check_output([*kube, 'get', 'pv', pvc['spec']['volumeName'], '-o', 'json']))
    assert pv['spec']['csi']['driver'] == 'ebs.csi.aws.com'
    node = json.loads(subprocess.check_output([*kube, 'get', 'node', spec['nodeName'], '-o', 'json']))
    assert node['spec'].get('providerID'), 'A cloud node identity is required for this remote fixture'
    assert not spec.get('hostNetwork', False)
    assert not spec.get('automountServiceAccountToken', True)
    assert spec['volumes'][0]['persistentVolumeClaim']['claimName'] == 'reporting-data'
    pod['metadata'] = {'name': remote_pod, 'namespace': namespace,
                       'labels': {'app': 'isolated-reporting-benchmark'}}
    pod.pop('status', None)
    pod['spec'].pop('nodeName', None)
    (root/'remote-pod.json').write_text(json.dumps(pod))
    # Recreate only this run's worker. The test PVC persists across worker loss.
else:
    assert volume not in docker('volume', 'ls', '--format', '{{.Name}}').splitlines()
    docker('volume', 'create', volume)
    start_worker()
try:
    report_exec('test', '!', '-e', '/fixture/journal.ndjson')
    report_exec('sh', '-ec',
           'cp /source/benchmark_scoped_reporting.py /source/scoped_trial_balance.py /fixture/; '
           'cd /fixture; python -B -c "import benchmark_scoped_reporting, scoped_trial_balance"')
    snapshot('before')
    if not kube:
        assert stats['before']['cpuset.cpus.effective'] == report_cpus
        assert stats['before']['memory.swap.max'] == '0'
        assert 'rbps=10485760 wbps=10485760' in stats['before']['io.max']
    with (root/'k6.log').open('w') as k6log, (root/'reporting.log').open('w') as worker_log:
        load = subprocess.Popen(['docker', 'run', '--rm', '--name', load_name,
            '--cpuset-cpus', bank_cpus, '--network', 'container:ob16356-edge',
            '--user', '0:0', '-v', f'{root}:/run', '-v', f'{auth}/customers.json:/fixtures.json:ro',
            '-v', f'{repo}/perf/k6/regulatory-customer-edge.js:/scenario.js:ro',
            '-e', 'EDGE_URL=http://127.0.0.1:8128/customer/v1', '-e', 'CUSTOMER_FIXTURE=/fixtures.json',
            '-e', 'RATE_PER_ENDPOINT=30', '-e', 'VUS_PER_ENDPOINT=36', '-e', 'DURATION=8m',
            'grafana/k6:2.2.0', 'run', '--out', 'json=/run/metrics.ndjson', '/scenario.js'],
            stdout=k6log, stderr=k6log)
        try:
            for _ in range(35):
                if load.poll() is not None:
                    raise RuntimeError('k6 exited before benchmark')
                metrics = root/'metrics.ndjson'
                if metrics.exists() and metrics.stat().st_size > 1000:
                    break
                time.sleep(1)
            else:
                raise RuntimeError('k6 emitted no samples')
            assert docker('exec', load_name, 'cat', '/sys/fs/cgroup/cpuset.cpus.effective') == bank_cpus
            for _ in range(6):
                time.sleep(12)
                recent = dict.fromkeys(('account', 'balance', 'payment'), 0)
                failures = 0
                cutoff = time.time() - 10
                with metrics.open('rb') as stream:
                    stream.seek(max(0, metrics.stat().st_size-10_000_000))
                    stream.readline()
                    for line in stream:
                        if b'"metric":"http_req_failed"' not in line:
                            continue
                        try:
                            sample = json.loads(line)
                            point = sample['data']
                            when = datetime.datetime.fromisoformat(point['time'])
                        except (ValueError, KeyError, TypeError):
                            continue
                        if when.timestamp() >= cutoff:
                            recent[point['tags']['scenario']] += 1
                            failures += int(point['value'])
                if min(recent.values()) >= 240 and failures == 0:
                    break
            else:
                raise RuntimeError('customer APIs did not sustain 90 successful reads/s in warm-up')
            phase('baseline', lambda: time.sleep(45))
            def export():
                finish(worker('-c',
                    'import sys;sys.path.insert(0,"/fixture");from pathlib import Path;'
                    'from benchmark_scoped_reporting import write_synthetic_export;'
                    'write_synthetic_export(Path("/fixture"),3000000)'))
                flush_artifacts(('/fixture/journal.ndjson', '/fixture/manifest.json', '/fixture'))
            phase('export', export)
            digest = hashlib.sha256(subprocess.check_output(
                report_command('cat', '/fixture/manifest.json'))).hexdigest()
            arguments = ('/fixture/scoped_trial_balance.py', '/fixture/manifest.json', digest,
                         '/fixture/journal.ndjson', '--max-records-per-second', '50000')

            def outage():
                interrupted = worker(*arguments, '/fixture/aborted')
                time.sleep(4)
                snapshot('before_outage')
                remove_worker()
                interrupted.wait(timeout=60)
                time.sleep(45)
            phase('outage', outage)

            def recover():
                start_worker()
                snapshot('restarted')
                recovered_digest = hashlib.sha256(subprocess.check_output(
                    report_command('cat', '/fixture/manifest.json'))).hexdigest()
                assert recovered_digest == digest
                report_exec('test', '!', '-e', '/fixture/aborted')
                finish(worker(*arguments, '/fixture/recovered'))
                files = report_exec('find', '/fixture/recovered', '-type', 'f').splitlines()
                flush_artifacts((*files, '/fixture/recovered', '/fixture'))
            phase('catch_up', recover)
            if load.poll() is not None:
                raise RuntimeError('k6 stopped before catch-up completed')
            phase('post_recovery', lambda: load.wait(timeout=600))
            if load.returncode:
                raise RuntimeError('k6 did not finish its planned workload successfully')
        finally:
            if load.poll() is None:
                subprocess.run(['docker', 'kill', '--signal=INT', load_name], stdout=subprocess.DEVNULL)
                load.wait(timeout=30)
    snapshot('after')
    archive = subprocess.check_output(report_command('tar', '-C', '/fixture', '-cf', '-', 'manifest.json', 'recovered'))
    with tarfile.open(fileobj=io.BytesIO(archive)) as artifacts:
        artifacts.extractall(root, filter='data')
finally:
    remove_worker()

manifest = json.loads((root/'manifest.json').read_text())
index = json.loads((root/'recovered/index.json').read_text())
assert len(manifest['contexts']) == len(index) == 3
assert sum(context['records'] for context in manifest['contexts']) == 6_000_000
for context in manifest['contexts']:
    scope = context['scope']
    artifact = index[scope['contextId']]
    raw = (root/'recovered'/artifact['file']).read_bytes()
    assert hashlib.sha256(raw).hexdigest() == artifact['sha256']
    balance = json.loads(raw)
    assert balance['scope'] == scope
    assert balance['trialBalance']['ledgerReportsBalanced']
    assert sum(Decimal(line['net']) for line in balance['trialBalance']['lines']) == 0

with (root/'summary.json').open('w') as summary:
    subprocess.run(['python3', str(repo/'perf/k6/summarize-regulatory-customer.py'),
                    str(root/'metrics.ndjson'), str(root/'phases.json')], check=True, stdout=summary)

spec = importlib.util.spec_from_file_location(
    "regulatory_summary", repo/'perf/k6/summarize-regulatory-customer.py')
summarizer = importlib.util.module_from_spec(spec)
spec.loader.exec_module(summarizer)
first = last = None
with (root/'metrics.ndjson').open() as stream:
    for line in stream:
        sample = json.loads(line)
        if sample['type'] == 'Point' and sample.get('metric') in {
            'http_req_duration', 'http_req_failed', 'dropped_iterations',
        }:
            instant = summarizer.instant(sample['data']['time'])
            first = min(first, instant) if first is not None else instant
            last = max(last, instant) if last is not None else instant
summarizer.PHASES = ('whole_run',)
whole_window = {'whole_run': [first.isoformat(), (last + datetime.timedelta(microseconds=1)).isoformat()]}
with (root/'metrics.ndjson').open() as stream:
    whole = summarizer.summarize(stream, whole_window)
(root/'whole-run.json').write_text(json.dumps(whole, indent=2))

errors = sum(api['errors'] for api in whole['whole_run'].values())
dropped = sum(api['dropped_iterations'] for api in whole['whole_run'].values())
(root/'acceptance.json').write_text(json.dumps({
    "httpErrors": errors,
    "droppedIterations": dropped,
    "observedErrorFreeScheduledTraffic": errors == 0 and dropped == 0,
}, indent=2))
if errors or dropped:
    raise RuntimeError(f'Customer traffic had {errors} HTTP failures and {dropped} dropped iterations')
