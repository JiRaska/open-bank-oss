"""Independent reference oracle for the treasury cash-flow ledger (ADR-0344 D9).

Pure Python, stdlib only, `decimal` only — no float anywhere. Written from the deal contract
(LegSpec / CashFlow) and published market conventions (ISDA 2006 Definitions section 4.16 day
counts and section 4.12 business-day conventions, standard money-market and FX conventions). It
deliberately shares NO code with the Kotlin implementation it checks: it must not be derived
from `openbank-libs-finance`, the treasury service or the risk engine, otherwise a fixture would
only prove that the code agrees with itself.

Conventions this oracle fixes where the contract leaves a choice are listed in
`../resources/parity/README.md`; each fixture also repeats the ones it exercises in its
`sourceNote`.
"""

from __future__ import annotations

import calendar as _cal
import datetime as _dt
from decimal import ROUND_HALF_UP, Decimal, getcontext

getcontext().prec = 34

ZERO = Decimal("0")
ONE = Decimal("1")
HUNDRED = Decimal("100")
CENT = Decimal("0.01")

PRODUCTS = (
    "CASH", "MM_PLACEMENT", "MM_BORROWING", "CNB_DEPOSIT_FACILITY", "CNB_LOMBARD", "FX_SPOT",
    "TREASURY_BILL", "BOND", "BILL_OF_EXCHANGE", "LOAN", "FX_FORWARD", "FX_SWAP", "FRA", "IRS",
    "REPO", "REVERSE_REPO",
)


class OracleError(ValueError):
    """A deal the oracle cannot evaluate under its stated conventions."""


# --------------------------------------------------------------------------------------------
# Dates and calendars
# --------------------------------------------------------------------------------------------

def d(text: str) -> _dt.date:
    return _dt.date.fromisoformat(text)


def easter_sunday(year: int) -> _dt.date:
    """Gregorian Easter Sunday — the anonymous Gregorian computus (Meeus/Jones/Butcher)."""
    a = year % 19
    b, c = divmod(year, 100)
    dd, e = divmod(b, 4)
    f = (b + 8) // 25
    g = (b - f + 1) // 3
    h = (19 * a + b - dd - g + 15) % 30
    i, k = divmod(c, 4)
    ll = (32 + 2 * e + 2 * i - h - k) % 7
    m = (a + 11 * h + 22 * ll) // 451
    month, day = divmod(h + ll - 7 * m + 114, 31)
    return _dt.date(year, month, day + 1)


def _target2(year: int) -> set:
    es = easter_sunday(year)
    return {
        _dt.date(year, 1, 1), es - _dt.timedelta(days=2), es + _dt.timedelta(days=1),
        _dt.date(year, 5, 1), _dt.date(year, 12, 25), _dt.date(year, 12, 26),
    }


def _certis(year: int) -> set:
    """Czech public holidays (Act 245/2000 Coll.) — the days CZK interbank clearing is closed."""
    es = easter_sunday(year)
    fixed = [(1, 1), (5, 1), (5, 8), (7, 5), (7, 6), (9, 28), (10, 28), (11, 17),
             (12, 24), (12, 25), (12, 26)]
    days = {_dt.date(year, mo, dy) for mo, dy in fixed}
    days.add(es + _dt.timedelta(days=1))  # Easter Monday
    days.add(es - _dt.timedelta(days=2))  # Good Friday (a public holiday since 2016)
    return days


_HOLIDAYS = {"TARGET2": _target2, "CERTIS": _certis, "WEEKEND_ONLY": lambda year: set()}


def is_holiday(day: _dt.date, cals) -> bool:
    for code in cals:
        if code not in _HOLIDAYS:
            raise OracleError(f"unknown calendar {code}")
        if day in _HOLIDAYS[code](day.year):
            return True
    return False


def is_business_day(day: _dt.date, cals) -> bool:
    """Joint calendar: a day is good only if it is good in EVERY listed calendar."""
    return day.weekday() < 5 and not is_holiday(day, cals)


def _step(day, cals, delta):
    day += _dt.timedelta(days=delta)
    while not is_business_day(day, cals):
        day += _dt.timedelta(days=delta)
    return day


