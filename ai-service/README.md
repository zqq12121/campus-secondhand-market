# 校园二手助手 ai-service

Python 3.11 + FastAPI + LangGraph + DeepSeek。独立部署，Java 后端无需改动。需求见「校园二手助手 Agent 需求文档」。

当前进度：**M5**（全部完成）。

- M1：图骨架、商品混合检索 RAG、短期记忆、SSE 接口。
- M2：规则问答 RAG（带出处）、长期偏好记忆、订单查询。
- M3：写操作（发求购、生成发布草稿）+ `interrupt` 确认卡片 + `/resume`；支持上传商品图片。
- M4：前端聊天浮窗（`frontend/src/web/assistant/`）、商品卡片、确认卡片、发布页预填、「助手偏好」页（`/me/preferences`）。
- M5：可观测性（每请求一行链路日志 + 可选 Langfuse）、cross-encoder 重排序、规则式安全防护（输入拦截、工具结果过滤、输出改写）。

## 结构

```
app/
├── main.py            # FastAPI：/chat 与 /resume（SSE）、/memory（我的偏好）、/health、/ready、/metrics
├── config.py          # 全部配置走环境变量（见仓库根目录 .env.example）
├── deps.py            # 进程级依赖初始化（Redis、Java、模型、索引），lifespan / 评测共用
├── java_client.py     # 带用户 token 调 Java /api/v1
├── ratelimit.py       # 按用户每分钟 / 每天次数
├── observability.py   # TraceCollector 回调：每次模型 / 工具调用的耗时、token、成本；可选 Langfuse
├── metrics.py         # Prometheus 指标
├── guard.py           # 规则式安全防护：注入检测、工具结果过滤、输出检查
├── redis_lock.py      # Redis 分布式锁（Lua 校验 value 再续期 / 释放）与选主执行
├── mcp_server.py      # MCP Server：把 5 个只读工具暴露给 Claude Desktop / Cursor 等客户端
├── rag/
│   ├── embedder.py    # bge-small-zh：本地模型或外置 TEI 服务
│   ├── reranker.py    # bge-reranker-base（cross-encoder）：本地模型或外置 TEI 服务
│   ├── goods_index.py # MySQL 增量同步 → Redis 向量索引；向量 + 关键词 RRF 混合检索，再重排
│   └── rules_index.py # knowledge/*.md + 上线公告 → 按二级标题切片 → Redis 索引（蓝绿重建）；向量 + jieba 全文召回，RRF 合并后重排
└── graph/
    ├── context.py     # 请求上下文（token 包在对象里，避免写进 checkpoint）
    ├── tools.py       # 只读工具：search_goods / get_goods_detail / search_rules / list_my_orders / get_order（核心逻辑 *_impl 与 MCP 共用）
    ├── drafts.py      # 写工具：draft_want_post / suggest_listing，interrupt() 等用户确认
    ├── images.py      # 本轮图片转 data URL 给模型，历史图片换占位文字
    ├── memory.py      # 长期偏好：5 个固定槽位，存 LangGraph Store（Redis）
    └── builder.py     # START → load_memory → prepare → agent ⇄ tools → output_guard → save_memory → END
knowledge/             # 平台规则知识库（交易流程、售后、信用分、发布与求购、账号与评价）
evals/                 # 评测集与脚本：检索、规则问答（关键词 + LLM-as-judge）、Agent 轨迹、汇总 run_all
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

3. 启动服务（首次启动会下载 embedding 模型约 100 MB、重排序模型约 1.1 GB）：

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
| `revise` | 输出防护改写了回复（打码或拦截）：用 data 整体替换本轮已收到的文本 |
| `done` | `{conversationId, usage, pending}`；`usage` 含本轮所有模型调用（摘要、偏好抽取也算）；`pending=true` 表示有待确认的卡片 |
| `error` | `{code, message}` |

确认卡片的回调 `POST /api/v1/ai/chat/{conversationId}/resume`，返回同样的 SSE 流：

```json
{ "interruptId": "confirm 事件里的值", "decision": "confirm | cancel", "edits": { "validDays": 15 } }
```

- `edits` 只接受白名单字段。求购：title / description / categoryId / campusId / priceMin / priceMax / validDays；发布草稿：title / description / categoryId / conditionLevel / price。修改后会重新校验。
- 求购在确认后才调 Java `POST /wants` 发布；发布草稿只回给前端预填，始终由用户自己在发布页点发布。
- `interruptId` 不存在或已处理返回 409。有待确认卡片时用户直接发新消息，视为取消。
- 同一会话同时只处理一个请求：上一条的 SSE 流还没结束时，同会话的 `/chat` 或 `/resume` 返回 409「上一条消息还在处理，请稍后」。

未登录或 token 失效返回 401，账号被封禁返回 403，超限返回 429，格式与 Java 统一响应一致。

「我的偏好」（长期记忆）：

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/v1/ai/memory` | 列出偏好：`[{key, label, value, updatedAt}]` |
| DELETE | `/api/v1/ai/memory/{key}` | 删除一条，key 为 campus / budget / interests / avoid / trade_pref |
| DELETE | `/api/v1/ai/memory` | 清空 |

