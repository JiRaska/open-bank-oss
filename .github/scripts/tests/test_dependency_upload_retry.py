"""Exercise the upload step with a fake gh, without network or waits."""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
import yaml

class DependencyUploadRetryTest(unittest.TestCase):
    def test_upload_retries(self):
        root = Path(__file__).resolve().parents[3]
        workflow = yaml.safe_load((root / ".github/workflows/dependency-submission.yml").read_text())
        step = next(s for s in workflow["jobs"]["submit"]["steps"]
                    if s.get("name") == "Submit complete fleet snapshot")
        cases = [("502", 1, 0, 2), ("502", 9, 1, 3), ("401", 9, 1, 1)]
        for code, failures, expected, attempts in cases:
            with self.subTest(code=code, failures=failures), tempfile.TemporaryDirectory() as tmp:
                d = Path(tmp)
                snapshot = d / "dependency-snapshot-10-1/merged.json"
                snapshot.parent.mkdir()
                snapshot.write_text('{"sha":"fixture"}')
                stub = d / "gh"
                stub.write_text("""#!/usr/bin/env python3
import hashlib,json,os,sys
from pathlib import Path
log=Path(os.environ['UPLOAD_TEST_LOG'])
rows=json.loads(log.read_text()) if log.exists() else []
args=sys.argv[1:]
rows.append({'args':args,'sha256':hashlib.sha256(Path(args[args.index('--input')+1]).read_bytes()).hexdigest()})
log.write_text(json.dumps(rows))
if len(rows)<=int(os.environ['UPLOAD_TEST_FAILURES']):
 message='Server Error' if os.environ['UPLOAD_TEST_CODE']=='502' else 'Bad credentials'
 print('gh: '+message+' (HTTP '+os.environ['UPLOAD_TEST_CODE']+')',file=sys.stderr)
 sys.exit(1)
""")
                stub.chmod(0o755)
                (d / "sleep").write_text("#!/bin/sh\nexit 0\n")
                (d / "sleep").chmod(0o755)
                env = {**os.environ, "PATH": str(d)+os.pathsep+os.environ["PATH"],
                       "GITHUB_REPOSITORY":"example/example", "GITHUB_RUN_ID":"10",
                       "GITHUB_RUN_ATTEMPT":"1", "RUNNER_TEMP":tmp,
                       "UPLOAD_TEST_LOG":str(d / "calls.json"),
                       "UPLOAD_TEST_FAILURES":str(failures), "UPLOAD_TEST_CODE":code}
                result = subprocess.run(["bash", "-c", step["run"]], cwd=root,
                    env=env, capture_output=True, text=True, timeout=10)
                self.assertEqual(expected, result.returncode, result.stderr)
                rows=json.loads((d / "calls.json").read_text())
                self.assertEqual(attempts,len(rows))
                self.assertTrue(all(r==rows[0] for r in rows))
                self.assertEqual(["api","--method","POST",
                    "repos/example/example/dependency-graph/snapshots","--input",str(snapshot)], rows[0]['args'])

if __name__ == "__main__":
    unittest.main()
