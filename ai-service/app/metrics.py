"""Prometheus 指标，经 /api/v1/ai/metrics 暴露。

标签只用取值有限的维度（接口、结果、图节点、工具名、防护类型）。不把 user_id、conversation_id 当标签：
每种标签取值组合都是一条独立的时间序列，按用户或会话打标签，序列数会随用户数无限增长（基数爆炸）。
多 worker 部署时每个进程各自计数，需要 prometheus_client 的多进程模式（PROMETHEUS_MULTIPROC_DIR）才能合并。
"""
from prometheus_client import Counter, Histogram

from app.observability import TraceCollector

_LATENCY_BUCKETS = (0.1, 0.25, 0.5, 1, 2, 3, 5, 8, 13, 20, 30, 60, 120)

# result：ok / pending（停在确认卡片）/ error / aborted（客户端中途断开）/ refused（输入防护拒答）
#         / busy（同会话上一条还在处理，409）/ rate_limited（429）
REQUESTS = Counter("ai_requests_total", "对话请求数", ["endpoint", "result"])
REQUEST_SECONDS = Histogram("ai_request_duration_seconds", "对话请求耗时（整个 SSE 流）", ["endpoint"],
                            buckets=_LATENCY_BUCKETS)
TTFT_SECONDS = Histogram("ai_ttft_seconds", "首 token 延迟：请求开始到第一个 token 事件", ["endpoint"],
                         buckets=_LATENCY_BUCKETS)
# kind：input_uncached / input_cached（命中 DeepSeek 上下文缓存）/ output
LLM_TOKENS = Counter("ai_llm_tokens_total", "模型调用 token 数", ["node", "kind"])
TOOL_SECONDS = Histogram("ai_tool_duration_seconds", "工具调用耗时", ["tool"],
                         buckets=(0.01, 0.05, 0.1, 0.25, 0.5, 1, 2, 5, 10))
# 写工具停下等用户确认（interrupted）不算报错
TOOL_ERRORS = Counter("ai_tool_errors_total", "工具调用报错次数", ["tool"])
# kind：input_blocked（输入拦截）/ tool_redacted（工具结果过滤）/ output_rewritten（输出改写）
GUARD_TRIGGERS = Counter("ai_guard_triggers_total", "安全防护触发次数", ["kind"])


def observe_trace(trace: TraceCollector) -> None:
    """把一次请求里各次模型调用、工具调用的统计计入指标。"""
    for c in trace.llm_calls:
        cached = c.get("cached_input_tokens", 0)
        LLM_TOKENS.labels(c["node"], "input_uncached").inc(c["input_tokens"] - cached)
        LLM_TOKENS.labels(c["node"], "input_cached").inc(cached)
        LLM_TOKENS.labels(c["node"], "output").inc(c["output_tokens"])
    for t in trace.tool_calls:
        TOOL_SECONDS.labels(t["name"]).observe(t["ms"] / 1000)
        if t.get("error") and t["error"] != "interrupted":
            TOOL_ERRORS.labels(t["name"]).inc()