偏好由 `save_memory` 节点在每轮结束时抽取：只记录用户明确表达的偏好，不记录手机号、聊天内容、订单金额。抽取用 `with_structured_output(MemoryUpdate, method="function_calling")`（DeepSeek 支持 function_calling 和 json_mode），不再用正则从回复里抠 JSON。槽位名不在白名单里的会被过滤，值超过 60 字会截断；模型报错或返回的参数不合法时只记日志，不影响对话。

## 可观测性

每个请求结束时输出一行 `chat_trace` JSON 日志，只有元数据，不含对话内容：

```json
{"user": "7", "conv": "…", "ms": 3120, "ttft_ms": 820, "pending": false, "revised": false, "memory_skipped": true,
 "input_tokens": 5210, "output_tokens": 380, "cached_input_tokens": 3840, "cost": 0.009, "fallback": false,
 "llm":   [{"node": "agent", "ms": 1450, "input_tokens": 2400, "cached_input_tokens": 1920, "output_tokens": 60}, …],
 "tools": [{"name": "search_goods", "ms": 210}, {"name": "draft_want_post", "ms": 3, "error": "interrupted"}]}
```

- `llm` 的 `node` 是发起调用的图节点：`agent`、`prepare`（摘要压缩）、`save_memory`（偏好抽取）。
- `ttft_ms`：从请求开始到第一个 `token` 事件的毫秒数。本轮没有输出 token（比如直接停在确认卡片）时为 `null`。
- `cached_input_tokens`：命中 DeepSeek 上下文缓存的输入 token 数。langchain-deepseek 把 DeepSeek 返回的 `prompt_cache_hit_tokens` 放在 `usage_metadata.input_token_details.cache_read`。
- `cost`：未命中缓存的输入按 `AI_PRICE_INPUT_PER_M` 计，命中缓存的按 `AI_PRICE_CACHED_INPUT_PER_M` 计（不配置时按输入价计，与之前一致），输出按 `AI_PRICE_OUTPUT_PER_M` 计。SSE `done` 事件里的 `usage` 格式不变。
- 工具 `error` 为 `interrupted` 表示写工具停下等用户确认，不是故障。

**提示词前缀缓存**：DeepSeek 的上下文缓存按请求前缀匹配。系统提示词里所有用户都相同的部分（规则、校区、分类）在前，用户相关的部分（昵称、注册校区、偏好、摘要）在最后，不同用户、不同会话的请求可以共用同一段缓存前缀。原来的顺序已经满足这一点，只加了测试守住；`guard.py` 的提示词泄露检测不受影响。

**Prometheus**：`GET /api/v1/ai/metrics`。前端 Nginx 已屏蔽这个路径，Prometheus 直接抓 `ai:8090`。