def adjust(day: _dt.date, convention: str, cals) -> _dt.date:
    """ISDA 2006 section 4.12 business-day conventions."""
    if convention == "UNADJUSTED" or is_business_day(day, cals):
        return day
    if convention == "FOLLOWING":
        return _step(day, cals, 1)
    if convention == "PRECEDING":
        return _step(day, cals, -1)
    if convention == "MODIFIED_FOLLOWING":
        f = _step(day, cals, 1)
        return f if f.month == day.month else _step(day, cals, -1)
    if convention == "MODIFIED_PRECEDING":
        p = _step(day, cals, -1)
        return p if p.month == day.month else _step(day, cals, 1)
    raise OracleError(f"unknown business-day convention {convention}")


def add_business_days(day: _dt.date, n: int, cals) -> _dt.date:
    """Move n good days (n < 0 moves back). n == 0 returns the day itself."""
    step = 1 if n > 0 else -1
    for _ in range(abs(n)):
        day = _step(day, cals, step)
    return day


def is_month_end(day: _dt.date) -> bool:
    return day.day == _cal.monthrange(day.year, day.month)[1]


def add_months(anchor: _dt.date, months: int, eom: bool) -> _dt.date:
    """Calendar-month shift from an ANCHOR (never iterated, so day 31 does not decay to 28)."""
    total = anchor.year * 12 + anchor.month - 1 + months
    year, month = divmod(total, 12)
    month += 1
    last = _cal.monthrange(year, month)[1]
    day = last if (eom and is_month_end(anchor)) else min(anchor.day, last)
    return _dt.date(year, month, day)


# --------------------------------------------------------------------------------------------
# Day counts (ISDA 2006 section 4.16)
# --------------------------------------------------------------------------------------------

def _thirty(y1, m1, d1, y2, m2, d2):
    return Decimal(360 * (y2 - y1) + 30 * (m2 - m1) + (d2 - d1)) / Decimal(360)


def year_fraction(start: _dt.date, end: _dt.date, day_count: str) -> Decimal:
    if end < start:
        raise OracleError("accrual end before start")
    days = Decimal((end - start).days)
    if day_count == "ACT/360":
        return days / Decimal(360)
    if day_count == "ACT/365F":
        return days / Decimal(365)
    if day_count == "ACT/ACT_ISDA":
        total = ZERO
        cursor = start
        while cursor < end:
            boundary = min(_dt.date(cursor.year + 1, 1, 1), end)
            basis = 366 if _cal.isleap(cursor.year) else 365
            total += Decimal((boundary - cursor).days) / Decimal(basis)
            cursor = boundary
        return total
    if day_count == "30/360":  # Bond Basis, 4.16(f)
        d1 = 30 if start.day == 31 else start.day
        d2 = 30 if (end.day == 31 and d1 > 29) else end.day
        return _thirty(start.year, start.month, d1, end.year, end.month, d2)
    if day_count == "30E/360":  # Eurobond Basis, 4.16(g)
        d1 = 30 if start.day == 31 else start.day
        d2 = 30 if end.day == 31 else end.day
        return _thirty(start.year, start.month, d1, end.year, end.month, d2)
    raise OracleError(f"unknown day count {day_count}")


# --------------------------------------------------------------------------------------------
# Number formatting
# --------------------------------------------------------------------------------------------

def money(x: Decimal) -> Decimal:
    return x.quantize(CENT, rounding=ROUND_HALF_UP)


def fmt_amount(x: Decimal) -> str:
    return str(money(x))


def fmt_rate(x: Decimal) -> str:
    """Rates are kept exact; padded to at least 4 dp so `3.61` and `3.6100` print alike."""
    x = Decimal(x)
    if x.as_tuple().exponent > -4:
        x = x.quantize(Decimal("0.0001"))
    return format(x, "f")


def dec(text) -> Decimal:
    if isinstance(text, float):
        raise OracleError("floats are not accepted — pass decimal strings")
    return Decimal(str(text))


# --------------------------------------------------------------------------------------------
# Schedules
# --------------------------------------------------------------------------------------------

