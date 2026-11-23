# 校园二手助手 ai-service

Python 3.11 + FastAPI + LangGraph + DeepSeek。独立部署，Java 后端无需改动。需求见「校园二手助手 Agent 需求文档」。

当前进度：**M4**（全部完成）。

- M1：图骨架、商品混合检索 RAG、短期记忆、SSE 接口。
- M2：规则问答 RAG（带出处）、长期偏好记忆、订单查询。
- M3：写操作（发求购、生成发布草稿）+ `interrupt` 确认卡片 + `/resume`；支持上传商品图片。
- M4：前端聊天浮窗（`frontend/src/web/assistant/`）、商品卡片、确认卡片、发布页预填、「助手偏好」页（`/me/preferences`）。

## 结构

```
app/
├── main.py            # FastAPI：/chat 与 /resume（SSE）、/memory（我的偏好）、/health
├── config.py          # 全部配置走环境变量（见仓库根目录 .env.example）
├── java_client.py     # 带用户 token 调 Java /api/v1
├── ratelimit.py       # 按用户每分钟 / 每天次数
├── rag/
│   ├── embedder.py    # 本地 bge-small-zh
│   ├── goods_index.py # MySQL 增量同步 → Redis 向量索引；向量 + 关键词 RRF 混合检索
│   └── rules_index.py # knowledge/*.md + 上线公告 → 按二级标题切片 → Redis 向量索引
└── graph/
    ├── context.py     # 请求上下文（token 包在对象里，避免写进 checkpoint）
    ├── tools.py       # 只读工具：search_goods / get_goods_detail / search_rules / list_my_orders / get_order
    ├── drafts.py      # 写工具：draft_want_post / suggest_listing，interrupt() 等用户确认
    ├── images.py      # 本轮图片转 data URL 给模型，历史图片换占位文字
    ├── memory.py      # 长期偏好：5 个固定槽位，存 LangGraph Store（Redis）
    └── builder.py     # START → load_memory → prepare → agent ⇄ tools → save_memory → END
knowledge/             # 平台规则知识库（交易流程、售后、信用分、发布与求购、账号与评价）
evals/                 # 检索与规则问答评测集 + 脚本
```

## 本地运行

前置条件：`docker compose up -d` 已启动 MySQL / Redis，Java 后端运行在 8080。

1. 在仓库根目录 `.env` 中填写 `DEEPSEEK_API_KEY` 等配置，参考 `.env.example` 的「校园二手助手」段。
2. 创建 MySQL 只读账号。只授予同步需要的 3 张表，密码与 `.env` 中的 `AI_MYSQL_PASSWORD` 一致：

   ```bash
   docker exec -it campus-trade-mysql mysql -uroot -p
   ```

   ```sql
   CREATE USER 'ai_ro'@'%' IDENTIFIED BY '<AI_MYSQL_PASSWORD>';
   GRANT SELECT ON campus_trade.goods TO 'ai_ro'@'%';
   GRANT SELECT ON campus_trade.category TO 'ai_ro'@'%';
   GRANT SELECT ON campus_trade.campus TO 'ai_ro'@'%';
   ```

3. 启动服务（首次启动会下载 embedding 模型，约 100 MB）：

   ```bash
   cd ai-service
   uv sync
   uv run uvicorn app.main:app --port 8090 --reload
   ```

4. 前端执行 `npm run dev:real`，`/api/v1/ai` 会被代理到 8090。

## 演示数据

`sql/demo_goods.sql` 包含 3 个演示卖家和 40 件商品，覆盖 2 个校区和全部二级分类；其中 1 件已售出、1 件已下架，用于检验过滤。脚本可重复执行，只会删除并重建 id 在 90001–90999 范围内的商品。

```bash
docker exec -i campus-trade-mysql sh -c 'mysql --default-character-set=utf8mb4 -uroot -p"$MYSQL_ROOT_PASSWORD" campus_trade' < sql/demo_goods.sql
```

封面图地址为 `/uploads/demo/goods-<id>.svg`，图片文件不随仓库提交。演示卖家不能登录，体验时请自己注册账号。

## 接口

`POST /api/v1/ai/chat`，请求头 `Authorization: Bearer <用户 token>`。

