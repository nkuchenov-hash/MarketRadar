#!/usr/bin/env python3
"""Prepare bundled local model assets from a pinned, public ONNX model.

Not an API proxy. Runs only at build time and keeps BOTH the trained ONNX
weights and tokenizer vocabulary inside the signed Android APK.
Source: onnx-community/multilingual-MiniLMv2-L6-mnli-xnli-ONNX
Original: MoritzLaurer/multilingual-MiniLMv2-L6-mnli-xnli (MIT).
"""
import hashlib
import json
import os
import struct
import sys
from pathlib import Path

if len(sys.argv) != 3:
    raise SystemExit("Usage: prepare_nli_assets.py TOKENIZER_JSON OUTPUT_DIR")

input_file = Path(sys.argv[1])
dest = Path(sys.argv[2])
dest.mkdir(parents=True, exist_ok=True)
raw = input_file.read_bytes()
tokenizer = json.loads(raw)
model = tokenizer["model"]
if model["type"] != "Unigram" or len(model["vocab"]) < 200000:
    raise SystemExit("Unexpected tokenizer type, refusing to build")
pieces = model["vocab"]
out = dest / "model-vocab.bin"
with out.open("wb") as f:
    f.write(b"MRU1")
    f.write(struct.pack(">I", len(pieces)))
    for piece, score in pieces:
        data = piece.encode("utf-8")
        if len(data) >= 65536:
            raise SystemExit("Tokenizer piece too long")
        f.write(struct.pack(">H", len(data)))
        f.write(data)
        f.write(struct.pack(">f", float(score)))
print(f"Prepared {len(pieces)} SentencePiece Unigram tokens ({out.stat().st_size} bytes)")
print("Tokenizer sha256", hashlib.sha256(raw).hexdigest())
