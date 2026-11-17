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
failure=0
for query,title,expected in cases:
    same=inference(query,title,f"Продаваемый предмет — это {query}.")
    accessory=inference(query,title,f"Продаваемый предмет является аксессуаром или запчастью для {query}.")
    different=inference(query,title,f"Продаваемый предмет является другим товаром, не {query}.")
    verdict=same>=0.65 and same>=accessory+0.13 and same>=different+0.13
    print(f"case={expected}, got={verdict}, scores={same:.3f}/{accessory:.3f}/{different:.3f}: {title}",flush=True)
    if verdict!=expected:failure+=1
# Generate reference pair tokens so Android's pure-Java tokenizer is checked
# against HuggingFace tokenizers before any APK release.
golden=Path("android/app/src/main/assets/local-nli-token-golden.tsv")
with golden.open("w",encoding="utf-8") as out:
    for query,title,expected in cases:
        premise=f"В продаже: {title}."
        hypothesis=f"Продаваемый предмет — это {query}."
        ids=tokenizer.encode(premise,hypothesis).ids[:112]
        out.write(premise+"\\t"+hypothesis+"\\t"+",".join(map(str,ids))+"\\n")
print("Wrote reference XLM-R token pairs:",golden,flush=True)
print(f"Smoke-check result: {len(cases)-failure}/{len(cases)} expected",flush=True)
if failure:
    raise SystemExit(f"{failure} on-device inference cases misclassified; APK not publishable")
