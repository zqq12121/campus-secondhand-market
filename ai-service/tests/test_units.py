from langchain_core.messages import AIMessage, HumanMessage, ToolMessage

from app.graph.builder import split_for_summary, tool_calls_this_turn
from app.rag.goods_index import build_filter, embed_text, is_listed, rrf_merge
from app.ratelimit import check_rate
from tests.fakes import FakeRedis


def test_rrf_rewards_items_found_by_both_retrievers() -> None:
    merged = rrf_merge(["a", "b", "c"], ["c", "d"])
    assert merged[0] == "c"
    assert set(merged) == {"a", "b", "c", "d"}


def test_filter_combines_campus_category_and_price() -> None:
    expr = str(build_filter("1", "101", 10, 100))
    assert "@campus_id:{1}" in expr
    assert "@category_id:{101}" in expr and "@parent_category_id:{101}" in expr
    assert "@price:[10 +inf]" in expr and "@price:[-inf 100]" in expr
    assert build_filter(None, None, None, None) is None


def test_only_on_sale_in_stock_goods_are_indexed() -> None:
    base = {"status": 2, "deleted": 0, "stock": 1}
    assert is_listed(base)
    assert not is_listed({**base, "status": 5})
    assert not is_listed({**base, "deleted": 1})
    assert not is_listed({**base, "stock": 0})


def test_embed_text_contains_category_path_and_campus() -> None:
    text = embed_text({"title": "台灯", "category_name": "灯具", "parent_category_name": "生活",
                       "campus_name": "东区", "description": "九成新"})
    assert "生活/灯具" in text and "东区" in text and "九成新" in text


def test_summary_cut_lands_on_human_message() -> None:
    msgs = []
    for i in range(8):
        msgs += [HumanMessage(f"q{i}"), AIMessage("", tool_calls=[{"name": "t", "args": {}, "id": f"c{i}"}]),
                 ToolMessage("r", tool_call_id=f"c{i}"), AIMessage(f"a{i}")]
    cut = split_for_summary(msgs, max_messages=20, keep=10)
    assert cut > 0 and isinstance(msgs[cut], HumanMessage)
    assert split_for_summary(msgs[:20], max_messages=20, keep=10) == 0


def test_tool_calls_counted_only_for_current_turn() -> None:
    msgs = [HumanMessage("1"), ToolMessage("r", tool_call_id="a"), HumanMessage("2"),
            ToolMessage("r", tool_call_id="b"), ToolMessage("r", tool_call_id="c")]
    assert tool_calls_this_turn(msgs) == 2


async def test_rate_limit_per_minute() -> None:
    redis = FakeRedis()
    results = [await check_rate(redis, "7", per_minute=2, per_day=100) for _ in range(3)]
    assert results[:2] == [None, None]
    assert results[2] is not None and 0 < results[2] <= 60
