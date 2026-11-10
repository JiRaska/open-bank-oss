#!/usr/bin/env python3
"""Write the open-PR snapshot agent-admission.py reads, surviving an exhausted REST quota.

Agent PR admission runs first in CI and Services CI on every pull_request and needs one list:
the open PRs. It used `gh api --paginate --slurp pulls?state=open` on GITHUB_TOKEN, whose REST
core quota (5000/h) is shared by every job in the repository. When some other consumer drains it,
that one listing fails with "API rate limit exceeded for installation" and the whole PR goes red on
a required check for a reason that has nothing to do with the PR (2026-11-10 07:37Z, #15308).

GraphQL has its own budget, independent of REST core. So: REST first (unchanged behaviour), and
on a RATE-LIMIT failure only, the same snapshot through GraphQL, reshaped to the REST fields the
admission script reads (number, state, created_at, head.ref, head.repo.full_name) and emitted in
the same `--slurp` page-list shape. Any other failure (404, auth, network) stays fatal, so a
misconfiguration is never papered over. Waiting for the reset was rejected: the job would hold a
hosted runner slot for up to an hour.

Usage: fetch-open-prs.py <owner/repo> <out.json>      --self-test
"""
import json
import os
import re
import subprocess
import sys
import tempfile

GH = os.environ.get("FETCH_OPEN_PRS_GH", "gh")
RATE_LIMIT = re.compile(r"rate.?limit|secondary rate|too many requests|http 429|abuse detection", re.I)
QUERY = """query($owner:String!,$name:String!,$after:String){repository(owner:$owner,name:$name){
pullRequests(states:OPEN,first:100,after:$after){pageInfo{hasNextPage endCursor}
nodes{number createdAt headRefName headRepository{nameWithOwner}}}}}"""


def run(args):
    p = subprocess.run([GH, *args], capture_output=True, text=True)
    return p.returncode, p.stdout, p.stderr


def via_rest(repo):
    return run(["api", "--paginate", "--slurp", f"repos/{repo}/pulls?state=open&per_page=100"])


def via_graphql(repo):
    owner, name = repo.split("/", 1)
    prs, after = [], None
    for _ in range(50):  # 5000 open PRs; far beyond any real queue
        args = ["api", "graphql", "-f", f"query={QUERY}", "-f", f"owner={owner}", "-f", f"name={name}"]
        if after:
            args += ["-f", f"after={after}"]
        rc, out, err = run(args)
        if rc != 0:
            raise RuntimeError(f"graphql fallback failed: {err.strip()}")
        conn = json.loads(out)["data"]["repository"]["pullRequests"]
        for n in conn["nodes"]:
            repo_of_head = (n.get("headRepository") or {}).get("nameWithOwner")
            prs.append({"number": n["number"], "state": "open", "created_at": n["createdAt"],
                        "head": {"ref": n["headRefName"],
                                 "repo": {"full_name": repo_of_head} if repo_of_head else None}})
        if not conn["pageInfo"]["hasNextPage"]:
            return [prs]
        after = conn["pageInfo"]["endCursor"]
    raise RuntimeError("graphql fallback: pagination did not terminate")


def fetch(repo):
    rc, out, err = via_rest(repo)
    if rc == 0:
        return json.loads(out), "rest"
    if not RATE_LIMIT.search(err):
        raise RuntimeError(f"open-PR listing failed: {err.strip()}")
    print(f"::warning::REST quota exhausted ({err.strip()}); reading open PRs via GraphQL", file=sys.stderr)
    return via_graphql(repo), "graphql"


def self_test():
    """Drive every branch against a stub gh. Returns the number of failures."""
    fails = 0
    gql_page = {"data": {"repository": {"pullRequests": {"pageInfo": {"hasNextPage": False, "endCursor": None},
                "nodes": [{"number": 7, "createdAt": "2026-11-10T00:00:00Z", "headRefName": "agent/x",
                           "headRepository": {"nameWithOwner": "o/r"}}]}}}}
    cases = {
        "rest-ok": ("0", json.dumps([[{"number": 1, "state": "open", "head": {"ref": "a"}}]]), "", "rest", None),
        "rate-limit": ("1", "", "gh: API rate limit exceeded for installation ID 1. (HTTP 403)", "graphql", None),
        "not-found": ("1", "", "gh: Not Found (HTTP 404)", None, "open-PR listing failed"),
    }
    global GH
    saved = GH
    with tempfile.TemporaryDirectory() as tmp:
        for name, (rc, out, err, want, want_err) in cases.items():
            stub = os.path.join(tmp, f"gh-{name}")
            with open(stub, "w") as f:
                f.write("#!/usr/bin/env bash\n"
                        f"if [ \"$2\" = graphql ]; then printf '%s' {json.dumps(json.dumps(gql_page))}; exit 0; fi\n"
                        f"printf '%s' {json.dumps(out)}; printf '%s' {json.dumps(err)} >&2; exit {rc}\n")
            os.chmod(stub, 0o755)
            GH = stub
            try:
                pages, how = fetch("o/r")
                ok = want == how and isinstance(pages, list) and all(isinstance(p, list) for p in pages)
                if how == "graphql":
                    pr = pages[0][0]
                    ok = ok and pr == {"number": 7, "state": "open", "created_at": "2026-11-10T00:00:00Z",
                                       "head": {"ref": "agent/x", "repo": {"full_name": "o/r"}}}
            except RuntimeError as e:
                ok = want is None and want_err in str(e)
            print(f"{'ok  ' if ok else 'FAIL'} {name}")
            fails += not ok
    GH = saved
    return fails


def main(argv):
    if argv[1:] == ["--self-test"]:
        return 1 if self_test() else 0
    if len(argv) != 3:
        print(__doc__, file=sys.stderr)
        return 2
    pages, how = fetch(argv[1])
    with open(argv[2], "w") as f:
        json.dump(pages, f)
    print(f"open-PR snapshot via {how}: {sum(len(p) for p in pages)} PRs", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