_FREQ_MONTHS = {"1M": 1, "3M": 3, "6M": 6, "12M": 12}
# The service's LegSpec frequency names (the fixture wire form) -> the oracle's tenor tokens.
_WIRE_FREQ = {"ONCE": "TERM", "MONTHLY": "1M", "QUARTERLY": "3M", "SEMI_ANNUAL": "6M", "ANNUAL": "12M"}


def freq_of(text: str) -> str:
    return _WIRE_FREQ.get(text, text)


def periods_per_year(freq: str) -> int:
    freq = freq_of(freq)
    if freq not in _FREQ_MONTHS:
        raise OracleError(f"no periods-per-year for frequency {freq}")
    return 12 // _FREQ_MONTHS[freq]


def unadjusted_schedule(start, end, freq, stub, eom=False):
    """Unadjusted period boundaries.

    stub NONE / SHORT_FINAL / LONG_FINAL roll FORWARD from start; SHORT_INITIAL / LONG_INITIAL
    roll BACKWARD from end. A LONG stub merges the would-be short stub into its neighbour. NONE
    refuses a schedule that does not divide evenly.
    """
    freq = freq_of(freq)
    if freq == "TERM" and end == start:
        return [start, end]  # an exchange-only leg settling on one day
    if end <= start:
        raise OracleError("schedule end must be after start")
    if freq == "TERM":
        return [start, end]
    m = _FREQ_MONTHS[freq]
    backward = stub in ("SHORT_INITIAL", "LONG_INITIAL")
    if not backward:
        dates = [start]
        k = 1
        while (nxt := add_months(start, k * m, eom)) < end:
            dates.append(nxt)
            k += 1
        regular = add_months(start, (len(dates)) * m, eom) == end
        dates.append(end)
        if not regular:
            if stub == "NONE":
                raise OracleError("schedule has a stub but stub is NONE")
            if stub == "LONG_FINAL" and len(dates) > 2:
                del dates[-2]
        return dates
    dates = [end]
    k = 1
    while (prv := add_months(end, -k * m, eom)) > start:
        dates.insert(0, prv)
        k += 1
    regular = add_months(end, -(len(dates)) * m, eom) == start
    dates.insert(0, start)
    if not regular and stub == "LONG_INITIAL" and len(dates) > 2:
        del dates[1]
    return dates


# --------------------------------------------------------------------------------------------
# Legs -> cash flows
# --------------------------------------------------------------------------------------------

def _flip(direction):
    return "IN" if direction == "OUT" else "OUT"


def _flow(leg_index, period_index, kind, direction, currency, payment_date, notional, amount,
          accrual_start=None, accrual_end=None, fixing_date=None, rate=None, rate_source=None):
    return {
        "legIndex": leg_index, "periodIndex": period_index, "kind": kind, "direction": direction,
        "currency": currency,
        "accrualStart": accrual_start.isoformat() if accrual_start else None,
        "accrualEnd": accrual_end.isoformat() if accrual_end else None,
        "fixingDate": fixing_date.isoformat() if fixing_date else None,
        "paymentDate": payment_date.isoformat(),
        "notional": fmt_amount(notional) if notional is not None else None,
        "rate": fmt_rate(rate) if rate is not None else None,
        "rateSource": rate_source,
        "amount": fmt_amount(amount) if amount is not None else None,
    }


def _fixing_lookup(fixings):
    out = {}
    for f in fixings or []:
        out[(f["index"], f["date"])] = dec(f["rate"])
    return out


def _clamp(x, floor, cap):
    if floor is not None and x < floor:
        x = floor
    if cap is not None and x > cap:
        x = cap
    return x


