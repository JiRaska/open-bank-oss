#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Bounded real KYB/Context acceptance probe for an existing synthetic fixture.

Private tokens JSON contains maker/checker/reader/denied bearer strings (or
access_token objects). Maker, checker and reader require ROLE_ADMIN; denied must
be non-admin. The reader's preferred_username is the assignment principal.
--restrict-observation is OFF by default: enabling it permanently restricts one
synthetic source observation. Cleanup revokes only this probe's assignment or
rejects its pending proposal; source cases and evidence are never deleted.
TLS verification is mandatory. No policy or runtime configuration is modified.
"""
import argparse
import base64
import datetime
import json
import os
import re
import ssl
import stat
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

PURPOSE = 'KYB_OWNERSHIP_REVIEW'
LIMIT = 1024 * 1024


class Failure(Exception):
    pass


def require(condition, label):
    if not condition:
        raise Failure(label)


def canonical_uuid(value):
    return str(uuid.UUID(value))


def claims(token):
    require(isinstance(token, str) and len(token) <= 65536
            and re.fullmatch(r'[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+', token), 'token-format')
    segment = token.split('.')[1]
    value = json.loads(base64.urlsafe_b64decode(segment + '=' * (-len(segment) % 4)))
    require(isinstance(value, dict), 'token-format')
    return value  # Validation only: servers verify signatures and authorisation.


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise Failure('redirect-not-allowed')


class Harness:
    def __init__(self, args):
        self.args = args
        self.proposal = None
        self.assignment = None
        self.checks = []
        fd = os.open(args.tokens_json, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
        with os.fdopen(fd, 'r', encoding='utf-8') as stream:
            info = os.fstat(stream.fileno())
            require(stat.S_ISREG(info.st_mode) and info.st_uid == os.getuid()
                    and info.st_mode & 0o077 == 0 and info.st_size <= LIMIT, 'private-token-file-required')
            raw = json.load(stream)
        self.tokens = {}
        identities = []
        for role in ('maker', 'checker', 'reader', 'denied'):
            value = raw[role]
            token = value if isinstance(value, str) else value['access_token']
            info = claims(token)
            identity = info.get('preferred_username')
            require(isinstance(identity, str) and identity.strip() == identity
                    and identity and not any(ord(c) < 32 for c in identity), 'token-principal')
            require(isinstance(info.get('exp'), (int, float)) and info['exp'] > time.time(), 'token-expired')
            roles = set(info.get('realm_access', {}).get('roles', [])) | set(info.get('groups', []))
            require(('ROLE_ADMIN' in roles) == (role != 'denied'), 'token-role-layout')
            identities.append(identity)
            self.tokens[role] = token
        require(len(set(identities)) == 4 and len(set(self.tokens.values())) == 4,
                'distinct-principals-required')
        subjects = [claims(token).get('sub') for token in self.tokens.values()]
        require(all(isinstance(s, str) and s for s in subjects) and len(set(subjects)) == 4,
                'distinct-subjects-required')
        self.reader = identities[2]
        tls = ssl.create_default_context(cafile=args.ca_file)
        if args.client_cert:
            tls.load_cert_chain(args.client_cert, args.client_key)
        self.http = urllib.request.build_opener(urllib.request.HTTPSHandler(context=tls), NoRedirect())

    def request(self, service, role, method, path, body=None, expected=(200,), investigation=True):
        headers = {'Authorization': 'Bearer ' + self.tokens[role], 'Accept': 'application/json'}
        if investigation:
            headers.update({'X-Investigation-Case-Id': self.args.case_id,
                            'X-Investigation-Purpose': PURPOSE})
        data = None if body is None else json.dumps(body).encode('utf-8')
        if data is not None:
            headers['Content-Type'] = 'application/json'
        base = self.args.context_url if service == 'context' else self.args.kyb_url
        req = urllib.request.Request(base.rstrip('/') + path, data=data, headers=headers, method=method)
        try:
            with self.http.open(req, timeout=10) as response:
                status = response.status
                payload = response.read(LIMIT + 1) if status < 400 else b''
        except urllib.error.HTTPError as error:
            status, payload = error.code, b''  # Never inspect or log error bodies.
            error.close()
        except (OSError, urllib.error.URLError):
            raise Failure('transport-unavailable') from None
        require(status in expected, 'unexpected-http-' + str(status))
        require(len(payload) <= LIMIT, 'response-too-large')
        return status, json.loads(payload) if payload else None

    def history(self, role='reader', known_at=None, expected=(200,)):
        path = '/api/v1/context/kyb-cases/' + self.args.case_id + '/ownership-observations'
        if known_at:
            path += '?' + urllib.parse.urlencode({'knownAt': known_at})
        return self.request('context', role, 'GET', path, expected=expected)[1]

    def poll(self, condition, label):
        deadline = time.monotonic() + 30
        for _ in range(60):
            if time.monotonic() >= deadline:
                break
            if condition():
                self.checks.append(label + ':PASS')
                return
            time.sleep(0.5)
        raise Failure(label + '-deadline')

    def run(self):
        case = self.args.case_id
        proposal = self.request('context', 'maker', 'POST', '/api/v1/context/assignment-proposals', {
            'principalId': self.reader, 'caseId': case, 'purpose': PURPOSE,
            'rootRef': 'kyb-case:' + case,
            'validTo': (datetime.datetime.now(datetime.timezone.utc) + datetime.timedelta(hours=1)).isoformat(),
        }, expected=(201,), investigation=False)[1]
        self.proposal = canonical_uuid(proposal['id'])
        result = self.request('context', 'checker', 'PATCH',
                              '/api/v1/context/assignment-proposals/' + self.proposal,
                              {'approve': True}, investigation=False)[1]
        if result.get('assignmentId'):
            self.assignment = canonical_uuid(result['assignmentId'])
        require(result.get('status') == 'APPROVED' and self.assignment, 'assignment-approval')
        self.proposal = None
        self.checks.append('maker-checker-assignment:PASS')
        captured = []

        def available():
            view = self.history()
            require(view.get('root') == 'kyb-case:' + case and isinstance(view.get('observations'), list),
                    'history-contract')
            if not view['observations']:
                return False
            require(view.get('truncated') is False, 'history-truncated')
            captured[:] = [view]
            return True

        self.poll(available, 'source-history-visible')
        view = captured[0]
        row = view['observations'][0]
        observation = canonical_uuid(row['observationId'])
        detail_path = '/api/v1/kyb/cases/' + case + '/ubo-observations/' + observation
        detail = self.request('kyb', 'reader', 'GET', detail_path)[1]
        require(detail.get('id') == observation and detail.get('caseId') == case
                and detail.get('revision') == row.get('revision')
                and isinstance(row.get('sourceSha256'), str)
                and re.fullmatch(r'[a-fA-F0-9]{64}', row['sourceSha256'])
                and detail.get('sourceSha256') == row['sourceSha256'], 'source-reference-match')
        finding = detail.get('finding', {})
        require(finding.get('source') == 'REGISTER' and isinstance(finding.get('owners'), list)
                and finding['owners'], 'source-register-owners')
        self.checks.append('source-detail-register-owners:PASS')
        for role in ('maker', 'denied'):
            self.history(role, expected=(403,))
            self.request('kyb', role, 'GET', detail_path, expected=(403,))
        self.checks.append('unassigned-admin-nonadmin-denied:PASS')
        if self.args.restrict_observation:
            known = view['knownAt']
            require(isinstance(known, str) and known, 'history-known-at')
            self.request('kyb', 'reader', 'POST', detail_path + '/restrict',
                         {'reasonCode': 'SOURCE_WITHDRAWN'}, expected=(204,))

            def restricted():
                status, _ = self.request('kyb', 'reader', 'GET', detail_path, expected=(200, 404))
                current = self.history()['observations']
                historical = self.history(known_at=known)['observations']
                return status == 404 and all(r['observationId'] != observation for r in current + historical)

            self.poll(restricted, 'restriction-current-and-prior-known-at-omission')

    def cleanup(self):
        try:
            if self.assignment:
                self.request('context', 'checker', 'DELETE',
                             '/api/v1/context/assignment-proposals/assignments/' + self.assignment,
                             expected=(204,), investigation=False)
            elif self.proposal:
                # Never retry approval or infer ownership of another active assignment.
                result = self.request('context', 'checker', 'PATCH',
                                      '/api/v1/context/assignment-proposals/' + self.proposal,
                                      {'approve': False}, investigation=False)[1]
                require(result.get('status') == 'REJECTED', 'proposal-rejection')
            return True
        except Exception:
            return False


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--synthetic-fixture', action='store_true', required=True)
    parser.add_argument('--context-url', required=True)
    parser.add_argument('--kyb-url', required=True)
    parser.add_argument('--case-id', required=True)
    parser.add_argument('--tokens-json', required=True)
    parser.add_argument('--ca-file', required=True)
    parser.add_argument('--client-cert')
    parser.add_argument('--client-key')
    parser.add_argument('--restrict-observation', action='store_true',
                        help='Permanently restrict one synthetic source observation; off by default')
    args = parser.parse_args()
    harness = None
    failed = False
    try:
        args.case_id = canonical_uuid(args.case_id)
        require(bool(args.client_cert) == bool(args.client_key), 'client-certificate-pair-required')
        for value in (args.context_url, args.kyb_url):
            parsed = urllib.parse.urlsplit(value)
            require(parsed.scheme == 'https' and parsed.hostname and not parsed.username
                    and not parsed.password and not parsed.query and not parsed.fragment
                    and parsed.path in ('', '/') and not any(c.isspace() for c in value),
                    'https-origin-required')
            _ = parsed.port
        harness = Harness(args)
        harness.run()
    except Exception as error:
        failed = True
        print('scenario:FAIL ' + (str(error) if isinstance(error, Failure) else 'setup-or-contract-failure'))
    finally:
        if harness is not None:
            cleaned = harness.cleanup()
            failed = failed or not cleaned
            print(' '.join(harness.checks))
            print('cleanup:' + ('PASS' if cleaned else 'FAIL'))
    if not failed:
        print('scenario:PASS')
    return 1 if failed else 0


if __name__ == '__main__':
    sys.exit(main())
