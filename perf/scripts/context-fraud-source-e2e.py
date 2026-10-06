#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Real, synthetic-only Fraud/Context lifecycle probe; Python standard library.

Tokens JSON contains maker/checker/reader/denied bearer strings (or access_token
objects). Maker, checker and reader need existing admin permissions; denied must
be non-admin. No policy, TLS, database or runtime configuration is modified.
Immutable synthetic scoring/audit evidence remains after normal API cleanup.
"""
import argparse
import base64
import datetime
import json
import ssl
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid


class Failure(Exception):
    pass


def require(condition, label):
    if not condition:
        raise Failure(label)


def claims(token):
    try:
        segment = token.split('.')[1]
        return json.loads(base64.urlsafe_b64decode(segment + '=' * (-len(segment) % 4)))
    except (ValueError, IndexError):
        raise Failure('token-format') from None


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise Failure('redirect-not-allowed')


class Harness:
    def __init__(self, args):
        self.args = args
        with open(args.tokens_json, encoding='utf-8') as stream:
            raw = json.load(stream)
        self.tokens = {}
        self.identities = {}
        for role in ('maker', 'checker', 'reader', 'denied'):
            value = raw[role]
            token = value if isinstance(value, str) else value['access_token']
            require(isinstance(token, str) and '\n' not in token, 'token-format')
            self.tokens[role] = token
            info = claims(token)  # Input validation only; the servers verify signatures.
            self.identities[role] = info.get('upn') or info.get('preferred_username') or info.get('sub')
            require(self.identities[role], 'token-principal')
            roles = set(info.get('realm_access', {}).get('roles', [])) | set(info.get('groups', []))
            require(('ROLE_ADMIN' in roles) == (role != 'denied'), 'token-role-layout')
        require(len(set(self.identities.values())) == 4, 'distinct-principals-required')
        self.reader = args.reader_principal or self.identities['reader']
        self.tls = ssl.create_default_context(cafile=args.ca_file)
        self.http = urllib.request.build_opener(urllib.request.HTTPSHandler(context=self.tls), NoRedirect())
        self.cases = []
        self.closed = set()
        self.assignments = {}
        self.pending = set()
        self.checks = []

    def request(self, service, role, method, path, body=None, case=None, expected=(200,)):
        headers = {'Authorization': 'Bearer ' + self.tokens[role], 'Accept': 'application/json'}
        if case is not None:
            headers.update({'X-Investigation-Purpose': 'FRAUD_INVESTIGATION',
                            'X-Investigation-Case-Id': case})
        if method == 'POST' and service == 'fraud':
            headers['X-Investigation-Purpose'] = 'FRAUD_INVESTIGATION'
            headers['Idempotency-Key'] = str(uuid.uuid4())
        data = None if body is None else json.dumps(body).encode()
        if data is not None:
            headers['Content-Type'] = 'application/json'
        base = self.args.fraud_url if service == 'fraud' else self.args.context_url
        req = urllib.request.Request(base.rstrip('/') + path, data=data, headers=headers, method=method)
        try:
            with self.http.open(req, timeout=10) as response:
                status, payload = response.status, response.read()
        except urllib.error.HTTPError as error:
            status, payload = error.code, error.read()
        except (OSError, urllib.error.URLError):
            raise Failure('transport-unavailable') from None
        require(status in expected, 'unexpected-http-' + str(status))
        return json.loads(payload) if payload and status < 400 else None

    def grant(self, case):
        proposal = self.request('context', 'maker', 'POST', '/api/v1/context/assignment-proposals', {
            'principalId': self.reader, 'caseId': case, 'purpose': 'FRAUD_INVESTIGATION',
            'rootRef': 'fraud-case:' + case,
            'validTo': (datetime.datetime.now(datetime.timezone.utc) + datetime.timedelta(hours=1)).isoformat(),
        }, expected=(201,))
        proposal_id = proposal['id']
        self.pending.add(proposal_id)
        result = self.request('context', 'checker', 'PATCH',
                              '/api/v1/context/assignment-proposals/' + proposal_id, {'approve': True})
        require(result['status'] == 'APPROVED' and result.get('assignmentId'), 'assignment-approval')
        self.assignments[case] = result['assignmentId']
        self.pending.remove(proposal_id)

    def network(self, case, role='reader', expected=(200,)):
        return self.request('context', role, 'GET', '/api/v1/context/fraud-cases/' + case + '/network',
                            case=case, expected=expected)

    def poll(self, condition, label):
        deadline = time.monotonic() + 30
        while time.monotonic() < deadline:
            if condition():
                self.checks.append(label + ':PASS')
                return
            time.sleep(0.5)
        raise Failure(label + '-deadline')

    def close(self, case):
        result = self.request('fraud', 'reader', 'POST',
                              '/api/v1/fraud/cases/' + case + '/close-without-finding', case=case)
        require(result['status'] == 'CLOSED_NO_FINDING', 'source-close-status')
        self.closed.add(case)

    def run(self):
        account = str(uuid.uuid4())
        for _ in range(3):
            counterparty = str(uuid.uuid4())
            result = self.request('fraud', 'maker', 'POST', '/api/v1/fraud/score', {
                'amount': 20000, 'currency': 'EUR', 'rail': 'SEPA',
                'accountId': account, 'counterpartyId': counterparty,
            })
            require(result['verdict'] == 'REVIEW', 'review-verdict')
            found = []
            def find_score():
                rows = self.request('fraud', 'maker', 'GET', '/api/v1/fraud/review-queue?limit=100')
                found[:] = [row['scoreId'] for row in rows
                            if row.get('accountId') == account and row.get('counterpartyId') == counterparty]
                return len(found) == 1
            self.poll(find_score, 'review-queue')
            opened = self.request('fraud', 'maker', 'POST', '/api/v1/fraud/cases',
                                  {'scoreId': found[0]}, expected=(201,))
            self.cases.append(str(uuid.UUID(opened['caseId'])))
            require(opened['status'] == 'OPEN', 'source-open-status')
        root, related, hidden = self.cases
        self.grant(root)
        self.grant(related)
        def related_visible():
            view = self.network(root)
            rows = view['related']
            return (view['root']['caseId'] == root and len(rows) == 1
                    and rows[0]['evidence']['caseId'] == related
                    and rows[0]['shared'] == [{'type': 'ACCOUNT', 'sourceId': account}])
        self.poll(related_visible, 'assigned-shared-account-network')
        for role in ('maker', 'denied'):
            self.network(root, role=role, expected=(403,))
        self.network(hidden, expected=(403,))
        self.checks.append('unassigned-admin-nonadmin-hidden:PASS')
        self.close(related)
        self.poll(lambda: self.network(root)['related'] == [], 'closed-related-removed')
        self.network(related, expected=(403,))
        self.checks.append('closed-case-as-root-denied:PASS')

    def cleanup(self):
        failures = []
        for case in self.cases:
            if case not in self.closed:
                try:
                    if case not in self.assignments:
                        self.grant(case)
                    self.close(case)
                except Exception:
                    failures.append('case-cleanup:FAIL')
        for assignment in self.assignments.values():
            try:
                self.request('context', 'checker', 'DELETE',
                             '/api/v1/context/assignment-proposals/assignments/' + assignment, expected=(204,))
            except Exception:
                failures.append('assignment-cleanup:FAIL')
        for proposal in self.pending:
            try:
                self.request('context', 'checker', 'PATCH',
                             '/api/v1/context/assignment-proposals/' + proposal, {'approve': False})
            except Exception:
                failures.append('proposal-cleanup:FAIL')
        return failures


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--synthetic-fixture', action='store_true', required=True)
    parser.add_argument('--fraud-url', required=True)
    parser.add_argument('--context-url', required=True)
    parser.add_argument('--ca-file', required=True)
    parser.add_argument('--tokens-json', required=True)
    parser.add_argument('--reader-principal', help='Override JWT principal mapping used by the deployed server')
    args = parser.parse_args()
    for value in (args.fraud_url, args.context_url):
        parsed = urllib.parse.urlsplit(value)
        require(parsed.scheme == 'https' and parsed.hostname and not parsed.username
                and not parsed.password and not parsed.query and not parsed.fragment, 'https-base-url-required')
    harness = None
    failed = False
    try:
        harness = Harness(args)
        harness.run()
    except Exception as error:
        failed = True
        # Never print exception text from HTTP/TLS/JSON libraries or request bodies.
        label = str(error) if isinstance(error, Failure) else 'setup-or-contract-failure'
        print('scenario:FAIL ' + label)
    finally:
        if harness is not None:
            failures = harness.cleanup()
            failed = failed or bool(failures)
            print(' '.join(harness.checks))
            print('cleanup:' + ('FAIL' if failures else 'PASS'))
    return 1 if failed else 0


if __name__ == '__main__':
    try:
        sys.exit(main())
    except Failure:
        print('configuration:FAIL')
        sys.exit(1)
