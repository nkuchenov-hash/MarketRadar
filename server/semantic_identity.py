"""Server-side offline semantic identity assessment of public product cards.

A local multilingual NLI model evaluates the title AND the available card text.
It is not a price estimator; missing evidence remains uncertain. No account
data, browser cookies or API key are sent to an external inference service.
"""
from __future__ import annotations

import asyncio
import os
from functools import lru_cache
from pathlib import Path
import threading

_LOCK = threading.Lock()


def enabled(shop_id: str) -> bool:
    return shop_id in {v.strip() for v in os.getenv(
        "CLOUD_SEMANTIC_MARKETS", "").split(",") if v.strip()}


@lru_cache(maxsize=1)
def _runtime():
    import onnxruntime as ort
    from tokenizers import Tokenizer
    root = Path(os.getenv("CLOUD_SEMANTIC_MODEL_DIR", "/app/identity-model"))
    options = ort.SessionOptions()
    options.intra_op_num_threads = 2
    options.inter_op_num_threads = 1
    session = ort.InferenceSession(str(root / "model.onnx"),
                                   sess_options=options,
                                   providers=["CPUExecutionProvider"])
    tokenizer = Tokenizer.from_file(str(root / "tokenizer.json"))
    tokenizer.enable_truncation(max_length=256, strategy="only_first")
    tokenizer.enable_padding()
    return session, tokenizer


def _scores(query: str, cards: list[dict]):
    import numpy as np
    hypotheses = (
        f"Продаваемый предмет — это {query}.",
        f"Продаваемый предмет является аксессуаром или запчастью для {query}.",
        f"Продаваемый предмет является другим товаром, не {query}.",
    )
    out = []
    # One model instance, bounded CPU/memory and actual batched inference.
    with _LOCK:
        session, tokenizer = _runtime()
        names = {item.name for item in session.get_inputs()}
        for begin in range(0, len(cards), 8):
            pairs = []
            for card in cards[begin:begin+8]:
                title = str(card.get("title", ""))[:350]
                context = str(card.get("context", ""))[:1800]
                premise = f"В продаже: {title}. Информация карточки: {context}"
                pairs.extend((premise, hypothesis) for hypothesis in hypotheses)
            encoded = tokenizer.encode_batch(pairs)
            ids = np.asarray([x.ids for x in encoded], dtype=np.int64)
            mask = np.asarray([x.attention_mask for x in encoded], dtype=np.int64)
            inputs = {"input_ids": ids, "attention_mask": mask}
            if "token_type_ids" in names:
                inputs["token_type_ids"] = np.asarray(
                    [x.type_ids for x in encoded], dtype=np.int64)
            logits = session.run(None, {k: v for k, v in inputs.items() if k in names})[0]
            values = np.exp(logits - logits.max(axis=1, keepdims=True))
            probs = values / values.sum(axis=1, keepdims=True)
            out.extend(tuple(float(p) for p in row)
                       for row in probs[:, 0].reshape(-1, 3))
    return out


async def assess(query: str, cards: list[dict]) -> list[str]:
    """Return match/reject/unclear, one decision per supplied card.

    Keep structural model-number/edition validation in addition to NLI;
    similar product names alone never constitute an AI decision.
    """
    if not cards:
        return []
    from .mobile_cloud import exact_product
    scores = await asyncio.to_thread(_scores, query, cards)
    if len(scores) != len(cards):
        raise RuntimeError("Missing semantic decisions")
    results = []
    for card, (same, accessory, different) in zip(cards, scores):
        title = str(card.get("title", ""))
        if accessory >= same + .12 or different >= same + .12:
            results.append("reject")
        elif (exact_product(query, title) and same >= .20
              and same - accessory >= .12 and same - different >= .10):
            results.append("match")
        else:
            results.append("unclear")
    return results