```json
{ "conversationId": "可选，续聊时传上次 done 事件返回的值", "message": "帮我把这个卖了", "images": ["可选，最多 3 张"] }
```

`images` 必须是先调 Java `POST /files` 上传后返回的地址（以 `UPLOAD_PUBLIC_BASE_URL` 开头），其他地址返回 400。

返回 `text/event-stream`：

| event | data |
| --- | --- |
| `token` | 回复文本片段 |
| `tool` | `{name, status: start \| end}` |
| `goods` | 核实过、可购买的商品卡片数组 |
| `confirm` | 写操作待确认：`{interruptId, kind: want_post \| listing, draft}`，前端渲染确认卡片 |
| `listing` | 用户确认后的发布草稿，前端用它预填发布页 |
| `done` | `{conversationId, usage, pending}`；`pending=true` 表示有待确认的卡片 |
| `error` | `{code, message}` |

确认卡片的回调 `POST /api/v1/ai/chat/{conversationId}/resume`，返回同样的 SSE 流：

```json
{ "interruptId": "confirm 事件里的值", "decision": "confirm | cancel", "edits": { "validDays": 15 } }
```

- `edits` 只接受白名单字段。求购：title / description / categoryId / campusId / priceMin / priceMax / validDays；发布草稿：title / description / categoryId / conditionLevel / price。修改后会重新校验。
- 求购在确认后才调 Java `POST /wants` 发布；发布草稿只回给前端预填，始终由用户自己在发布页点发布。
- `interruptId` 不存在或已处理返回 409。有待确认卡片时用户直接发新消息，视为取消。

未登录或 token 失效返回 401，账号被封禁返回 403，超限返回 429，格式与 Java 统一响应一致。

「我的偏好」（长期记忆）：

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/v1/ai/memory` | 列出偏好：`[{key, label, value, updatedAt}]` |
| DELETE | `/api/v1/ai/memory/{key}` | 删除一条，key 为 campus / budget / interests / avoid / trade_pref |
| DELETE | `/api/v1/ai/memory` | 清空 |

偏好由 `save_memory` 节点在每轮结束时抽取：只记录用户明确表达的偏好，不记录手机号、聊天内容、订单金额。

## 测试

```bash
uv run pytest
```

测试用脚本化的假模型和假 Java，不调用 DeepSeek，不依赖外部服务。

### 评测

需要导入演示数据，并启动 Java 后端和 ai-service；`AI_EVAL_TOKEN` 填任一用户的 token。

```bash
AI_EVAL_TOKEN=<token> uv run python -m evals.run_retrieval   # 商品检索 top5 命中率
AI_EVAL_TOKEN=<token> uv run python -m evals.run_rules       # 规则问答正确率与出处（走 /chat 端到端）
```

2026-11-21 在演示数据上的结果（DeepSeek `deepseek-flash`，embedding `bge-small-zh-v1.5`）：

| 评测 | 用例 | 结果 | 验收线 |
| --- | --- | --- | --- |
| 商品检索 top5 命中率 | 35 | 混合 97%（向量 100%，关键词 63%） | ≥ 80% |
| 规则问答正确率 | 15 | 100% | ≥ 90% |
| 规则回答带出处 | 15 | 100% | 全部 |

用例是按演示数据编写的，结果偏乐观；有了真实数据后应补充用例再测。

## 注意

- 商品索引每 `GOODS_SYNC_INTERVAL_SECONDS` 秒按 `goods.updated_at` 增量同步。更换 embedding 模型后，需要先删除 Redis 里的 `ai_goods` 索引和 `ai:goods_index:watermark`，再重启服务。
- `search_goods` 会同时调用 Java `/goods?keyword=`，这些查询会计入热搜词。
- 规则知识库每 10 分钟检查一次 `knowledge/` 和公告，内容有变化才重建。修改规则后，记得同步更新 `knowledge/` 里的数值。
- `save_memory` 每轮多一次模型调用，在回复流式输出完成后执行，只会推迟 `done` 事件。
- 识图依赖 `deepseek-flash`。2026-11-21 实测 `deepseek-v4-pro` 不能识图，会胡乱描述图片，切换模型前要重新验证。
- 参考价只取「同二级分类 + 品类词命中」的在售商品：至少 3 件时取中位数 ±20%，否则使用模型估价并注明仅供参考。
