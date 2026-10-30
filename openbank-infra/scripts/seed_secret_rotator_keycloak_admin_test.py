# SPDX-License-Identifier: Apache-2.0
"""Offline seed regression: synthetic credentials, no cluster or secret access."""

import base64
import json
import os
import pathlib
import shlex
import subprocess
import tempfile
import unittest

SCRIPT = pathlib.Path(__file__).with_name("seed-secret-rotator-keycloak-admin.sh")
FAKE = """#!/usr/bin/env python3
import sys,os,json,pathlib,base64
p=pathlib.Path(os.environ['PROOF']);args=sys.argv[1:]
with (p/'calls').open('a') as f:f.write(json.dumps([pathlib.Path(sys.argv[0]).name,args])+'\\n')
if pathlib.Path(sys.argv[0]).name=='kubectl':
 if 'secret' in args:
  key='USERNAME' if 'admin-username' in args[-1] else 'PASSWORD'
  print(base64.b64encode(os.environ[key].encode()).decode());sys.exit(0)
 i=args.index('--');os.execvp(args[i+1],args[i+1:])
assert os.environ['BAO_TOKEN']==os.environ['TOKEN']
if args[:2]==['kv','put']:
 assert sys.stdin.read()==os.environ['PASSWORD']
 assert args[3]=='username='+os.environ['USERNAME'] and args[4]=='password=-'
 (p/'written').touch();sys.exit(0)
assert args[:2]==['kv','get']
if os.environ['MODE']=='exists' or (p/'written').exists():
 if os.environ['MODE']=='postfail' and '-field=password' in args:sys.exit(2)
 print('fixture-value');sys.exit(0)
sys.exit(2)
"""


class SeedCredentialsTest(unittest.TestCase):
    def check_flow(self, mode):
        with tempfile.TemporaryDirectory(prefix="seed-offline-") as tmp:
            root = pathlib.Path(tmp)
            sentinel = root / "INJECTED"
            token = 'synthetic-token-quote"$()'
            password = "synthetic-password\n$(touch " + str(sentinel) + ')"'
            username = 'admin"; touch ' + str(sentinel) + "; #"
            kv_path = 'fixture"; touch ' + str(sentinel) + "; #"
            for name in ("kubectl", "bao"):
                executable = root / name
                executable.write_text(FAKE)
                executable.chmod(0o755)
            script = root / "seed.sh"
            source = SCRIPT.read_text()
            needle = "KV_PATH=openbank/keycloak/admin"
            self.assertEqual(
                source.count(needle),
                1,
                "KV path fixture must replace exactly one assignment",
            )
            script.write_text(source.replace(needle, "KV_PATH=" + shlex.quote(kv_path)))
            env = dict(
                os.environ,
                PATH=tmp + os.pathsep + os.environ["PATH"],
                PROOF=tmp,
                TOKEN=token,
                VAULT_TOKEN=token,
                PASSWORD=password,
                USERNAME=username,
                MODE=mode,
            )
            # Never inherit an operator credential through alternate supported aliases.
            env.pop("BAO_TOKEN", None)
            env.pop("RT", None)
            result = subprocess.run(
                ["bash", str(script)],
                env=env,
                capture_output=True,
                check=False,
                text=True,
                timeout=15,
            )
            calls = [
                json.loads(line) for line in (root / "calls").read_text().splitlines()
            ]
            for executable, args in calls:
                argv = "\0".join(args)
                for secret in (
                    token,
                    password,
                    base64.b64encode(password.encode()).decode(),
                ):
                    self.assertNotIn(
                        secret, argv, executable + " exposed synthetic credential"
                    )
            self.assertEqual(
                result.returncode, 1 if mode == "postfail" else 0, result.stderr
            )
            self.assertFalse(sentinel.exists(), "quoted input executed as shell code")
            self.assertEqual((root / "written").exists(), mode != "exists")
            writes = [
                args
                for executable, args in calls
                if executable == "bao" and args[:2] == ["kv", "put"]
            ]
            self.assertEqual(len(writes), 0 if mode == "exists" else 1)

    def test_existing_record_exits_without_write(self):
        self.check_flow("exists")

    def test_missing_record_writes_and_verifies(self):
        self.check_flow("missing")

    def test_failed_postwrite_probe_returns_failure(self):
        self.check_flow("postfail")


if __name__ == "__main__":
    unittest.main()
