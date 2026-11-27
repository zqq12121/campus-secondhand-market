"""长期记忆：用户偏好存在 LangGraph Store，命名空间 ("user", user_id)，固定 5 个槽位。"""
import json
import logging

from langchain_core.language_models import BaseChatModel
from langchain_core.messages import HumanMessage
from langchain_core.runnables import Runnable
from langgraph.store.base import BaseStore
from pydantic import BaseModel, Field

log = logging.getLogger(__name__)

SLOTS: dict[str, str] = {
    "campus": "常用校区",
    "budget": "预算习惯",
    "interests": "感兴趣的品类或物品",
    "avoid": "明确不要的东西",
    "trade_pref": "交易方式偏好",
}
MAX_VALUE_CHARS = 60

# 抽取前的零成本预判：本轮用户消息命中这些词才调用模型抽取偏好。
# 词表宁宽勿窄：误触发只是多花一次模型调用（模型多半返回「无变化」，不会写坏数据）；
# 漏抽则用户说过的偏好就丢了，下次还得再说一遍。所以拿不准的词也放进来。
# 「100 以内」这类单次搜索条件刻意不收：抽取提示词本来就规定它们不算偏好，触发了也是白调用。
PREFERENCE_SIGNALS = (
    # 长期习惯
    "平时", "一般", "通常", "经常", "常去", "常在", "常用", "总是", "一直", "习惯", "以后", "今后", "下次", "每次",
    "都要", "都只", "都在",
    # 校区 / 住处
    "我在", "我住", "住在", "宿舍", "校区",
    # 预算
    "预算",
    # 喜好与排斥
    "喜欢", "感兴趣", "偏好", "爱买", "不要", "别给我", "别推", "不想要", "不喜欢", "讨厌", "不考虑", "排除",
    # 交易方式
    "只要", "只接受", "只能", "面交", "担保",
    # 主动要求记住 / 忘掉
    "记住", "记一下", "帮我记", "忘掉", "忘了",
)
# 已有偏好时，用户纠正或否定的说法
CORRECTION_SIGNALS = ("不是", "不对", "错了", "改成", "改为", "换成", "换到", "搬", "不再", "不用", "已经不", "别按",
                      "删掉", "删除", "去掉")

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
- 没有变化时 set 和 delete 都留空。"""


class MemoryUpdate(BaseModel):
    """本轮对话后对用户长期偏好的修改。"""

    # 槽位名用 str 而不是 Literal：模型多写一个非法槽位时，Literal 会让整个结果校验失败，合法的也一起丢掉；
    # 这里只靠 description 约束，校验后再由 clean_update 过滤
    set: dict[str, str] = Field(
        default_factory=dict,
        description=f"要新增或改写的偏好，键是槽位名，只能是 {'、'.join(SLOTS)} 之一；"
                    f"值是不超过 {MAX_VALUE_CHARS} 字的简短描述，如「贝克兰德」「300 元以内」",
    )
    delete: list[str] = Field(
        default_factory=list,
        description=f"用户否定或纠正后要删除的槽位名，只能是 {'、'.join(SLOTS)} 之一",
    )


def memory_extractor(llm: BaseChatModel) -> Runnable:
    """偏好抽取用结构化输出。DeepSeek 支持 function_calling 和 json_mode，用 function_calling：schema 跟着工具定义走。"""
    return llm.with_structured_output(MemoryUpdate, method="function_calling")


def namespace(user_id: str) -> tuple[str, str]:
    return ("user", str(user_id))


async def load_profile(store: BaseStore, user_id: str) -> dict[str, str]:
    items = await store.asearch(namespace(user_id), limit=len(SLOTS))
    return {i.key: i.value["value"] for i in items if i.key in SLOTS}


def should_extract(human_text: str, profile: dict[str, str]) -> bool:
    """本轮是否值得调用模型抽取偏好：含偏好信号词，或已有偏好且用户在纠正。"""
    if any(w in human_text for w in PREFERENCE_SIGNALS):
        return True
    return bool(profile) and any(w in human_text for w in CORRECTION_SIGNALS)


def format_profile(profile: dict[str, str]) -> str:
    return "；".join(f"{SLOTS[k]}：{v}" for k, v in profile.items() if k in SLOTS)


def clean_update(update: MemoryUpdate) -> MemoryUpdate:
    """只保留白名单槽位，值去空白并截断。"""
    return MemoryUpdate(
        set={k: v.strip()[:MAX_VALUE_CHARS] for k, v in update.set.items() if k in SLOTS and v.strip()},
        delete=[k for k in update.delete if k in SLOTS],
    )


async def extract_and_save(extractor: Runnable, store: BaseStore, user_id: str, profile: dict[str, str],
                           human: str, ai: str) -> MemoryUpdate | None:
    """extractor 是 memory_extractor() 的结果（可能带降级）。抽取失败只记日志，不影响对话。"""
    prompt = EXTRACT_PROMPT.format(
        slots="\n".join(f"- {k}：{v}" for k, v in SLOTS.items()),
        current=json.dumps(profile, ensure_ascii=False) or "{}",
        human=human, ai=ai[:800], max_chars=MAX_VALUE_CHARS,
    )
    try:
        result = await extractor.ainvoke([HumanMessage(prompt)])
    except Exception:  # 模型报错，或返回的参数不符合 MemoryUpdate
        log.exception("偏好抽取失败")
        return None
    if result is None:  # 模型没有按要求调用结构化输出的工具
        log.warning("偏好抽取结果无法解析")
        return None
    update = clean_update(result)
    for key, value in update.set.items():
        if profile.get(key) != value:
            await store.aput(namespace(user_id), key, {"value": value})
    for key in update.delete:
        await store.adelete(namespace(user_id), key)
    return update
