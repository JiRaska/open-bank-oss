#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Real synthetic Complaint/Context probe; standard library and verified TLS.

Requires an existing synthetic TransactionService transaction and domestic payment.
Their UUIDs remain distinct inputs. The graph must join the complaint's
booking-transaction:<transactionId> target to transaction:<paymentId> through
authoritative BOOKING_REQUESTED evidence. Independently projected payment
lifecycle evidence must belong to that payment ID; references alone cannot pass.
The domestic-payment lifecycle must have been
independently emitted through normal source APIs. Creates an assigned complaint,
records an interim reply, and creates a second unassigned complaint to test root
isolation. It leaves both synthetic source complaints open; cleanup only
revokes its owned Context assignment or rejects its pending proposal. Tokens are
read from a private owner-only JSON file with maker/checker/reader/denied keys.
No event injection, direct database access or configuration changes are used.
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

PURPOSE = 'PAYMENT_COMPLAINT'
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

    def request(self, service, role, method, path, body=None, expected=(200,), investigation=True,
                case_id=None, purpose=None):
        headers = {'Authorization': 'Bearer ' + self.tokens[role], 'Accept': 'application/json'}
        if investigation:
            headers.update({'X-Investigation-Case-Id': case_id if case_id is not None else self.args.case_id,
                            'X-Investigation-Purpose': purpose if purpose is not None else PURPOSE})
        data = None if body is None else json.dumps(body).encode('utf-8')
        if data is not None:
            headers['Content-Type'] = 'application/json'
        base = {'context': self.args.context_url, 'dispute': self.args.dispute_url,
                'payment': self.args.payment_url, 'transaction': self.args.transaction_url}[service]
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

    def graph(self, role='reader', as_of=None, expected=(200,), case_id=None, purpose=None, reference=None):
        path = '/api/v1/context/complaints/' + urllib.parse.quote(
            reference if reference is not None else self.reference, safe='')
        if as_of:
            path += '?' + urllib.parse.urlencode({'asOf': as_of})
        return self.request('context', role, 'GET', path, expected=expected,
                            case_id=case_id, purpose=purpose)[1]

    def verify_graph(self, view, source):
        if view is None:
            return False
        root = 'complaint:' + self.reference
        target = 'booking-transaction:' + self.args.transaction_id
        payment_key = 'transaction:' + self.args.payment_id
        require(view.get('root') == root and view.get('truncated') is False, 'graph-contract')
        nodes = {node['key']: node for node in view['nodes']}
        complaint = nodes.get(root)
        if not complaint or complaint.get('sourceVersion') != source['aggregateRevision']:
            return False
        require(complaint.get('sourceRef') == self.complaint_id
                and complaint.get('sourceSystem') == 'dispute-service'
                and complaint.get('label') == 'Complaint ' + self.reference + ' · ' + source['status'],
                'complaint-source-match')
        edges = view['edges']
        require(any(e.get('from') == root and e.get('to') == target
                    and e.get('relation') == 'CONCERNS_TRANSACTION'
                    and e.get('sourceVersion') == source['aggregateRevision'] for e in edges),
                'same-transaction-edge')
        booking = nodes.get(target)
        if not booking or booking.get('sourceSystem') != 'transaction-service':
            return False
        require(booking.get('sourceRef') == self.args.transaction_id
                and booking.get('type') == 'TRANSACTION_BOOKING', 'authoritative-booking-identity')
        if not any(e.get('from') == payment_key and e.get('to') == target
                   and e.get('relation') == 'BOOKING_REQUESTED'
                   and e.get('evidenceRef', '').startswith('transaction:' + self.args.transaction_id + ':')
                   for e in edges):
            return False
        payment = nodes.get(payment_key)
        if not payment or payment.get('sourceSystem') != 'domestic-payment':
            return False  # A complaint's Transaction reference node is never payment proof.
        require(payment.get('sourceRef') == self.args.payment_id and payment.get('type') == 'PAYMENT',
                'independent-payment-identity')
        stages = [e for e in edges if e.get('from') == payment_key
                  and e.get('evidenceRef', '').startswith('domestic-payment:' + self.args.payment_id + ':')]
        return any(nodes.get(e.get('to'), {}).get('sourceSystem') == 'domestic-payment'
                   and nodes[e['to']].get('label') == 'Domestic payment · ' + self.payment_status
                   and nodes[e['to']].get('sourceVersion') == e.get('sourceVersion')
                   and e.get('sourceVersion', 0) > 0 for e in stages)

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
        transaction = self.request('transaction', 'reader', 'GET',
                                   '/api/v1/transactions/' + self.args.transaction_id,
                                   investigation=False)[1]
        require(transaction.get('id') == self.args.transaction_id
                and transaction.get('sourceAccountId') == self.args.account_id,
                'authoritative-transaction-source')
        self.checks.append('authoritative-transaction-source:PASS')
        payment = self.request('payment', 'reader', 'GET',
                               '/api/v1/domestic-payments/' + self.args.payment_id,
                               investigation=False)[1]
        require(payment.get('id') == self.args.payment_id
                and payment.get('debtorAccountId') == self.args.account_id
                and payment.get('status') in ('RECEIVED', 'VALIDATED', 'SENT_TO_CLEARING',
                                              'SETTLED', 'RETURNED', 'REJECTED', 'CANCELLED'),
                'authoritative-payment-source')
        self.payment_status = payment['status']
        self.checks.append('authoritative-payment-source:PASS')
        source = self.request('dispute', 'maker', 'POST', '/api/v1/complaints', {
            'category': 'PAYMENT_SERVICE', 'channel': 'APP',
            'description': 'Synthetic Context acceptance fixture',
            'accountId': self.args.account_id, 'transactionId': self.args.transaction_id,
        }, expected=(201,), investigation=False)[1]
        self.complaint_id = canonical_uuid(source['id'])
        self.reference = source['reference']
        require(isinstance(self.reference, str) and re.fullmatch(r'[A-Za-z0-9._:-]+', self.reference),
                'source-reference-contract')
        require(source.get('status') == 'RECEIVED' and source.get('aggregateRevision', 0) > 0
                and source.get('transactionId') == self.args.transaction_id
                and source.get('accountId') == self.args.account_id, 'source-created-contract')
        proposal = self.request('context', 'maker', 'POST', '/api/v1/context/assignment-proposals', {
            'principalId': self.reader, 'caseId': self.args.case_id, 'purpose': PURPOSE,
            'rootRef': 'complaint:' + self.reference,
            'validTo': (datetime.datetime.now(datetime.timezone.utc) + datetime.timedelta(hours=1)).isoformat(),
        }, expected=(201,), investigation=False)[1]
        self.proposal = canonical_uuid(proposal['id'])
        self.request('context', 'maker', 'PATCH',
                     '/api/v1/context/assignment-proposals/' + self.proposal,
                     {'approve': True}, expected=(409,), investigation=False)
        self.checks.append('maker-self-approval-denied:PASS')
        result = self.request('context', 'checker', 'PATCH',
                              '/api/v1/context/assignment-proposals/' + self.proposal,
                              {'approve': True}, investigation=False)[1]
        if result.get('assignmentId'):
            self.assignment = canonical_uuid(result['assignmentId'])
        require(result.get('status') == 'APPROVED' and self.assignment, 'assignment-approval')
        self.proposal = None
        self.checks.append('maker-checker-exact-root:PASS')
        self.poll(lambda: self.verify_graph(self.graph(expected=(200, 404)), source),
                  'source-created-and-independent-payment-projection')
        for role in ('maker', 'denied'):
            self.graph(role, expected=(403,))
        self.checks.append('unassigned-admin-nonadmin-denied:PASS')
        self.graph(purpose='FRAUD_INVESTIGATION', expected=(403,))
        self.graph(case_id='unassigned-' + str(uuid.uuid4()), expected=(403,))
        other = self.request('dispute', 'maker', 'POST', '/api/v1/complaints', {
            'category': 'PAYMENT_SERVICE', 'channel': 'APP',
            'description': 'Synthetic unassigned Context root fixture',
            'accountId': self.args.account_id, 'transactionId': self.args.transaction_id,
        }, expected=(201,), investigation=False)[1]
        other_reference = other.get('reference')
        require(isinstance(other_reference, str)
                and re.fullmatch(r'[A-Za-z0-9._:-]+', other_reference)
                and other_reference != self.reference, 'distinct-source-complaint-root')
        self.graph(reference=other_reference, expected=(403,))
        self.checks.append('assigned-reader-wrong-purpose-case-root-denied:PASS')
        historical_at = source['updatedAt']
        self.historical_at = historical_at
        updated = self.request('dispute', 'maker', 'POST',
                               '/api/v1/complaints/' + self.complaint_id + '/interim-reply',
                               {'reason': 'Synthetic acceptance interim reply'}, investigation=False)[1]
        require(updated.get('aggregateRevision', 0) > source['aggregateRevision']
                and updated.get('status') == 'RECEIVED' and updated.get('interimReplyAt')
                and updated.get('updatedAt') > source['updatedAt'], 'source-lifecycle-contract')
        self.poll(lambda: self.verify_graph(self.graph(), updated), 'source-lifecycle-projected')
        require(self.verify_graph(self.graph(as_of=historical_at), source), 'historical-source-revision')
        self.checks.append('historical-source-revision:PASS')

    def cleanup(self):
        try:
            if self.assignment:
                self.request('context', 'checker', 'DELETE',
                             '/api/v1/context/assignment-proposals/assignments/' + self.assignment,
                             expected=(204,), investigation=False)
                self.graph(expected=(403,))
                if getattr(self, 'historical_at', None):
                    self.graph(as_of=self.historical_at, expected=(403,))
                self.checks.append('revoked-current-and-historical-access-denied:PASS')
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
    parser.add_argument('--dispute-url', required=True)
    parser.add_argument('--transaction-id', required=True)
    parser.add_argument('--account-id', required=True)
    parser.add_argument('--transaction-url', required=True, help='Authoritative TransactionService HTTPS origin')
    parser.add_argument('--payment-id', required=True, help='Actual domestic payment UUID; never substituted for transaction-id')
    parser.add_argument('--payment-url', required=True, help='Authoritative domestic payment HTTPS origin')
    parser.add_argument('--case-id', required=True)
    parser.add_argument('--tokens-json', required=True)
    parser.add_argument('--ca-file', help='Private CA bundle; system trust used when omitted')
    parser.add_argument('--client-cert')
    parser.add_argument('--client-key')
    args = parser.parse_args()
    harness = None
    failed = False
    try:
        args.case_id = canonical_uuid(args.case_id)
        args.transaction_id = canonical_uuid(args.transaction_id)
        args.account_id = canonical_uuid(args.account_id)
        args.payment_id = canonical_uuid(args.payment_id)
        require(args.transaction_id != args.payment_id, 'distinct-payment-and-transaction-required')
        require(bool(args.client_cert) == bool(args.client_key), 'client-certificate-pair-required')
        for value in (args.context_url, args.dispute_url, args.payment_url, args.transaction_url):
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
