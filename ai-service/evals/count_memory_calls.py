"""统计偏好抽取预判（should_extract）能省下多少次模型调用。不调用模型，不需要任何外部服务。

用法：uv run python -m evals.count_memory_calls
- 改动前：save_memory 每轮都调用一次模型抽取，调用次数 = 轮数。
- 改动后：只有 should_extract 为真的轮次才调用。
用例取 evals/ 下各评测集里的用户问题（都是找物、问规则，按「没有已有偏好」统计）；
另外用一组明确表达偏好的说法检查有没有漏抽（宁可多抽不要漏抽）。
"""
import json
import pathlib

from app.graph.memory import should_extract

EVALS = pathlib.Path(__file__).parent
# 评测集文件 → 用户问题所在的字段
SOURCES = {"rules.jsonl": "question", "retrieval.jsonl": "query", "trajectory.jsonl": "question"}
# 应该触发抽取的说法：(用户消息, 已有偏好)
PREFERENCE_SAMPLES = [
    ("我平时在贝克兰德校区", {}),
    ("我一般预算 300 以内", {}),
    ("以后都只要面交", {}),
    ("别给我推教材了", {}),
    ("我不喜欢二手耳机", {}),
    ("我对数码产品比较感兴趣", {}),
    ("我住在西区宿舍", {}),
    ("帮我记一下，我只接受担保交易", {}),
    ("不对，我已经搬到廷根了", {"campus": "贝克兰德"}),
    ("预算改成 500", {"budget": "300 以内"}),
]


def load_questions() -> list[str]:
    questions: list[str] = []
    for name, field in SOURCES.items():
        path = EVALS / name
        if path.exists():
            questions += [json.loads(line)[field] for line in path.read_text(encoding="utf-8").splitlines()
                          if line.strip()]
    return questions


def main() -> None:
    questions = load_questions()
    triggered = [q for q in questions if should_extract(q, {})]
    n = len(questions)
    print(f"评测问题 {n} 条（按没有已有偏好统计）")
    print(f"  改动前：偏好抽取调用 {n} 次（每轮 1 次）")
    print(f"  改动后：偏好抽取调用 {len(triggered)} 次（{len(triggered) / n:.0%}），省下 {n - len(triggered)} 次")
    for q in triggered:
        print(f"    触发：{q}")
    hits = [text for text, profile in PREFERENCE_SAMPLES if should_extract(text, profile)]
    print(f"偏好说法 {len(PREFERENCE_SAMPLES)} 条，触发抽取 {len(hits)} 条（召回 {len(hits) / len(PREFERENCE_SAMPLES):.0%}）")
    for text, profile in PREFERENCE_SAMPLES:
        if text not in hits:
            print(f"    漏抽：{text} {profile}")


if __name__ == "__main__":
    main()