def leg_periods(deal, leg):
    """Period boundaries for one leg: (unadjusted, accrual, payment) date lists."""
    cals = leg.get("paymentCalendars") or deal.get("calendars") or ["WEEKEND_ONLY"]
    conv = deal.get("businessDayConvention", "MODIFIED_FOLLOWING")
    if freq_of(leg.get("accrualFrequency", "TERM")) != freq_of(leg.get("paymentFrequency", leg.get("accrualFrequency", "TERM"))):
        raise OracleError("accrualFrequency != paymentFrequency (compounding) is out of scope")
    unadj = unadjusted_schedule(d(leg["accrualStart"]), d(leg["accrualEnd"]),
                                leg.get("accrualFrequency", "TERM"), leg.get("stub", "NONE"),
                                leg.get("endOfMonth", False))
    if leg.get("adjustAccrualPeriod", False):
        accrual = [adjust(x, conv, cals) for x in unadj]
    else:
        accrual = list(unadj)
    lag = int(leg.get("paymentLagBusinessDays", 0))
    payment = []
    for x in unadj[1:]:
        if leg.get("adjustPaymentDate", True):
            payment.append(add_business_days(adjust(x, conv, cals), lag, cals))
        else:
            if lag:
                raise OracleError("a payment lag needs adjustPaymentDate")
            payment.append(x)
    return cals, conv, unadj, accrual, payment


def _period_rates(deal, leg, accrual_unadj, conv, cals, fixings, refdata):
    """Per-period (rate_percent|None, fixingDate|None, rateSource)."""
    spec = leg["rate"]
    n = len(accrual_unadj) - 1
    if spec["type"] == "NONE":
        return [(None, None, None)] * n
    if spec["type"] == "FIXED":
        r = dec(spec["rate"])
        return [(r, None, "FIXED")] * n
    index = spec["index"]
    idx = (refdata or {}).get("indices", {}).get(index)
    if idx is None:
        raise OracleError(f"fixture has no reference data for index {index}")
    fix_cals = [idx["fixingCalendar"]]
    lag = int(spec.get("fixingLagBusinessDays", idx.get("fixingLagBusinessDays", 2)))
    spread = dec(spec.get("spread", "0"))
    cap = dec(spec["cap"]) if spec.get("cap") is not None else None
    floor = dec(spec["floor"]) if spec.get("floor") is not None else None
    applies = spec.get("capFloorAppliesTo", "INDEX")
    until_first = spec.get("rateUntilFirstFixing")
    observed = _fixing_lookup(fixings)
    out = []
    for i in range(n):
        start = adjust(accrual_unadj[i], conv, cals)
        fdate = add_business_days(start, -lag, fix_cals)
        raw = observed.get((index, fdate.isoformat()))
        if i == 0 and until_first is not None:
            # The rate until the first fixing is the contractually agreed first-period rate, known
            # at trade: it applies to period 0 even when an index value is published for its date.
            out.append((dec(until_first), fdate, "RATE_UNTIL_FIRST_FIXING"))
        elif raw is not None:
            if applies == "INDEX":
                rate = _clamp(raw, floor, cap) + spread
            elif applies == "INDEX_PLUS_SPREAD":
                rate = _clamp(raw + spread, floor, cap)
            else:
                raise OracleError(f"unknown capFloorAppliesTo {applies}")
            out.append((rate, fdate, "OBSERVED"))
        else:
            out.append((None, fdate, "MISSING"))
    return out


