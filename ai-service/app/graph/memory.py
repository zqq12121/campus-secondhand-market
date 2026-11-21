"""长期记忆：用户偏好存在 LangGraph Store，命名空间 ("user", user_id)，固定 5 个槽位。"""
import json
import logging
import re

from langchain_core.language_models import BaseChatModel
from langchain_core.messages import HumanMessage
from langgraph.store.base import BaseStore
from pydantic import BaseModel, Field, ValidationError

log = logging.getLogger(__name__)

SLOTS: dict[str, str] = {
    "campus": "常用校区",
    "budget": "预算习惯",
    "interests": "感兴趣的品类或物品",
    "avoid": "明确不要的东西",
    "trade_pref": "交易方式偏好",
}
MAX_VALUE_CHARS = 60

EXTRACT_PROMPT = """你负责维护二手平台用户的长期偏好。根据本轮对话，判断是否需要更新下列槽位：
{slots}

已有偏好：
{current}

本轮对话：
用户：{human}
助手：{ai}

规则：
- 只记录用户自己明确表达、以后找东西还用得上的偏好；一次性的具体需求（如这次想买显示器）记到 interests 即可，不要写成长句。
- campus、budget、trade_pref 只在用户表达长期习惯时才写（如「我平时在廷根」「我一般预算 300 以内」「以后都只要面交」）。
  某一次搜索或求购里带的校区、价格条件（如「贝克兰德 100 元以内的显示器」）不算偏好，不要据此改写这三个槽位。
- 用户纠正或否定了已有偏好时更新或删除对应槽位。
- 不要记录手机号、聊天内容、订单金额等个人隐私。
- 每个值不超过 {max_chars} 字。
只输出 JSON，格式：{{"set": {{"槽位名": "值"}}, "delete": ["槽位名"]}}；没有变化时输出 {{"set": {{}}, "delete": []}}。"""


class MemoryUpdate(BaseModel):
    set: dict[str, str] = Field(default_factory=dict)
    delete: list[str] = Field(default_factory=list)


def namespace(user_id: str) -> tuple[str, str]:
    return ("user", str(user_id))


async def load_profile(store: BaseStore, user_id: str) -> dict[str, str]:
    items = await store.asearch(namespace(user_id), limit=len(SLOTS))
    return {i.key: i.value["value"] for i in items if i.key in SLOTS}


def format_profile(profile: dict[str, str]) -> str:
    return "；".join(f"{SLOTS[k]}：{v}" for k, v in profile.items() if k in SLOTS)


def parse_update(text: str) -> MemoryUpdate | None:
    match = re.search(r"\{.*\}", text, re.S)
    if not match:
        return None
    try:
        update = MemoryUpdate.model_validate(json.loads(match.group(0)))
    except (ValueError, ValidationError):
        return None
    update.set = {k: v.strip()[:MAX_VALUE_CHARS] for k, v in update.set.items() if k in SLOTS and v.strip()}
    update.delete = [k for k in update.delete if k in SLOTS]
    return update


async def extract_and_save(llm: BaseChatModel, store: BaseStore, user_id: str, profile: dict[str, str],
                           human: str, ai: str) -> MemoryUpdate | None:
    """抽取失败只记日志，不影响对话。"""
    prompt = EXTRACT_PROMPT.format(
        slots="\n".join(f"- {k}：{v}" for k, v in SLOTS.items()),
        current=json.dumps(profile, ensure_ascii=False) or "{}",
        human=human, ai=ai[:800], max_chars=MAX_VALUE_CHARS,
    )
    try:
        result = await llm.ainvoke([HumanMessage(prompt)])
    except Exception:
        log.exception("偏好抽取调用失败")
        return None
    update = parse_update(result.text)
    if update is None:
        log.warning("偏好抽取结果无法解析")
        return None
    for key, value in update.set.items():
        if profile.get(key) != value:
            await store.aput(namespace(user_id), key, {"value": value})
    for key in update.delete:
        await store.adelete(namespace(user_id), key)
    return update
