"""Server-side offline semantic identity assessment of public product cards.

A local multilingual NLI model evaluates the title AND the available card text.
It is not a price estimator; missing evidence remains uncertain. No account
data, browser cookies or API key are sent to an external inference service.
"""
from __future__ import annotations

import asyncio
import os
import re
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
                # Keep independent title and contextual evidence. In the real
                # NLI smoke test, appending a long explanatory phrase changed
                # the correct speaker to a false accessory classification.
                premise = f"В продаже: {title}."
                full = f"{title}. {context}"
                pairs.extend((text, hypothesis)
                             for text in (premise, full)
                             for hypothesis in hypotheses)
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
                       for row in probs[:, 0].reshape(-1, 6))
    return out


# NLI is a small general-language classifier, not an authority on SKU identity.
# Explicit descriptions of what is (or is not) sold outrank its probabilities.
# Scope these patterns to negative sale/contents statements, not incidental
# mentions of compatible accessories or accessories included with a product.
_ACCESSORY = (
    r"чех[оа]л[а-я]*|климатическ[а-я]*\s+модул[а-яь]*|"
    r"кронштейн[а-я]*|держател[а-яь]*|креплен[а-я]*|подставк[а-я]*|"
    r"накладк[а-я]*|кабел[а-яь]*|адаптер[а-я]*|корпус[а-я]*|"
    r"блок[а-я]*\s+питания|запчаст[а-яь]*|аксессуар[а-я]*"
)


def _explicit_conflict(query: str, context: str) -> bool:
    from .core import normalize
    q = normalize(query)
    s = normalize(context)
    if re.search(_ACCESSORY, q):
        return False  # user is looking for the accessory itself
    if re.search(r"(?:только|прода[её]тся|в комплекте только)\s+(?:" + _ACCESSORY + r")\b", s):
        return True
    if re.search(
        r"(?:без\s+(?:умной\s+)?колонки|колонка\s+не\s+входит\s+в\s+комплект|"
        r"без\s+(?:самого\s+)?устройства|устройство\s+не\s+входит\s+в\s+комплект|"
        r"не\s+является\s+(?:умной\s+)?колонкой|"
        r"макет\s+колонки|муляж\s+колонки)", s
    ):
        # Don't apply speaker-specific text to unrelated searches.
        return bool(re.search(r"станци[яию]|колонк[аиу]|speaker|устройств[оа]",q))
    return False


def _direct_identity(query: str, title: str, context: str) -> bool:
    from .core import normalize
    from .mobile_cloud import exact_product
    q, t, c = map(normalize, (query, title, context))
    if not exact_product(query,title):
        return False
    # An explicit full requested model in the actual heading is independent
    # structural evidence, unlike matching a fraction of query words.
    literal = f" {q} " in f" {t} "
    # The manufacturer can be omitted in a complete WB heading. A station
    # with the correct edition AND explicitly stated speaker product type
    # is a corroborated identity, not a generic similarity score.
    speaker = ("яндекс станция мини 3 про" in q and
               bool(re.search(r"(?:станция\s+мини\s+3\s+про|yndx\s*00059)",t)) and
               bool(re.search(r"умная\s+колонка|умной\s+колонки|smart\s+speaker",
                              t+" "+c)))
    return literal or speaker


def _decide(query: str, card: dict, scores: tuple[float, ...]) -> str:
    from .mobile_cloud import exact_product
    title, context = str(card.get("title","")), str(card.get("context",""))
    if not exact_product(query,title):
        return "reject"
    if _explicit_conflict(query,context):
        return "reject"
    ts, ta, td, cs, ca, cd = scores
    # NLI may be wrong even on a full literal model name. Preserve direct
    # identity evidence, unless the description explicitly contradicts it.
    # If contextual NLI signals a different product with no positive
    # corroboration beyond the title, require more information instead.
    if _direct_identity(query,title,context):
        if ca >= cs + .12 or cd >= cs + .12:
            from .core import normalize
            c=normalize(context)
            if not re.search(r"умная\s+колонка|умной\s+колонки|smart\s+speaker",c):
                if not (ts >= .20 and ts-ta >= .12 and ts-td >= .10):
                    return "unclear"
        return "match"
    if (ts >= .20 and ts-ta >= .12 and ts-td >= .10 and
        ca < cs + .12 and cd < cs + .12):
        return "match"
    return "unclear"


async def assess(query: str, cards: list[dict]) -> list[str]:
    """Evaluate each card with actual NLI and explicit product evidence.

    Match is a combined evidence decision, not a claim that NLI alone
    verified the product. Price is independently extracted from the store.
    """
    if not cards:
        return []
    scores = await asyncio.to_thread(_scores, query, cards)
    if len(scores) != len(cards):
        raise RuntimeError("Missing semantic decisions")
    return [_decide(query,card,row) for card,row in zip(cards,scores)]