def leg_flows(deal, leg_index, leg, fixings, refdata):
    cals, conv, unadj, accrual, payment = leg_periods(deal, leg)
    direction = "IN" if leg["payReceive"] == "RECEIVE" else "OUT"
    ccy = leg["currency"]
    notional = dec(leg["notional"])
    amort = (leg.get("amortisation") or {}).get("type", "BULLET")
    n = len(unadj) - 1
    rates = _period_rates(deal, leg, unadj, conv, cals, fixings, refdata)
    flows = []
    if leg.get("initialNotionalExchange", False):
        start_pay = adjust(unadj[0], conv, cals)
        flows.append(_flow(leg_index, -1, "NOTIONAL_EXCHANGE", _flip(direction), ccy, start_pay,
                           notional, notional, rate_source="FIXED"))
    outstanding = notional
    annuity = None
    if amort == "ANNUITY":
        if leg["rate"]["type"] != "FIXED":
            raise OracleError("ANNUITY needs a fixed rate")
        r = dec(leg["rate"]["rate"]) / HUNDRED / Decimal(periods_per_year(leg["paymentFrequency"]))
        annuity = money(notional * r / (ONE - (ONE + r) ** (-n)))
    elif amort == "LINEAR":
        linear = money(notional / Decimal(n))
    elif amort != "BULLET":
        raise OracleError(f"amortisation {amort} is out of the oracle's scope")
    dc = leg["dayCount"]
    no_rate = leg["rate"]["type"] == "NONE"
    for i in range(n):
        rate, fdate, source = rates[i]
        if no_rate:
            continue
        yf = year_fraction(accrual[i], accrual[i + 1], dc)
        interest = None if rate is None else money(outstanding * rate / HUNDRED * yf)
        flows.append(_flow(leg_index, i, "INTEREST", direction, ccy, payment[i], outstanding,
                           interest, accrual[i], accrual[i + 1], fdate, rate, source))
        if amort == "BULLET":
            continue
        last = i == n - 1
        if amort == "LINEAR":
            principal = outstanding if last else linear
        else:
            principal = outstanding if last else annuity - interest
        flows.append(_flow(leg_index, i, "PRINCIPAL", direction, ccy, payment[i], outstanding,
                           principal, rate_source="FIXED"))
        outstanding -= principal
    if amort == "BULLET" and leg.get("finalNotionalExchange", False):
        # An interest-bearing leg repays PRINCIPAL; an exchange-only leg (FX, cash, a discount
        # security's consideration and redemption) moves its amount as a NOTIONAL_EXCHANGE.
        kind = "NOTIONAL_EXCHANGE" if no_rate else "PRINCIPAL"
        flows.append(_flow(leg_index, n - 1, kind, direction, ccy, payment[-1],
                           notional, notional, rate_source="FIXED"))
    return flows


def legs_flows(deal, fixings, refdata):
    flows = []
    for i, leg in enumerate(deal["legs"]):
        flows.extend(leg_flows(deal, i, leg, fixings, refdata))
    return flows


# --------------------------------------------------------------------------------------------
# Products that are not a plain sum of legs
# --------------------------------------------------------------------------------------------

def exchange_leg(pay_receive, ccy, amount, day):
    """An exchange-only leg (no rate) paying or receiving [amount] once, on [day] (rolled)."""
    return {
        "payReceive": pay_receive, "currency": ccy, "notional": fmt_amount(amount),
        "amortisation": {"type": "BULLET", "schedule": []},
        "accrualStart": day.isoformat(), "accrualEnd": day.isoformat(),
        "accrualFrequency": "ONCE", "paymentFrequency": "ONCE", "stub": "NONE",
        "adjustPaymentDate": True, "adjustAccrualPeriod": False, "paymentLagBusinessDays": 0,
        "dayCount": "ACT/360", "rate": {"type": "NONE"},
        "initialNotionalExchange": False, "finalNotionalExchange": True,
    }


def _side(direction_in: bool) -> str:
    return "RECEIVE" if direction_in else "PAY"


def _cash(deal):
    return {"legs": [exchange_leg(_side(deal["direction"] == "IN"), deal["currency"], dec(deal["amount"]),
                                  d(deal["valueDate"]))]}


def spot_date(trade_date, cals, lag=2):
    return add_business_days(trade_date, lag, cals)


def fx_forward_rate(spot, base_rate, quote_rate, days, base_basis=360, quote_basis=360):
    """Covered interest parity on simple money-market rates, rounded HALF_UP to 6 dp."""
    dd = Decimal(days)
    num = ONE + dec(quote_rate) / HUNDRED * dd / Decimal(quote_basis)
    den = ONE + dec(base_rate) / HUNDRED * dd / Decimal(base_basis)
    return (dec(spot) * num / den).quantize(Decimal("0.000001"), rounding=ROUND_HALF_UP)


def _fx_pair_legs(base, quote, side, base_amount, rate, value_date):
    """The base currency is received when bought, the quote currency paid; both on [value_date]."""
    buy = side == "BUY"
    return [exchange_leg(_side(buy), base, base_amount, value_date),
            exchange_leg(_side(not buy), quote, money(base_amount * rate), value_date)]


