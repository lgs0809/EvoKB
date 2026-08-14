import gc
import os
import threading
from typing import List, Union

import torch
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel
from sentence_transformers import CrossEncoder, SentenceTransformer

app = FastAPI(title="Enterprise Knowledge Base Models")
lock = threading.RLock()

EMBED_MODEL = os.getenv("EMBED_MODEL", "Qwen/Qwen3-Embedding-0.6B")
RERANK_MODEL = os.getenv("RERANK_MODEL", "Qwen/Qwen3-Reranker-0.6B")
MEMORY_MODE = os.getenv("MODEL_MEMORY_MODE", "single").lower()
DEVICE = os.getenv("MODEL_DEVICE", "cpu")
EMBED_DTYPE = os.getenv("EMBED_MODEL_DTYPE", os.getenv("MODEL_DTYPE", "bfloat16"))
RERANK_DTYPE = os.getenv("RERANK_MODEL_DTYPE", "float32" if DEVICE == "cpu" else os.getenv("MODEL_DTYPE", "bfloat16"))

_embed = None
_rerank = None


def _cleanup():
    gc.collect()
    if torch.cuda.is_available():
        torch.cuda.empty_cache()


def _model_kwargs(dtype: str):
    # Qwen3 model configs are bfloat16. Keeping that dtype halves resident
    # weight memory on modern CPUs; if an older CPU rejects an operation,
    # PyTorch/SentenceTransformers surfaces the error rather than fabricating a
    # fallback score.
    if dtype == "float32":
        return {"torch_dtype": torch.float32}
    return {"torch_dtype": torch.bfloat16}


def get_embed():
    global _embed, _rerank
    with lock:
        if _embed is None:
            if MEMORY_MODE == "single" and _rerank is not None:
                _rerank = None
                _cleanup()
            _embed = SentenceTransformer(
                EMBED_MODEL,
                device=DEVICE,
                model_kwargs=_model_kwargs(EMBED_DTYPE),
            )
        return _embed


def get_rerank():
    global _embed, _rerank
    with lock:
        if _rerank is None:
            if MEMORY_MODE == "single" and _embed is not None:
                _embed = None
                _cleanup()
            _rerank = CrossEncoder(
                RERANK_MODEL,
                device=DEVICE,
                model_kwargs=_model_kwargs(RERANK_DTYPE),
            )
        return _rerank


class EmbeddingRequest(BaseModel):
    model: str = EMBED_MODEL
    input: Union[str, List[str]]


class RerankRequest(BaseModel):
    model: str = RERANK_MODEL
    query: str
    documents: List[str]
    top_n: int | None = None


@app.get("/health")
def health():
    return {
        "status": "ok",
        "embedding_model": EMBED_MODEL,
        "reranker_model": RERANK_MODEL,
        "memory_mode": MEMORY_MODE,
        "embedding_dtype": EMBED_DTYPE,
        "reranker_dtype": RERANK_DTYPE,
        "embedding_loaded": _embed is not None,
        "reranker_loaded": _rerank is not None,
    }


@app.post("/v1/embeddings")
def embeddings(req: EmbeddingRequest):
    texts = [req.input] if isinstance(req.input, str) else req.input
    if not texts:
        raise HTTPException(400, "input must not be empty")
    with lock:
        model = get_embed()
        try:
            vectors = model.encode(
                texts,
                normalize_embeddings=True,
                truncate_dim=1024,
                convert_to_numpy=True,
                show_progress_bar=False,
            )
        except Exception as exc:
            raise HTTPException(500, f"embedding failed: {exc}") from exc
    return {
        "object": "list",
        "model": EMBED_MODEL,
        "data": [
            {"object": "embedding", "index": i, "embedding": v.tolist()}
            for i, v in enumerate(vectors)
        ],
    }


@app.post("/v1/rerank")
def rerank(req: RerankRequest):
    if not req.documents:
        raise HTTPException(400, "documents must not be empty")
    with lock:
        model = get_rerank()
        pairs = [(req.query, doc) for doc in req.documents]
        try:
            scores = model.predict(
                pairs,
                activation_fn=torch.nn.Sigmoid(),
                show_progress_bar=False,
            )
        except Exception as exc:
            raise HTTPException(500, f"rerank failed: {exc}") from exc
    ranked = sorted(
        ({"index": i, "relevance_score": float(score)} for i, score in enumerate(scores)),
        key=lambda x: x["relevance_score"],
        reverse=True,
    )
    if req.top_n is not None:
        ranked = ranked[: req.top_n]
    return {"model": RERANK_MODEL, "results": ranked}
