#!/usr/bin/env python3
"""Generate baseline-fraud-v1.onnx (ADR-0139 phase-1b).

Not a trained model — this is the exact same deterministic logistic that
BaselineFraudModel.kt computed in pure Kotlin, re-expressed as an ONNX graph so the
in-process ONNX Runtime adapter (OnnxFraudModel) can be proven end-to-end (latency,
loading, inference) before a real trained model exists (ADR-0141, phase 2 registry).
Coefficients intentionally match BaselineFraudModel byte-for-byte so behaviour is
unchanged; only the execution engine changes.

Regenerate with:
    python3 -m venv .venv && . .venv/bin/activate
    pip install onnx==1.22.0 onnxruntime==1.27.0 numpy
    python3 scripts/gen_onnx_baseline_model.py
"""

import numpy as np
import onnx
from onnx import TensorProto, helper

INTERCEPT = -4.0
WEIGHT_H1 = 0.30
WEIGHT_H24 = 0.05

OUTPUT_PATH = "src/main/resources/ml/baseline-fraud-v1.onnx"


def build_model() -> onnx.ModelProto:
    # Inputs, in this fixed order: [velocity_h1_count, velocity_h24_count].
    features = helper.make_tensor_value_info("features", TensorProto.FLOAT, [1, 2])
    risk_score = helper.make_tensor_value_info("risk_score", TensorProto.FLOAT, [1, 1])

    weight = helper.make_tensor(
        name="weight",
        data_type=TensorProto.FLOAT,
        dims=[2, 1],
        vals=np.array([[WEIGHT_H1], [WEIGHT_H24]], dtype=np.float32).flatten(),
    )
    bias = helper.make_tensor(
        name="bias",
        data_type=TensorProto.FLOAT,
        dims=[1],
        vals=np.array([INTERCEPT], dtype=np.float32),
    )

    gemm = helper.make_node("Gemm", ["features", "weight", "bias"], ["logit"], name="linear")
    sigmoid = helper.make_node("Sigmoid", ["logit"], ["risk_score"], name="sigmoid")

    graph = helper.make_graph(
        nodes=[gemm, sigmoid],
        name="baseline-fraud-v1",
        inputs=[features],
        outputs=[risk_score],
        initializer=[weight, bias],
    )
    model = helper.make_model(
        graph,
        producer_name="openbank-fraud-service/gen_onnx_baseline_model.py",
        opset_imports=[helper.make_opsetid("", 18)],
    )
    model.ir_version = 9
    onnx.checker.check_model(model)
    return model


CARD_PATH = "src/main/resources/ml/baseline-fraud-v1.card.json"


def write_card() -> None:
    """Regenerate the ADR-0141 model card so its pinned contentSha256 tracks the .onnx bytes.

    The serving adapter (OnnxFraudModel) verifies the model against this card before loading, so a
    regenerated model with a stale card would fail verification — keep them in lockstep here.
    """
    import hashlib
    import json

    with open(OUTPUT_PATH, "rb") as f:
        digest = hashlib.sha256(f.read()).hexdigest()
    card = {
        "modelId": "baseline-fraud",
        "version": "v1",
        "scope": "fraud-shadow",
        "artifact": "baseline-fraud-v1.onnx",
        "contentSha256": digest,
        "features": ["VELOCITY_TXN_COUNT_H1", "VELOCITY_TXN_COUNT_H24"],
        "provenance": {
            "kind": "deterministic-baseline",
            "generator": "openbank-fraud-service/scripts/gen_onnx_baseline_model.py",
            "trained": False,
            "note": (
                "Deterministic re-expression of BaselineFraudModel.kt (logistic: intercept -4.0, "
                "w_h1 0.30, w_h24 0.05). Not a trained model — a plumbing/latency proof for the "
                "ADR-0139/0140 shadow plane."
            ),
        },
        "metrics": {
            "note": (
                "Baseline encodes the same logistic as the rules path; parity is asserted by this "
                "script and the fraud-service parity test, not measured on held-out data."
            )
        },
        "signing": {
            "state": "digest-pinned",
            "note": (
                "This card pins the artifact content hash; the card itself is not yet cosign-signed "
                "(ADR-0141 follow-up). A trained model under a *-enforce scope must add a real "
                "detached signature before it can gate."
            ),
        },
    }
    with open(CARD_PATH, "w") as f:
        json.dump(card, f, indent=2)
        f.write("\n")
    print(f"wrote {CARD_PATH} (contentSha256={digest[:12]}…)")


def main() -> None:
    model = build_model()
    onnx.save(model, OUTPUT_PATH)
    print(f"wrote {OUTPUT_PATH}")
    write_card()

    # Sanity-check against BaselineFraudModelTest's exact assertions before shipping.
    import onnxruntime as ort

    session = ort.InferenceSession(OUTPUT_PATH)

    def score(h1: float, h24: float) -> float:
        result = session.run(["risk_score"], {"features": np.array([[h1, h24]], dtype=np.float32)})
        return float(result[0][0][0])

    bounded = score(5.0, 12.0)
    assert 0.0 < bounded < 1.0, bounded
    assert score(5.0, 12.0) == bounded, "must be deterministic"

    low, high = score(1.0, 0.0), score(25.0, 0.0)
    assert high > low, (low, high)

    missing = score(0.0, 0.0)
    assert missing < 0.05, missing

    print("parity checks vs BaselineFraudModel semantics: OK")


if __name__ == "__main__":
    main()