| 指标 | 标签 | 说明 |
| --- | --- | --- |
| `ai_requests_total` | endpoint、result | result：ok / pending / error / aborted（客户端断开）/ refused（输入拦截）/ busy（409）/ rate_limited（429） |
| `ai_request_duration_seconds` | endpoint | 整个 SSE 流的耗时直方图 |
| `ai_ttft_seconds` | endpoint | 首 token 延迟直方图 |
| `ai_llm_tokens_total` | node、kind | kind：input_uncached / input_cached / output |
| `ai_tool_duration_seconds` | tool | 工具耗时直方图 |
| `ai_tool_errors_total` | tool | 工具报错次数，`interrupted` 不算 |
| `ai_guard_triggers_total` | kind | input_blocked / tool_redacted / output_rewritten |

user_id、conversation_id 不作为标签：每种标签组合都是一条独立的时间序列，按用户打标签会导致基数爆炸，按用户排查请用 `chat_trace` 日志。多 worker 部署时每个进程各自计数，需要设置 `PROMETHEUS_MULTIPROC_DIR` 启用 prometheus_client 的多进程模式，这次没有实现。

**就绪检查**：`GET /api/v1/ai/ready` 检查 Redis 能否 ping 通、规则索引的当前版本是否存在，任一项失败返回 503（`data` 里标明哪项失败），适合用作 K8s readinessProbe。`/health` 保持不变，只表示进程还活着。

配置 `LANGFUSE_PUBLIC_KEY` 和 `LANGFUSE_SECRET_KEY` 后，会额外把完整链路（每个节点的输入输出、提示词、工具参数）上报到 Langfuse，按用户和会话归组。**上报内容包含对话原文和图片**，上生产前要确认隐私合规。可以用 Langfuse Cloud，也可以自己部署后改 `LANGFUSE_BASE_URL`。

## 重排序

向量和关键词召回后用 RRF 合并，再用 `bge-reranker-base` 对前 30 条打分重排。规则检索的向量和全文各召回 15 条，RRF 合并后取前 15 条重排，最后取前 5 条。模型加载失败或打分报错时退回原顺序。`RERANK_MODEL` 留空可以关闭。

**规则库混合检索**：规则里有很多数字和专有词（「15 分钟」「信用分」），向量召回对它们不敏感，所以加了一路全文（BM25）召回。

- 中文分词：redisvl 0.32 的 `IndexSchema` 不能设置 RediSearch 的 `LANGUAGE chinese`，绕过 redisvl 手写 `FT.CREATE` 又会丢掉它的 schema 管理，所以选了 jieba 预分词。`title + content` 用 `cut_for_search` 切词，去掉虚词后用空格拼接，写进 `tokens` 文本字段；查询用同一个分词器。领域词（信用分、担保订单……）加进了词典，见 `rules_index.DOMAIN_WORDS`。代价是多一个依赖，首次分词要加载词典，约 1 秒。
- 合并复用 `goods_index.rrf_merge`。任意一路失败都会降级成只用另一路并告警，两路都失败时返回空结果，不抛异常。
- schema 版本（`SCHEMA_VERSION`）算进了内容 hash，加 `tokens` 字段后第一次启动会自动走一次蓝绿重建。

2026-11-24 本地实测（CPU，M 系列 Mac）：

| 场景 | 耗时 | 效果 |
| --- | --- | --- |
| 商品：30 条候选 | 约 130 ms | 评测脚本已加「混合+重排」和 MRR，需 Java 后端，尚未跑 |
| 规则：15 个评测问题 | 每次约 380 ms（只用向量约 12 ms） | 第一条命中：只用向量 14/15，加重排 15/15；前三条都是 15/15 |

规则的命中判定方式：取评测集里的关键事实词，看是否出现在检索到的片段中。这只是粗判。

## 容错

下游出故障时，助手的部分功能降级，整体仍然可用。

