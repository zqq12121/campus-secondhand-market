"""外置 embedding / 重排序服务（text-embeddings-inference）：用 httpx.MockTransport 模拟 TEI。"""
import json

import httpx
import pytest

from app.main import build_embedder, build_reranker
from app.rag.embedder import TEI_BATCH_SIZE, HttpEmbedder
from app.rag.reranker import HttpReranker, rerank
from tests.fakes import settings

DIMS = 3


class FakeTEI:
    def __init__(self) -> None:
        self.requests: list[tuple[str, dict]] = []

    def handler(self, request: httpx.Request) -> httpx.Response:
        body = json.loads(request.content)
        self.requests.append((request.url.path, body))
        if request.url.path == "/embed":
            return httpx.Response(200, json=[[float(len(t)), 0.0, 1.0] for t in body["inputs"]])
        if request.url.path == "/rerank":
            # 与 TEI 一致：按分数降序返回 index + score
            scored = [{"index": i, "score": 1.0 if "退款" in t else 0.1 * i} for i, t in enumerate(body["texts"])]
            return httpx.Response(200, json=sorted(scored, key=lambda x: -x["score"]))
        return httpx.Response(404)

    def transport(self) -> httpx.MockTransport:
        return httpx.MockTransport(self.handler)


async def test_embedder_probes_dims_and_sends_tei_body() -> None:
    tei = FakeTEI()
    emb = await HttpEmbedder.create("http://tei/", "检索：", transport=tei.transport())
    assert emb.dims == DIMS
    assert tei.requests[0] == ("/embed", {"inputs": ["维度探测"], "normalize": True, "truncate": True})

    docs = await emb.embed_documents(["台灯", "显示器"])
    assert docs == [[2.0, 0.0, 1.0], [3.0, 0.0, 1.0]]
    assert tei.requests[-1][1]["inputs"] == ["台灯", "显示器"]  # 文档不加前缀
    await emb.aclose()


async def test_embedder_query_uses_instruction_prefix() -> None:
    tei = FakeTEI()
    emb = await HttpEmbedder.create("http://tei", "为这个句子生成表示以用于检索相关文章：", transport=tei.transport())
    vec = await emb.embed_query("台灯")
    assert tei.requests[-1][1]["inputs"] == ["为这个句子生成表示以用于检索相关文章：台灯"]
    assert vec == [float(len("为这个句子生成表示以用于检索相关文章：台灯")), 0.0, 1.0]


async def test_embedder_batches_documents() -> None:
    tei = FakeTEI()
    emb = await HttpEmbedder.create("http://tei", "", transport=tei.transport())
    texts = [f"t{i}" for i in range(TEI_BATCH_SIZE * 2 + 1)]
    assert len(await emb.embed_documents(texts)) == len(texts)
    assert [len(b["inputs"]) for _, b in tei.requests[1:]] == [TEI_BATCH_SIZE, TEI_BATCH_SIZE, 1]


async def test_reranker_maps_tei_index_order_back_to_docs() -> None:
    tei = FakeTEI()
    rr = HttpReranker("http://tei", transport=tei.transport())
    docs = ["其他", "能退款", "别的"]
    scores = await rr.scores("退款", docs)
    assert scores == pytest.approx([0.0, 1.0, 0.2])  # 与 docs 对齐，不是 TEI 返回的降序
    assert tei.requests[0] == ("/rerank", {"query": "退款", "texts": docs, "truncate": True})
    assert await rerank(rr, "退款", docs) == [1, 2, 0]
    assert await rr.scores("退款", []) == []
    assert len(tei.requests) == 2  # 空候选不发请求


async def test_reranker_http_error_raises_for_existing_fallback() -> None:
    rr = HttpReranker("http://tei", transport=httpx.MockTransport(lambda r: httpx.Response(503)))
    with pytest.raises(httpx.HTTPStatusError):
        await rr.scores("q", ["a"])


async def test_settings_choose_http_or_local(monkeypatch: pytest.MonkeyPatch) -> None:
    async def fake_create(url: str, instruction: str) -> str:
        return f"http:{url}"

    monkeypatch.setattr(HttpEmbedder, "create", staticmethod(fake_create))
    assert await build_embedder(settings(embedding_service_url="http://tei-embed")) == "http:http://tei-embed"
    assert isinstance(await build_reranker(settings(rerank_service_url="http://tei-rerank")), HttpReranker)
    # 本地模型分支要下载模型，不在单测里加载
    assert await build_reranker(settings(rerank_model="")) is None
