#!/usr/bin/env bash
set -euo pipefail

MODEL_VARIANT="${1:-8b}"

case "$MODEL_VARIANT" in
  8b)
    MODEL_DIR="${2:-$HOME/models/bonsai-8b}"
    BASE_URL="https://huggingface.co/prism-ml/Bonsai-8B-gguf/resolve/main"
    MODEL_FILE="Bonsai-8B.gguf"
    ;;
  1.7b)
    MODEL_DIR="${2:-$HOME/models/bonsai-1.7b-q1_0}"
    BASE_URL="https://huggingface.co/prism-ml/Bonsai-1.7B-gguf/resolve/main"
    MODEL_FILE="Bonsai-1.7B-Q1_0.gguf"
    ;;
  *)
    echo "Usage: $0 [8b|1.7b] [model_dir]" >&2
    exit 1
    ;;
esac

mkdir -p "$MODEL_DIR"

curl -L "$BASE_URL/$MODEL_FILE?download=true" \
  -o "$MODEL_DIR/$MODEL_FILE"

curl -L "$BASE_URL/NOTICE.txt?download=true" \
  -o "$MODEL_DIR/NOTICE.txt"

echo "Downloaded Bonsai assets ($MODEL_VARIANT) to: $MODEL_DIR"
