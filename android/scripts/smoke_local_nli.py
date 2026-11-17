#!/usr/bin/env python3
"""Run actual local ONNX model (not faked decisions) against product-identity cases.

Executed in CI before publishing. Prints scores, rejects obvious false positives.
This is a smoke check, NOT a guarantee of marketplace accuracy.
"""
import os
import sys
from pathlib import Path

import numpy as np
import onnxruntime as ort
from tokenizers import Tokenizer

model_path, tokenizer_path = sys.argv[1:3]
session = ort.InferenceSession(model_path, providers=["CPUExecutionProvider"])
tokenizer = Tokenizer.from_file(tokenizer_path)
print("model inputs:", [(x.name,x.type) for x in session.get_inputs()])
print("outputs:", [(x.name,x.type) for x in session.get_outputs()])
cases = [
  ("Яндекс Станция Мини 3 Про", "Умная колонка Яндекс Станция Мини 3 Про с Алисой Zigbee 18 Вт, синий", True),
  ("Яндекс Станция Мини 3 Про", "Климатический модуль для Станции Мини 3 Про с Алисой Яндекс", False),
  ("Яндекс Станция Мини 3 Про", "Комплект креплений для умной колонки Яндекс Станция Мини 3 Про", False),
  ("Яндекс Станция Мини 3 Про", "Умная колонка Яндекс Станция Мини 3", False),
  ("iPhone 16 Pro", "Apple iPhone 16 Pro 256 ГБ", True),
  ("iPhone 16 Pro", "Apple iPhone 16 Pro Max 256 ГБ", False),
  ("Samsung Galaxy A56", "Смартфон Samsung Galaxy A56 256 ГБ", True),
]
def inference(query,title,hypothesis):
    premise=f"В продаже: {title}."
    ids=tokenizer.encode(premise,hypothesis).ids[:112]
    data={"input_ids":np.asarray([ids],dtype=np.int64),
          "attention_mask":np.ones((1,len(ids)),dtype=np.int64)}
    names={x.name for x in session.get_inputs()}
    inputs={k:v for k,v in data.items() if k in names}
    for k in names:
        if k=="token_type_ids":inputs[k]=np.zeros((1,len(ids)),dtype=np.int64)
    logits=session.run(None,inputs)[0][0]
    raw=np.exp(logits-np.max(logits)); probs=raw/raw.sum()
    return float(probs[0])
# All scores are inferred from the actual bundled ONNX weights, not mocks.
# The Android ProductIdentityGate will apply model/version requirements to
# these scores. Standalone NLI can confuse Pro vs Pro Max, so isolated NLI
# scores must NOT be treated as proof of product identity.
golden=Path("android/app/src/main/assets/local-nli-token-golden.tsv")
scored=Path("android/app/src/main/assets/local-nli-score-golden.tsv")
with golden.open("w",encoding="utf-8") as tokens_out, \
     scored.open("w",encoding="utf-8") as score_out:
    for query,title,expected in cases:
        hypotheses=[
            f"Продаваемый предмет — это {query}.",
            f"Продаваемый предмет является аксессуаром или запчастью для {query}.",
            f"Продаваемый предмет является другим товаром, не {query}.",
        ]
        scores=[inference(query,title,h) for h in hypotheses]
        print(f"expected={expected}; ONNX scores={scores}; product={title}",flush=True)
        score_out.write(chr(9).join([query,title,str(expected).lower()]+
                         [str(v) for v in scores])+chr(10))
        for hypothesis in hypotheses:
            premise=f"В продаже: {title}."
            ids=tokenizer.encode(premise,hypothesis).ids[:112]
            tokens_out.write(chr(9).join([premise,hypothesis,
                              ",".join(map(str,ids))])+chr(10))
print("Generated exact HuggingFace reference token IDs and REAL ONNX scores",flush=True)
if golden.stat().st_size<100 or scored.stat().st_size<100:
    raise SystemExit("Missing local model smoke validation files")