def _fx(deal):
    base, quote = deal["currencyPair"].split("/")
    cals = deal["calendars"]
    trade = d(deal["tradeDate"])
    sd = spot_date(trade, cals)
    amount = dec(deal["baseAmount"])
    spot = dec(deal["spotRate"])
    product = deal["product"]
    if product == "FX_SPOT":
        return {"spotDate": sd.isoformat(), "legs": _fx_pair_legs(base, quote, deal["side"], amount, spot, sd)}
    far = adjust(add_months(sd, _FREQ_MONTHS[deal["tenor"]], True), "MODIFIED_FOLLOWING", cals)
    days = (far - sd).days
    fwd = fx_forward_rate(spot, deal["baseRate"], deal["quoteRate"], days)
    out = {"spotDate": sd.isoformat(), "farDate": far.isoformat(), "days": days,
           "forwardRate": fmt_rate(fwd)}
    if product == "FX_FORWARD":
        out["legs"] = _fx_pair_legs(base, quote, deal["side"], amount, fwd, far)
        return out
    far_side = "SELL" if deal["side"] == "BUY" else "BUY"
    out["legs"] = (_fx_pair_legs(base, quote, deal["side"], amount, spot, sd)
                   + _fx_pair_legs(base, quote, far_side, amount, fwd, far))
    return out


def _discount_security(deal):
    cals = deal["calendars"]
    conv = deal.get("businessDayConvention", "FOLLOWING")
    value = d(deal["valueDate"])
    maturity = adjust(d(deal["maturityDate"]), conv, cals)
    days = Decimal((maturity - value).days)
    nominal = dec(deal["nominal"])
    y = dec(deal["yield"]) / HUNDRED
    basis = Decimal(int(deal.get("basis", 360)))
    if deal["product"] == "TREASURY_BILL":
        factor = ONE - y * days / basis  # discount (bank-discount) yield
    else:
        factor = ONE / (ONE + y * days / basis)  # simple money-market yield
    price = money(nominal * factor)
    buy = deal.get("side", "BUY") == "BUY"
    ccy = deal["currency"]
    return {
        "days": int(days),
        "pricePer100": fmt_rate((HUNDRED * factor).quantize(Decimal("0.000001"), rounding=ROUND_HALF_UP)),
        # The consideration on the value date, the face at (unadjusted) maturity, rolled on payment.
        "legs": [exchange_leg(_side(not buy), ccy, price, value),
                 exchange_leg(_side(buy), ccy, nominal, d(deal["maturityDate"]))],
    }


# Products whose deal ticket the oracle derives into exchange-only legs from the deal terms.
DERIVED = {"CASH": _cash, "FX_SPOT": _fx, "FX_FORWARD": _fx, "FX_SWAP": _fx,
           "TREASURY_BILL": _discount_security, "BILL_OF_EXCHANGE": _discount_security}


def derived_legs(deal):
    """The legs a deal ticket sends for a DERIVED product (None for a leg-described product)."""
    fn = DERIVED.get(deal["product"])
    return fn(deal)["legs"] if fn else None


def _bond(deal, fixings, refdata):
    leg = deal["legs"][0]
    value = d(deal["valueDate"])
    cals, conv, unadj, accrual, payment = leg_periods(deal, leg)
    flows = [f for f in leg_flows(deal, 0, leg, fixings, refdata) if d(f["paymentDate"]) > value]
    nominal = dec(leg["notional"])
    clean = money(nominal * dec(deal["cleanPrice"]) / HUNDRED)
    accrued = ZERO
    for i in range(len(unadj) - 1):
        if accrual[i] <= value < accrual[i + 1]:
            yf = year_fraction(accrual[i], value, leg["dayCount"])
            accrued = money(nominal * dec(leg["rate"]["rate"]) / HUNDRED * yf)
    ccy = leg["currency"]
    settle = [_flow(None, None, "PRINCIPAL", "OUT", ccy, value, nominal, clean, rate_source="FIXED")]
    if accrued:
        settle.append(_flow(None, None, "INTEREST", "OUT", ccy, value, nominal, accrued, rate_source="FIXED"))
    return {"accruedInterest": fmt_amount(accrued), "flows": settle + flows}


