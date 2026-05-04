#!/usr/bin/env bash
set -euo pipefail

MODEL_DIR="${1:-$HOME/models/bonsai-1.7b-q1_0}"
BASE_URL="https://huggingface.co/prism-ml/Bonsai-1.7B-gguf/resolve/main"

mkdir -p "$MODEL_DIR"

curl -L "$BASE_URL/Bonsai-1.7B-Q1_0.gguf?download=true" \
  -o "$MODEL_DIR/Bonsai-1.7B-Q1_0.gguf"

curl -L "$BASE_URL/NOTICE.txt?download=true" \
  -o "$MODEL_DIR/NOTICE.txt"

echo "Downloaded Bonsai 1.7B assets to: $MODEL_DIR"
