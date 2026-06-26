#!/usr/bin/env python3
"""PreToolUse Claude hook: blocks git push to branches with open PRs from other sessions."""
import json, re, subprocess, sys

def main():
    try:
        inp = json.load(sys.stdin)
        cmd = inp.get("tool_input", {}).get("command", "")
    except Exception:
        return
    if "git push" not in cmd or "--no-verify" in cmd:
        return
    m = re.search(r"origin\s+([\w/._-]+)", cmd)
    branch = m.group(1) if m else None
    if not branch:
        try:
            branch = subprocess.check_output(["git","rev-parse","--abbrev-ref","HEAD"],stderr=subprocess.DEVNULL).decode().strip()
        except Exception:
            return
    if branch in ("main","HEAD",""):
        return
    try:
        wt = subprocess.check_output(["git","rev-parse","--show-toplevel"],stderr=subprocess.DEVNULL).decode().strip()
        if wt.startswith("/tmp/openbank-pr-"):
            return
    except Exception:
        pass
    try:
        r = subprocess.run(["gh","pr","list","--head",branch,"--state","open","--json","number,title,url"],capture_output=True,text=True,timeout=10)
        prs = json.loads(r.stdout) if r.returncode == 0 else []
    except Exception:
        return
    if not prs:
        return
    pr_list = "\n".join(f"  #{p['number']} {p['title']}" for p in prs)
    print(json.dumps({"decision":"block","reason":f"Branch '{branch}' has open PR(s):\n{pr_list}\n\nCreate own branch or use /tmp/openbank-pr-* worktree. Bypass: git push --no-verify"}))

if __name__ == "__main__":
    main()
