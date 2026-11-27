"""规则问答的 LLM-as-judge：把问题、检索到的片段、模型回答和参考要点交给评审模型，给出两项 0/1 评分。

- faithfulness（忠实度）：回答里的事实是否都能在检索片段里找到依据，没有编造。
- correctness（正确性）：回答是否与用例的参考要点（rules.jsonl 的 reference，摘自 knowledge/*.md）一致。

评审模型默认与 DEEPSEEK_MODEL 相同，可以用 AI_EVAL_JUDGE_MODEL 换成别的（同一个 DeepSeek 接口）。
用自己评自己有偏差：同一个模型倾向于认同自己的回答，条件允许时换更强的评审模型。

前置：同 run_trajectory（真实的图）。用法：AI_EVAL_TOKEN=<用户 token> uv run python -m evals.judge
"""
import asyncio
import json
import os
import pathlib
from typing import Any, Literal

from langchain_core.messages import HumanMessage
from langchain_core.runnables import Runnable
from pydantic import BaseModel, Field

from app.config import get_settings
from app.main import build_llm
from evals.harness import Agent
from evals.trajectory import extract

CASES = pathlib.Path(__file__).with_name("rules.jsonl")

JUDGE_PROMPT = """你是校园二手平台规则问答的评审。根据下面的材料，对助手的回答做两项评判。

问题：{question}

参考要点（来自平台规则原文）：{reference}

助手检索到的规则片段：
{snippets}

助手的回答：
{answer}

评判标准：
1. faithfulness：回答里的每一条规则事实（数字、时限、条件、结论）是否都能在「检索到的规则片段」里找到依据。
   有片段里没有的规则内容（凭常识编的、数字对不上的）给 0；全部有依据给 1。
   检索不到依据时，回答说明不确定、建议联系管理员，算 1。
2. correctness：回答的结论和关键数字是否与「参考要点」一致。结论相反、关键数字错误或缺失给 0；一致给 1。
   措辞不同、补充了参考要点之外但有依据的信息，不扣分。

每一项都先写理由，再给分。"""


class Verdict(BaseModel):
    # 字段顺序就是生成顺序：先写理由再给分，分数受理由约束，比先给分再找理由更稳
    reason: str = Field(description="判断理由：先写，指出依据或问题所在")
    score: Literal[0, 1] = Field(description="写完理由后再给分：1 通过，0 不通过")


class JudgeResult(BaseModel):
    faithfulness: Verdict = Field(description="回答是否只基于检索到的片段")
    correctness: Verdict = Field(description="回答是否与参考要点一致")


def judge_model() -> Runnable:
    s = get_settings()
    model = s.ai_eval_judge_model or s.deepseek_model
    llm = build_llm(s.model_copy(update={"deepseek_model": model}))
    return llm.with_structured_output(JudgeResult, method="function_calling")


def snippets_from(messages: list[Any]) -> list[str]:
    steps, _ = extract(messages)
    out: list[str] = []
    for s in steps:
        if s.name == "search_rules" and s.result:
            out += [f"【{r['source']}】{r['content']}" for r in json.loads(s.result).get("results", [])]
    return out


async def judge(judge_llm: Runnable, question: str, snippets: list[str], answer: str,
                reference: str) -> JudgeResult:
    prompt = JUDGE_PROMPT.format(question=question, reference=reference,
                                 snippets="\n".join(snippets) or "（没有检索到片段）", answer=answer or "（空）")
    return await judge_llm.ainvoke([HumanMessage(prompt)])


async def evaluate(token: str) -> dict[str, Any]:
    agent = await Agent.open(token)
    judge_llm = judge_model()
    cases = [json.loads(line) for line in CASES.read_text(encoding="utf-8").splitlines() if line.strip()]
    details: list[dict[str, Any]] = []
    try:
        for c in cases:
            messages = await agent.ask(c["question"])
            _, answer = extract(messages)
            r = await judge(judge_llm, c["question"], snippets_from(messages), answer, c["reference"])
            details.append({"question": c["question"], "answer": answer,
                            "faithfulness": r.faithfulness.model_dump(), "correctness": r.correctness.model_dump()})
            print(f"F={r.faithfulness.score} C={r.correctness.score}  {c['question']}")
            for name, v in (("faithfulness", r.faithfulness), ("correctness", r.correctness)):
                if not v.score:
                    print(f"    {name}: {v.reason}")
    finally:
        await agent.aclose()
    n = len(cases)
    summary = {"cases": n, "faithfulness": round(sum(d["faithfulness"]["score"] for d in details) / n, 3),
               "correctness": round(sum(d["correctness"]["score"] for d in details) / n, 3)}
    print("  ".join(f"{k}={v}" for k, v in summary.items()))
    return {"summary": summary, "details": details}


def main() -> None:
    asyncio.run(evaluate(os.environ["AI_EVAL_TOKEN"]))


if __name__ == "__main__":
    main()
