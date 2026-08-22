package openbank.settlement.activity

import rego.v1

default allow := false

allowed_activities := {
    "debitPayer",
    "creditPayee",
    "bookToLedger",
    "reverseDebit",
    "reverseCredit",
    "rejectSettlement",
}

allow if {
    input.activity in allowed_activities
}
