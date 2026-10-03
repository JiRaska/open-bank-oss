#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""Reject vacuous DR trial-balance results; this does not prove retained source data or RPO."""
from decimal import Decimal
from fractions import Fraction
import json
import sys


def amount(value):
    if type(value) not in (int, Decimal):
        raise ValueError('trial balance amounts must be JSON numbers')
    value = Decimal(value)
    if not value.is_finite() or value < 0:
        raise ValueError('trial balance amounts must be finite and nonnegative')
    # Fraction preserves exact decimal sums regardless of Decimal context precision.
    return Fraction(value)


def validate(body, fiscal_year):
    if not isinstance(body, dict) or body.get('balanced') is not True:
        raise ValueError('trial balance must report the JSON boolean balanced=true')
    if type(body.get('fiscalYear')) is not int or body['fiscalYear'] != fiscal_year:
        raise ValueError('trial balance does not match the requested fiscal year')
    if type(body.get('accountCount')) is not int or body['accountCount'] < 1:
        raise ValueError('trial balance contains no account activity')
    debit, credit = (amount(body.get(key)) for key in ('totalDebit', 'totalCredit'))
    if debit <= 0 or debit != credit:
        raise ValueError('trial balance totals must be finite, positive and equal')
    sections = body.get('sections')
    if not isinstance(sections, list) or not sections:
        raise ValueError('trial balance must include account lines')
    by_currency = {}
    accounts = set()
    seen = set()
    for section in sections:
        if not isinstance(section, dict) or not isinstance(section.get('lines'), list):
            raise ValueError('trial balance section must include account lines')
        for line in section['lines']:
            if not isinstance(line, dict):
                raise ValueError('invalid trial balance account line')
            account, currency = line.get('glAccountId'), line.get('currency')
            if not isinstance(account, str) or not account.strip() or not isinstance(currency, str) or not currency.strip():
                raise ValueError('account lines require an identity and currency')
            if (account, currency) in seen:
                raise ValueError('duplicate account/currency line')
            seen.add((account, currency))
            accounts.add(account)
            totals = by_currency.setdefault(currency, [Fraction(0), Fraction(0)])
            totals[0] += amount(line.get('totalDebit'))
            totals[1] += amount(line.get('totalCredit'))
    if len(accounts) != body['accountCount']:
        raise ValueError('account count disagrees with trial balance lines')
    if any(d != c for d, c in by_currency.values()):
        raise ValueError('trial balance does not balance within every currency')
    if sum(d for d, _ in by_currency.values()) != debit or sum(c for _, c in by_currency.values()) != credit:
        raise ValueError('trial balance totals disagree with account lines')


def main():
    try:
        if len(sys.argv) != 2:
            raise ValueError('expected fiscal year argument')
        body = json.load(sys.stdin, parse_float=Decimal, parse_constant=Decimal)
        validate(body, int(sys.argv[1]))
    except (ValueError, ArithmeticError) as error:
        # Never print the response: it may contain restored financial data.
        print(f'::error::DR trial-balance verification failed: {error}', file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