def _fra(deal, fixings, refdata):
    """Market FRA settlement: N * (L - K) * t / (1 + L * t), paid at the period START."""
    flt = next(x for x in deal["legs"] if x["rate"]["type"] == "FLOATING")
    fix = next(x for x in deal["legs"] if x["rate"]["type"] == "FIXED")
    cals, conv, unadj, accrual, _ = leg_periods(deal, flt)
    if len(unadj) != 2:
        raise OracleError("an FRA is a single period")
    (rate, fdate, source), = _period_rates(deal, flt, unadj, conv, cals, fixings, refdata)
    start = adjust(unadj[0], conv, cals)
    notional = dec(flt["notional"])
    yf = year_fraction(accrual[0], accrual[1], flt["dayCount"])
    if rate is None:
        return {"flows": [_flow(None, 0, "INTEREST", None, flt["currency"], start, notional, None,
                                accrual[0], accrual[1], fdate, None, "MISSING")]}
    k = dec(fix["rate"]["rate"])
    raw = notional * (rate - k) / HUNDRED * yf / (ONE + rate / HUNDRED * yf)
    receive_float = flt["payReceive"] == "RECEIVE"
    direction = "IN" if (raw >= 0) == receive_float else "OUT"
    return {"flows": [_flow(None, 0, "INTEREST", direction, flt["currency"], start, notional,
                            abs(raw), accrual[0], accrual[1], fdate, rate, source)],
            "contractRate": fmt_rate(k)}


def repo_cash_amount(collateral) -> Decimal:
    nominal = dec(collateral["nominal"])
    dirty = dec(collateral["dirtyPricePercent"])
    haircut = dec(collateral["haircutPercent"])
    return money(nominal * dirty / HUNDRED * (ONE - haircut / HUNDRED))


def _repo(deal, fixings, refdata):
    col = deal["collateral"]
    cash = repo_cash_amount(col)
    leg = deal["legs"][0]
    if dec(leg["notional"]) != cash:
        raise OracleError(f"repo leg notional {leg['notional']} != collateral-derived cash {cash}")
    expected_side = "PAY" if deal["product"] == "REPO" else "RECEIVE"
    if leg["payReceive"] != expected_side:
        raise OracleError(f"{deal['product']} cash leg must be {expected_side}")
    # Repo: we deliver the security and receive cash; reverse repo the other way round.
    col_dir = "OUT" if deal["product"] == "REPO" else "IN"
    return {"collateral": {"securityIsin": col["securityIsin"], "nominal": fmt_amount(dec(col["nominal"])),
                           "direction": col_dir, "cashAmount": fmt_amount(cash)},
            "flows": legs_flows(deal, fixings, refdata)}


def compute(fixture) -> dict:
    """The oracle's `expected` block for a fixture (deal + fixings + referenceData)."""
    deal = fixture["deal"]
    fixings = fixture.get("fixings", [])
    refdata = fixture.get("referenceData", {})
    product = deal["product"]
    if product not in PRODUCTS:
        raise OracleError(f"unknown product {product}")
    if product in DERIVED:
        out = DERIVED[product](deal)
        legs = out.pop("legs")
        # FX_SPOT is a legacy product: the service derives its legs from the flat fields, so the
        # fixture carries none. Every other DERIVED product's ticket sends the legs; they must be
        # exactly the oracle's derivation, so a hand edit of a leg cannot pass.
        if product != "FX_SPOT" and deal.get("legs") != legs:
            raise OracleError(f"{product} deal legs disagree with the oracle's derivation from the deal terms")
        out["flows"] = legs_flows({**deal, "legs": legs}, fixings, refdata)
        return out
    if product == "BOND":
        return _bond(deal, fixings, refdata)
    if product == "FRA":
        return _fra(deal, fixings, refdata)
    if product in ("REPO", "REVERSE_REPO"):
        return _repo(deal, fixings, refdata)
    return {"flows": legs_flows(deal, fixings, refdata)}