**大模型降级**：配置 `FALLBACK_MODEL`、`FALLBACK_API_KEY`（以及可选的 `FALLBACK_BASE_URL`，OpenAI 兼容接口）后，主模型报超时 / 连接失败、429、5xx 时，用 LangChain 的 `with_fallbacks` 切到备用模型。参数错误、鉴权失败这类问题换模型也解决不了，不切换，直接报错。

- agent 用的是 `bind_tools` 之后的模型：主、备模型各自 `bind_tools` 再组合，降级后照样能调用工具。摘要压缩、强制作答、偏好抽取用的模型也做了降级。
- 配了备用模型时，主模型改为不重试（`max_retries=0`），失败一次就切换。否则重试 2 次再加上 60 秒超时，用户最多要等 3 分钟。
- 备用模型的调用带 `llm_fallback` tag。`chat_trace` 日志里，失败的主模型调用会带 `error`，备用模型的调用带 `"fallback": true`，顶层的 `fallback` 表示本轮是否发生过降级。
- 已知局限：如果主模型已经流式输出了一部分才失败，前端会先收到这部分片段，再收到备用模型的完整回答。超时、429、5xx 通常发生在第一个 token 之前，这种情况很少见。

**Java 调用**：

- 连接级重试：`httpx.AsyncHTTPTransport(retries=2)`，只重试建连失败。这时请求还没发出去，POST 重试也安全。
- 熔断器（`java_client.CircuitBreaker`）：连续失败 `JAVA_BREAKER_FAILURES` 次（默认 5）后熔断 `JAVA_BREAKER_COOLDOWN_SECONDS` 秒（默认 30），熔断期间直接抛 `JavaApiError(503, "服务暂不可用")`，不再发请求。冷却结束后进入半开状态，只放行一个试探请求：成功就关闭熔断，失败就再熔断一个周期。只有 5xx 和网络错误计为失败；401、403、其他 4xx 和业务错误码说明 Java 正常响应，会清零失败计数。熔断期间登录校验返回 503，工具调用把「服务暂不可用」交给模型，由模型告诉用户。

## 外置 embedding / 重排序服务

