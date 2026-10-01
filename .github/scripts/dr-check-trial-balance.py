#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""Reject vacuous DR trial-balance results; this does not prove retained source data or RPO."""
from decimal import Decimal
import json
import sys


def validate(body, fiscal_year):
    if not isinstance(body, dict) or body.get('balanced') is not True:
        raise ValueError('trial balance must report the JSON boolean balanced=true')
    if type(body.get('fiscalYear')) is not int or body['fiscalYear'] != fiscal_year:
        raise ValueError('trial balance does not match the requested fiscal year')
    if type(body.get('accountCount')) is not int or body['accountCount'] < 1:
        raise ValueError('trial balance contains no account activity')
    totals = [body.get(key) for key in ('totalDebit', 'totalCredit')]
    if any(type(value) not in (int, Decimal) for value in totals):
        raise ValueError('trial balance totals must be JSON numbers')
    debit, credit = map(Decimal, totals)
    if not debit.is_finite() or not credit.is_finite() or debit <= 0 or debit != credit:
        raise ValueError('trial balance totals must be finite, positive and equal')


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
