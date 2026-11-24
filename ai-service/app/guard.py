"""规则式安全防护，不额外调用模型。

- 输入：用户消息命中越狱 / 套取提示词句式时直接拒答，不进模型。
- 工具结果：商品标题描述、订单、公告等别人写的内容，命中注入句式的整句换成占位（间接注入）。
- 输出：最终回复大段复述系统提示词时整段替换；手机号、身份证号打码。

规则宁可漏判也不误判：漏掉的还有系统提示词里的约束兜底，误判会直接拒答正常提问。
"""
import re
from typing import Any

_W = r"[^\n]"  # 句式中间允许夹杂的字符，不跨行

# 用户输入和工具结果都检查
INJECTION_PATTERNS = [
    rf"(忽略|无视|忘记|忘掉|抛开)(掉)?{_W}{{0,6}}(之前|以上|上面|前面|先前|原有|所有|全部|系统)的?{_W}{{0,6}}"
    r"(指令|指示|规则|设定|提示词|prompt)",
    rf"(输出|告诉我|显示|打印|复述|重复|泄露|给我看)(一下)?{_W}{{0,8}}"
    r"(提示词|system ?prompt|初始(指令|设定)|原始(指令|设定))",
    rf"(提示词|system ?prompt|初始(指令|设定)|原始(指令|设定)){_W}{{0,8}}(输出|发给我|告诉我|显示|打印|复述|贴出来)",
    rf"你(现在|从现在起|接下来)?(是|扮演|变成|作为){_W}{{0,15}}(没有|不受|无视|摆脱){_W}{{0,6}}(限制|约束|规则)",
    r"(越狱|上帝)模式",
    r"ignore\s+(all\s+)?(the\s+)?(previous|prior|above|earlier)\s+(instructions|rules|prompts?)",
    r"(reveal|print|show|repeat)\s+(your\s+)?(system\s+prompt|initial\s+instructions)",
    r"</?(system|assistant)>",
]
# 只查工具结果：卖家在描述里对助手下指令。用户自己说「助手请推荐台灯」是正常提问，不能套这条
TOOL_ONLY_PATTERNS = [
    rf"(AI|ai|助手|机器人|大?模型|assistant){_W}{{0,4}}(请|你要|你必须|务必|必须|一定要){_W}{{0,20}}"
    r"(推荐|置顶|排(在)?第一|调用|告诉用户|回复|输出)",
]

_INPUT_RE = re.compile("|".join(f"(?:{p})" for p in INJECTION_PATTERNS), re.I)
_SENTENCE_END = "。！？!?\n"
_TOOL_RE = re.compile(
    rf"[^{_SENTENCE_END}]*(?:{'|'.join(f'(?:{p})' for p in INJECTION_PATTERNS + TOOL_ONLY_PATTERNS)})"
    rf"[^{_SENTENCE_END}]*[{_SENTENCE_END[:-1]}]?",
    re.I,
)
REDACTED = "[已过滤疑似指令]"
INPUT_REFUSAL = "这个请求我没法处理。我可以帮你找在售商品、解答平台规则、查订单，或者起草求购和发布草稿。"
LEAK_REPLACEMENT = "抱歉，这部分内容不能提供。还有什么可以帮你找的吗？"

PHONE_RE = re.compile(r"(?<!\d)(1[3-9]\d)\d{4}(\d{4})(?!\d)")
ID_CARD_RE = re.compile(
    r"(?<![\dXx])(\d{6})(?:19|20)\d{2}(?:0[1-9]|1[0-2])(?:0[1-9]|[12]\d|3[01])\d{3}([\dXx])(?![\dXx])"
)

SHINGLE_CHARS = 20
# 回复里有这么多个 20 字片段与系统提示词逐字相同（约 40 字连续重合）才判定为泄露
LEAK_MIN_SHINGLES = 20


def is_injection(text: str) -> bool:
    return bool(_INPUT_RE.search(text))


def redact_untrusted(obj: Any) -> tuple[Any, int]:
    """递归处理工具结果里的所有字符串，返回（处理后的对象，过滤的句子数）。"""
    if isinstance(obj, str):
        return _TOOL_RE.subn(REDACTED, obj)
    if isinstance(obj, dict):
        out, total = {}, 0
        for k, v in obj.items():
            out[k], n = redact_untrusted(v)
            total += n
        return out, total
    if isinstance(obj, list):
        items = [redact_untrusted(v) for v in obj]
        return [v for v, _ in items], sum(n for _, n in items)
    return obj, 0


def shingles(template: str) -> frozenset[str]:
    """系统提示词模板的 20 字片段集合；{placeholder} 处断开，运行时填入的校区、分类不算。"""
    parts = re.split(r"\{[a-z_]+\}|\n", template)
    return frozenset(p[i:i + SHINGLE_CHARS] for p in parts for i in range(len(p) - SHINGLE_CHARS + 1))


def check_output(text: str, prompt_shingles: frozenset[str]) -> tuple[str, list[str]]:
    """返回（安全的文本，触发的原因）；没有触发时原样返回、原因为空。"""
    hits = sum(text[i:i + SHINGLE_CHARS] in prompt_shingles for i in range(len(text) - SHINGLE_CHARS + 1))
    if hits >= LEAK_MIN_SHINGLES:
        return LEAK_REPLACEMENT, ["prompt_leak"]
    reasons: list[str] = []
    text, n = PHONE_RE.subn(r"\1****\2", text)
    if n:
        reasons.append("phone")
    text, n = ID_CARD_RE.subn(r"\1***********\2", text)
    if n:
        reasons.append("id_card")
    return text, reasons
