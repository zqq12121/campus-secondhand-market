"""用户上传图片的处理。

checkpoint 里的 HumanMessage 只存图片的公开 URL；调用模型前，把本轮用户消息里的图片
读出来转成 base64 data URL（DeepSeek 访问不到内网地址），更早轮次的图片换成文字占位以省 token。
"""
import base64
import logging

from langchain_core.messages import AnyMessage, HumanMessage

from app.graph.context import RequestContext

log = logging.getLogger(__name__)

PLACEHOLDER = "[用户上传的图片]"
UNREADABLE = "[图片读取失败]"


def human_content(text: str, image_urls: list[str]) -> str | list[dict]:
    if not image_urls:
        return text
    return [{"type": "text", "text": text}, *({"type": "image_url", "image_url": {"url": u}} for u in image_urls)]


def image_urls(message: AnyMessage) -> list[str]:
    if not isinstance(message.content, list):
        return []
    return [p["image_url"]["url"] for p in message.content if isinstance(p, dict) and p.get("type") == "image_url"]


async def _data_url(url: str, ctx: RequestContext, upload_base: str) -> str | None:
    if url in ctx.image_cache:
        return ctx.image_cache[url]
    prefix = upload_base.rstrip("/") + "/"
    if not url.startswith(prefix):
        return None
    try:
        content, mime = await ctx.java.fetch_upload(url[len(prefix):])
    except Exception as e:  # 读不到就让模型知道这张图看不了，不中断对话
        log.warning("读取用户图片失败 %s: %s", url, e)
        return None
    ctx.image_cache[url] = f"data:{mime};base64,{base64.b64encode(content).decode()}"
    return ctx.image_cache[url]


async def prepare_for_model(messages: list[AnyMessage], ctx: RequestContext, upload_base: str) -> list[AnyMessage]:
    last_human = max((i for i, m in enumerate(messages) if isinstance(m, HumanMessage)), default=-1)
    out: list[AnyMessage] = []
    for i, m in enumerate(messages):
        urls = image_urls(m) if isinstance(m, HumanMessage) else []
        if not urls:
            out.append(m)
            continue
        if i != last_human:
            out.append(HumanMessage(f"{m.text} {PLACEHOLDER * len(urls)}", id=m.id))
            continue
        parts: list[dict] = [{"type": "text", "text": m.text}]
        for url in urls:
            data = await _data_url(url, ctx, upload_base)
            parts.append({"type": "image_url", "image_url": {"url": data}} if data else {"type": "text", "text": UNREADABLE})
        out.append(HumanMessage(parts, id=m.id))
    return out
