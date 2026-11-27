"""规则库混合检索：向量召回 + jieba 分词的全文召回，RRF 合并，任一路失败降级。"""
import pathlib
from typing import Any

import pytest
from fakeredis import FakeAsyncRedis

from app.rag.rules_index import SCHEMA_VERSION, Chunk, content_version, tokenize
from tests.fakes import FakeJava, FakeReranker, InMemoryRulesIndex

RULES = """# 交易流程

## 下单

买家下单后等待卖家确认，卖家确认后订单进入待面交或待支付。

## 评价

订单完成后双方可以互相评价，评价内容会公开展示。

## 取消

卖家确认前买家可以随时取消订单，不影响信用分。

## 付款

担保订单需要在卖家确认后 15 分钟内完成付款，超时自动取消。
"""


class OrderedEmbedder:
    """向量召回结果就是写入顺序：「付款」那一节排在最后，top_k 小时向量召回拿不到它。"""

    dims = 4

    async def embed_documents(self, texts: list[str]) -> list[list[float]]:
        return [[0.1] * 4 for _ in texts]

    async def embed_query(self, text: str) -> list[float]:
        return [0.1] * 4


async def build(tmp_path: pathlib.Path, reranker: Any = None) -> InMemoryRulesIndex:
    (tmp_path / "交易流程.md").write_text(RULES, encoding="utf-8")
    rules = InMemoryRulesIndex(FakeAsyncRedis(), OrderedEmbedder(), FakeJava(), tmp_path, reranker=reranker)
    await rules.refresh()
    return rules


def test_tokenize_keeps_numbers_and_domain_words() -> None:
    words = tokenize("担保订单付款有时间限制吗？信用分低于 40 会怎样")
    assert {"担保订单", "付款", "信用分", "40"} <= set(words)
    assert not {"吗", "？", "有", "会"} & set(words)


def test_schema_version_is_part_of_content_hash() -> None:
    chunks = [Chunk("a#1", "标题", "内容")]
    assert SCHEMA_VERSION >= 2  # 加了 tokens 字段
    assert content_version(chunks, 4) != content_version(chunks, 8)


async def test_keyword_recall_finds_what_vector_misses(tmp_path: pathlib.Path) -> None:
    rules = await build(tmp_path)
    question = "担保订单要在 15 分钟内付款吗"
    assert all("15 分钟" not in d["content"] for d in await rules.vector_search(question, 2))
    result = await rules.search(question, top_k=2)
    assert "交易流程 > 付款" in [r["source"] for r in result]  # 关键词召回补上了


async def test_keyword_hits_go_through_rerank(tmp_path: pathlib.Path) -> None:
    reranker = FakeReranker("15 分钟")
    rules = await build(tmp_path, reranker=reranker)
    result = await rules.search("担保订单要在 15 分钟内付款吗", top_k=1)
    assert result[0]["source"] == "交易流程 > 付款"
    assert any("15 分钟" in d for d in reranker.calls[0][1])


async def test_vector_failure_falls_back_to_keyword(tmp_path: pathlib.Path, caplog: pytest.LogCaptureFixture) -> None:
    rules = await build(tmp_path)

    async def down(text: str) -> list[float]:
        raise RuntimeError("embedding down")

    rules._embedder.embed_query = down  # type: ignore[method-assign]
    result = await rules.search("15 分钟内付款", top_k=2)
    assert result and result[0]["source"] == "交易流程 > 付款"
    assert "规则向量召回失败" in caplog.text


async def test_keyword_failure_falls_back_to_vector(tmp_path: pathlib.Path, caplog: pytest.LogCaptureFixture,
                                                    monkeypatch: pytest.MonkeyPatch) -> None:
    rules = await build(tmp_path)

    def broken(text: str) -> list[str]:
        raise RuntimeError("tokenizer broken")

    monkeypatch.setattr("app.rag.rules_index.tokenize", broken)
    result = await rules.search("15 分钟内付款", top_k=2)
    assert [r["source"] for r in result] == ["交易流程 > 下单", "交易流程 > 评价"]  # 向量顺序
    assert "规则关键词召回失败" in caplog.text


async def test_both_failing_returns_empty_without_raising(tmp_path: pathlib.Path,
                                                          monkeypatch: pytest.MonkeyPatch) -> None:
    rules = await build(tmp_path)

    async def down(*args: Any) -> list[dict[str, Any]]:
        raise RuntimeError("redis down")

    monkeypatch.setattr(rules, "_query", down)
    assert await rules.search("付款") == []


async def test_stopword_only_question_skips_keyword_recall(tmp_path: pathlib.Path) -> None:
    rules = await build(tmp_path)
    assert await rules.keyword_search("吗？", 5) == []