默认在进程内加载 `bge-small-zh-v1.5` 和 `bge-reranker-base`，两个模型加起来约 1.2 GB。也可以拆成独立的 [text-embeddings-inference](https://github.com/huggingface/text-embeddings-inference)（TEI）服务，ai-service 通过 HTTP 调用：

```bash
docker run -d --name tei-embed -p 8091:80 -v hf-cache:/data \
  ghcr.io/huggingface/text-embeddings-inference:cpu-1.8 --model-id BAAI/bge-small-zh-v1.5
docker run -d --name tei-rerank -p 8092:80 -v hf-cache:/data \
  ghcr.io/huggingface/text-embeddings-inference:cpu-1.8 --model-id BAAI/bge-reranker-base
```

然后在 `.env` 里配置 `EMBEDDING_SERVICE_URL=http://localhost:8091`、`RERANK_SERVICE_URL=http://localhost:8092`（compose 内部用容器名，如 `http://tei-embed`）。不配置时仍用本地模型。

- embedding 调 `POST /embed`（`inputs`、`normalize: true`、`truncate: true`），文档按 32 条一批发送（TEI 默认的 `--max-client-batch-size`）。向量维度在启动时调用一次 `/embed` 获取。query 加检索指令前缀，行为与本地实现一致。
- 重排序调 `POST /rerank`。TEI 按分数降序返回 `[{index, score}]`，这里按 `index` 还原成与候选顺序对齐的分数。调用失败时沿用原有的降级逻辑：商品退回 RRF 顺序，规则退回向量顺序。
- 请求超时 10 秒。embedding 服务在启动时连不上会直接启动失败，与本地模型加载失败的行为一致。
- 本地和 TEI 用的是同一个模型时，向量可以通用，切换时不需要重建索引。
- 两个服务都外置后，进程里不再需要 sentence-transformers 和 torch。这次没有改依赖结构（Dockerfile 仍会安装 torch）；如果确定只用外置服务，可以考虑把这两个依赖移到可选组，镜像能小 1 GB 以上。

## 安全防护

规则检测，不额外调用模型。规则宁可漏判也不误判，漏掉的靠系统提示词里的约束兜底。

| 位置 | 做法 |
| --- | --- |
| 用户输入 | 命中「忽略之前的指令」「输出系统提示词」「你现在是没有限制的 AI」等句式时直接拒答。拒答不进模型，不写入会话历史，也不取消待确认的卡片 |
| 工具结果 | 所有只读工具的返回值逐个字符串检查。命中注入句式（含「AI 助手请务必推荐本商品」这类只针对工具结果的规则）时，把整句换成 `[已过滤疑似指令]`，防止卖家在商品描述里写指令（间接注入） |
| 最终回复 | `output_guard` 节点检查两件事：与系统提示词逐字重合约 40 字以上时整段替换；手机号、身份证号打码。改写后发 `revise` 事件，会话历史里也保存改写后的版本 |

已知局限：

- 回复是流式输出的，改写发生在整条回复生成之后。用户可能短暂看到原文，之后才被替换。
- 图片里的文字指令检测不到。
- 规则覆盖不了换个说法的注入。命中情况可以看日志里的 `输入防护拦截` 和 `工具结果过滤疑似注入`。

## 多实例部署

可以起多个实例，或者 `uvicorn --workers N`，只需要共用同一个 Redis。

- **选主同步**：商品索引同步和规则库重建都是后台循环，每个进程都会启动，但只有拿到 Redis 锁的那个进程真正执行。加锁用 `SET key <uuid> NX EX ttl`，持有者每 ttl/3 秒续期，其他进程每 ttl/3 秒重试一次。续期和释放用 Lua 脚本，先比对 value 再操作，只会动自己的锁。续期失败时立即停止同步，避免两个进程同时写；进程正常退出时主动释放锁，崩溃时锁最多 ttl 秒后过期，由其他进程接手。配置项：`AI_LEADER_LOCK_TTL_SECONDS`（默认 30）、`GOODS_SYNC_LOCK_KEY`、`RULES_SYNC_LOCK_KEY`。
- **规则库蓝绿重建**：每个内容版本写进独立的索引 `ai_rules_<hash 前 8 位>`（键前缀 `ai:kb:<hash>`），Redis 键 `ai:rules_index:current` 指向当前版本。重建时先把新版本完整写好，再切换指针，最后删除旧索引。重建期间检索一直读旧版本。hash 包含 schema 版本和向量维度，schema 或 embedding 模型变化时会自动重建。旧版本的固定索引 `ai_rules` 会在第一次蓝绿重建后删除。
- 商品索引是增量同步，没有做蓝绿，只加了选主。
- **会话锁**：`/chat` 和 `/resume` 执行图之前按 thread_id（`user_id:conv_id`）加锁，键为 `ai:conv_lock:<user>:<conv>`，value 是随机 uuid。拿不到锁直接返回 409。锁覆盖整个 SSE 流，在流的 finally 里用 Lua 校验 value 后释放；流抛异常、客户端中途断开也会释放。`cancel_pending` 和输入防护拒答都会读写会话状态，所以也放在锁内执行。`AI_CONVERSATION_LOCK_TTL_SECONDS`（默认 120）兜底进程崩溃导致锁没释放的情况；单轮执行超过 TTL 时锁会提前过期，这时不再保证互斥。

已知局限：锁没有 fencing token。如果主实例长时间卡住（比如 GC、网络分区）超过 ttl，可能和新主短暂重叠。两类同步都是幂等写入，重叠只会造成重复工作，不会写坏数据。

## MCP

把 5 个只读工具（`search_goods`、`get_goods_detail`、`search_rules`、`list_my_orders`、`get_order`）同时暴露成 MCP Server，可以接入 Claude Desktop、Cursor 等 MCP 客户端。用的是官方 Python SDK `mcp` 2.x，1.x 里的 `FastMCP` 在 2.x 改名为 `MCPServer`。

```bash
uv run python -m app.mcp_server                                    # stdio（默认）
AI_MCP_TRANSPORT=streamable-http uv run python -m app.mcp_server   # streamable HTTP：http://127.0.0.1:8093/mcp
```

- **实现复用**：工具逻辑抽成接收 `RequestContext` 的普通 async 函数（`tools.*_impl`），LangChain 工具和 MCP 工具都调用它们。工具说明和参数说明直接取自 LangChain 工具的 docstring，两边只维护一份。依赖初始化（Redis、Java、embedding / 重排序、索引）与 FastAPI 共用 `app/deps.py` 的 `build_deps`。MCP 进程只读索引，不跑后台同步，所以要先启动过一次 ai-service，把规则和商品索引建好。
- **鉴权**：每次调用都把用户 token 交给 Java 的 `/users/me` 校验，之后的 Java 请求都带这个 token，权限仍由 Java 判定。HTTP 模式从请求头 `Authorization: Bearer <token>` 取 token；stdio 模式是本机单用户，从环境变量 `AI_MCP_USER_TOKEN` 读。工具结果同样经过 `redact_untrusted` 过滤间接注入。
- **为什么不暴露写工具**：`draft_want_post` 和 `suggest_listing` 靠 LangGraph 的 `interrupt()` 停在确认卡片，用户确认后经 `/resume` 从 checkpoint 继续执行。MCP 调用是一次请求一次返回，没有这套「暂停—确认—恢复」机制，直接暴露就绕过了用户确认。
- MCP 调用不走 `/chat` 的限流和会话锁。HTTP 模式默认只监听 127.0.0.1，对外开放前要加限流。

Claude Desktop（stdio）的 `claude_desktop_config.json`：

```json
{
  "mcpServers": {
    "campus-trade": {
      "command": "uv",
      "args": ["--directory", "/绝对路径/ai-service", "run", "python", "-m", "app.mcp_server"],
      "env": {
        "AI_MCP_USER_TOKEN": "<用户 token>",
        "BACKEND_BASE_URL": "http://localhost:8080",
        "REDIS_HOST": "localhost"
      }
    }
  }
}
```

stdio 进程由客户端拉起，启动时要加载 embedding 和重排序模型。配了 `EMBEDDING_SERVICE_URL` / `RERANK_SERVICE_URL`（见「外置 embedding / 重排序服务」）时启动会快很多。

Cursor 等支持远程 MCP 的客户端，可以连 streamable HTTP（以 `~/.cursor/mcp.json` 为例）：

```json
{
  "mcpServers": {
    "campus-trade": {
      "url": "http://127.0.0.1:8093/mcp",
      "headers": { "Authorization": "Bearer <用户 token>" }
    }
  }
}
```

## 测试

```bash
uv run pytest
```

测试用脚本化的假模型和假 Java，不调用 DeepSeek，不依赖外部服务。

### 评测

`tests/` 下的单测不依赖外部服务，每次 push 都会跑。`evals/` 是真实环境的回归评测：需要导入演示数据（`sql/demo_goods.sql`），启动 Java 后端、Redis 和 ai-service，并配置 `DEEPSEEK_API_KEY`。`AI_EVAL_TOKEN` 填任一用户的 token。

```bash
AI_EVAL_TOKEN=<token> uv run python -m evals.run_all              # 全部评测，输出总表并写入 evals/results/<日期>.json
AI_EVAL_TOKEN=<token> uv run python -m evals.run_rules            # 规则问答：关键词判分（走 /chat 端到端）
AI_EVAL_TOKEN=<token> uv run python -m evals.judge                # 规则问答：LLM-as-judge
AI_EVAL_TOKEN=<token> uv run python -m evals.run_trajectory       # Agent 轨迹
uv run python -m evals.run_rules_retrieval                        # 规则检索（只需要 Redis）
AI_EVAL_TOKEN=<token> uv run python -m evals.run_retrieval        # 商品检索
uv run python -m evals.count_memory_calls                         # 偏好抽取预判的调用次数（不调模型）
```

`judge`、`run_trajectory` 直接调用真实的图（`evals/harness.py`），不经过 HTTP；会话和长期记忆用内存版，不会污染线上数据。写工具会停在确认卡片，不会真的发布。

指标定义：

| 评测（run_all 里的名字） | 指标 | 定义 |
| --- | --- | --- |
| `rules_keyword` | keyword_correct | 回答命中用例 `must` / `also` 里每组备选词中的至少一个。只是粗判，换个说法就会误判 |
| `rules_keyword` | cited | 可回答的问题，回答里带「来源」 |
| `rules_judge` | faithfulness | 评审模型判定：回答里的每条规则事实都能在 `search_rules` 检索到的片段里找到依据（0/1） |
| `rules_judge` | correctness | 评审模型判定：结论和关键数字与 `rules.jsonl` 的 `reference` 一致（0/1）。`reference` 摘自 `knowledge/*.md` |
| `trajectory` | pass_rate | `trajectory.jsonl` 里的工具约束全部满足的用例比例，另外按场景（规则 / 找物 / 订单 / 闲聊 / 写操作）分别统计 |
| `rules_retrieval` | top1 / top3 / mrr | 片段同时命中 `must` 每组备选词即算命中；分别统计向量、关键词、混合、混合+重排 |
| `goods_retrieval` | top5 / mrr | top5 中至少有一件 `relevant` 商品；分别统计向量、关键词、混合、混合+重排 |

- **LLM-as-judge**（`evals/judge.py`）：评审提示词要求每项「先写理由再给分」，`Verdict` 的字段顺序也是先 `reason` 后 `score`，让分数受理由约束。评审模型默认与 `DEEPSEEK_MODEL` 相同，可以用 `AI_EVAL_JUDGE_MODEL` 换成别的。用同一个模型给自己打分会偏宽松，条件允许时换更强的评审模型。
- **轨迹约束**（`evals/trajectory.py`，代码判定，不用模型）：`must_call`（如问规则必须调 `search_rules`）、`must_call_any`（问订单调 `list_my_orders` 或 `get_order`）、`must_not_call`（问订单不能调写工具）、`no_tools`（闲聊不调工具）、`verify_recommended`（最终回答里提到的每件商品都要先用 `get_goods_detail` 核实，且核实结果可购买；「提到」按 `search_goods` 返回商品的 id 或标题出现在回答里判定，换了说法的推荐会漏判）。`tests/test_trajectory.py` 用 ScriptedModel 跑真实的图，验证这些判定函数本身是对的。
- **CI**：`.github/workflows/ai-service.yml` 在每次 push 时跑 pytest。真实评测只在手动触发（workflow_dispatch）时跑：用 docker compose 拉起整套环境、导入演示数据、插入评测用户并签发 token，然后执行 `run_all`，结果作为 artifact 上传。需要在仓库 Secrets 里配置 `DEEPSEEK_API_KEY`。这个 job 还没有在 GitHub 上实际跑过。

2026-11-21 在演示数据上的结果（DeepSeek `deepseek-flash`，embedding `bge-small-zh-v1.5`；当时只有关键词判分）：

| 评测 | 用例 | 结果 | 验收线 |
| --- | --- | --- | --- |
| 商品检索 top5 命中率 | 35 | 混合 97%（向量 100%，关键词 63%） | ≥ 80% |
| 规则问答正确率 | 15 | 100% | ≥ 90% |
| 规则回答带出处 | 15 | 100% | 全部 |

用例是按演示数据编写的，结果偏乐观；有了真实数据后应补充用例再测。

2026-11-27 在演示数据上的结果（同样的模型和 embedding，`run_all` 一次跑完，原始结果见 `evals/results/2026-11-27.json`）：

| 评测 | 用例 | 结果 |
| --- | --- | --- |
| 规则问答·关键词判分 | 15 | 正确率 93.3%（14/15），带出处 100% |
| 规则问答·LLM 评审 | 15 | faithfulness 100%，correctness 100% |
| Agent 轨迹 | 18 | 94.4%（规则、订单、闲聊、写操作 100%，找物 80%） |
| 规则检索 top1 | 14 | 向量 92.9%，关键词 92.9%，混合 92.9%，混合+重排 100% |
| 规则检索 top3 | 14 | 四种方式都是 100% |
| 规则检索 MRR@5 | 14 | 向量 0.964，关键词 0.952，混合 0.964，混合+重排 1.000 |
| 商品检索 top5 | 35 | 向量 97.1%，关键词 62.9%，混合 97.1%，混合+重排 94.3% |
| 商品检索 MRR | 35 | 向量 0.943，关键词 0.629，混合 0.900，混合+重排 0.891 |

- 关键词判分错的那题是「待面交时取消会扣信用分吗」：回答写的是「扣 3 点信用分」，内容正确，但当时的备选词没有「3 点」，属于误判，LLM 评审给了满分。备选词已补上「3 点」。
- 轨迹失败的是「推荐几个降噪耳机」：那次模型只核实了 1 件商品，回答却提到了 3 件。单独重跑 2 次，3 件都先核实了，判定都通过，属于偶发。那次的回答原文没有存下来，分不清是真推荐还是顺带一提；之后的失败记录会带上 `answer` 字段。
- 规则检索：关键词召回单独用 MRR 就有 0.952，说明 jieba 分词生效了；重排把 top1 从 92.9% 提到 100%。和改动前不能完全对比：改动前线上是「向量召回 + 重排」，这次没有单独测这个组合，表里的「向量」一列是不带重排的纯向量。
- 商品检索：重排在演示数据上反而让 top5 和 MRR 都下降了。混合+重排漏掉的是「打游戏的高刷屏」「装电脑的书包」，原因还没查。和 11-21 相比，纯向量从 100% 降到 97.1%（35 条里少命中 1 条）。

## 注意

- 重排序模型约 1.1 GB，Docker 部署时和 embedding 模型一起缓存在 `hf-cache` 卷里；内存紧张时可以把 `RERANK_MODEL` 留空。
- 商品索引每 `GOODS_SYNC_INTERVAL_SECONDS` 秒按 `goods.updated_at` 增量同步。更换 embedding 模型后，需要先删除 Redis 里的 `ai_goods` 索引和 `ai:goods_index:watermark`，再重启服务。
- `search_goods` 会同时调用 Java `/goods?keyword=`，这些查询会计入热搜词。
- 规则知识库每 10 分钟检查一次 `knowledge/` 和公告，内容有变化才重建。修改规则后，记得同步更新 `knowledge/` 里的数值。
- `save_memory` 先用 `should_extract` 做零成本预判：用户消息含偏好信号词（平时 / 一般 / 以后 / 常去 / 不要 / 别给我 / 预算 / 只要面交等，见 `memory.PREFERENCE_SIGNALS`），或者已有偏好且用户在纠正（不是 / 不对 / 改成等），才调用模型抽取，否则跳过。`chat_trace` 里的 `memory_skipped` 记录本轮是否跳过。词表宁宽勿窄：误触发只是多一次调用，漏抽会丢掉用户的偏好。代价是普通找物消息（如「找个台灯」）不再把这次的需求写进 `interests`。抽取仍在回复流式输出完成后同步执行，会推迟 `done` 事件；没有改成后台任务，因为进程退出时后台任务会丢。
- `uv run python -m evals.count_memory_calls` 统计预判的效果：评测集里 68 条找物、规则、订单和闲聊问题，偏好抽取调用从 68 次降到 9 次（13%）；10 条手写的偏好说法全部触发（召回 100%，这组样例是对着词表写的，结果偏乐观）。
- 识图依赖 `deepseek-flash`。2026-11-21 实测 `deepseek-v4-pro` 不能识图，会胡乱描述图片，切换模型前要重新验证。
- 参考价只取「同二级分类 + 品类词命中」的在售商品：至少 3 件时取中位数 ±20%，否则使用模型估价并注明仅供参考。
