# AI 服务阅读指南

这份文档帮你读懂 `ai-service`（校园二手助手）的代码：先看它在系统里的位置和分层，再按推荐顺序读，最后跟着几条关键流程把模块串起来。怎么启动、接口格式、各项配置和评测结果以 [ai-service/README.md](../ai-service/README.md) 为准，本文只讲「代码在哪、为什么这样组织」。

下文的路径都相对 `ai-service/`。

---

## 1. 在整个系统里的位置

```text
浏览器（frontend/src/web/assistant/ 浮窗）
   │ POST /api/v1/ai/chat、/resume  ⇄  SSE 事件流
   ▼
ai-service :8090 ───── 带用户 token ─────► Java 后端 :8080（登录校验、商品、订单、求购、图片）
   │        └───── OpenAI 兼容协议 ──────► DeepSeek（备用模型可选）
   │
   ├── Redis：会话 checkpoint、长期偏好、向量索引、限流、会话锁、选主锁
   └── MySQL 只读账号：增量同步商品做向量索引（只有 goods / category / campus 三张表的 SELECT 权限）
```

它的边界只有三条，读代码时随时可以拿来对照：

1. **不改业务数据**。所有写操作（目前只有发求购）都调 Java 接口，权限由 Java 判断。直接连 MySQL 只为同步商品索引，用的是只读账号。
2. **不维护用户体系**。每个请求拿前端的 token 调 Java 的 `/users/me`，拿到用户就算登录成功，token 黑名单、封号都在 Java 里处理。
3. **自己的状态全在 Redis**。进程本身是无状态的，可以起多个实例（第 7 节）。

**为什么单独用 Python 写**：大模型生态（LangChain、LangGraph、sentence-transformers、PyTorch）以 Python 为主。语言和运行时都不同，这是值得拆成独立服务的理由。

---

## 2. 目录结构

```text
ai-service/
├── app/
│   ├── main.py            HTTP 层：FastAPI 路由、鉴权、限流、会话锁、SSE 事件转换、/memory、/health、/ready、/metrics
│   ├── deps.py            进程级依赖的装配：Redis、Java 客户端、embedding、重排序、两个索引。FastAPI、MCP、评测共用
│   ├── config.py          全部配置（环境变量 → Settings）
│   ├── java_client.py     调 Java：统一响应解析、连接重试、熔断器 CircuitBreaker
│   ├── guard.py           规则式安全防护：输入注入检测、工具结果过滤、输出检查
│   ├── observability.py   TraceCollector 回调（耗时、token、成本）、可选 Langfuse
│   ├── metrics.py         Prometheus 指标定义
│   ├── ratelimit.py       按用户的分钟 / 每日限流
│   ├── redis_lock.py      Redis 分布式锁 + 选主执行 run_as_leader
│   ├── mcp_server.py      把 5 个只读工具暴露成 MCP Server
│   ├── graph/             Agent 层
│   │   ├── builder.py     系统提示词、LangGraph 状态图、模型降级组合
│   │   ├── tools.py       5 个只读工具（*_impl 是不依赖 LangChain 的实现）
│   │   ├── drafts.py      2 个写工具：起草 → interrupt() 等用户确认 → 才产生副作用
│   │   ├── memory.py      长期偏好：5 个槽位、抽取预判、结构化输出
│   │   ├── images.py      用户图片转 data URL、历史图片换占位
│   │   └── context.py     RequestContext：一次请求的运行时上下文（token、用户、客户端）
│   └── rag/               检索层
│       ├── embedder.py    本地 bge-small-zh，或外置 TEI 服务
│       ├── reranker.py    本地 bge-reranker-base，或外置 TEI 服务
│       ├── goods_index.py 商品：MySQL 增量同步 → Redis 向量索引；向量 + Java 关键词 RRF 混合，再重排
│       └── rules_index.py 规则：knowledge/*.md + 公告切片；向量 + jieba 全文 RRF 混合，再重排；蓝绿重建
├── knowledge/             平台规则知识库（5 个 Markdown，按 ## 切片）
├── tests/                 单测：假模型、假 Java，不依赖外部服务（第 9 节）
├── evals/                 真实环境的回归评测（第 9 节）
├── Dockerfile
└── pyproject.toml / uv.lock
```

---

## 3. 分层与依赖方向

```text
main.py / mcp_server.py / evals/harness.py     入口：各自建 RequestContext，调同一张图或同一批工具
        │
        ▼
graph/（builder → tools / drafts / memory / images）   Agent：提示词、状态图、工具
        │
        ▼
rag/、java_client.py、guard.py                  能力：检索、调 Java、防护
        │
        ▼
deps.py + config.py                             装配和配置
```

几条约定，读代码时会反复看到：

1. **依赖只在 `deps.py` 里创建**。`build_deps` 建好 Redis、Java 客户端、模型和索引，FastAPI 的 `lifespan`、MCP Server 的 `default_lifespan`、评测的 `evals/harness.py` 都调用它，不各写一套初始化逻辑。后台同步任务不在 `build_deps` 里，只有 `main.py` 的 `lifespan` 启动它们。
2. **运行时上下文放在 `RequestContext` 里**，通过 `config["configurable"]["ctx"]` 传进图，节点和工具用 `get_ctx(config)` 取出来。它刻意做成对象而不是字符串：LangGraph 会把 configurable 里的 str / int 写进 checkpoint 元数据，token 如果以字符串传入，就会被存进 Redis（见 `graph/context.py` 的说明）。
3. **工具只通过 `ctx` 访问外部**：`ctx.java` 调 Java，`ctx.goods_index`、`ctx.rules_index` 做检索。所以测试只要换掉 `ctx` 里的对象就能隔离外部依赖。
4. **只读工具写成两层**：`search_goods_impl(ctx, ...)` 是普通的 async 函数，`@tool` 包装的 `search_goods(..., config)` 只负责取出 `ctx` 再调用它。MCP Server 直接调 `*_impl`，两边共享同一份实现和同一份注入过滤（`_dump`）。
5. **写工具不暴露给 MCP**。它们依赖 LangGraph 的 `interrupt()` 停下来等用户确认，MCP 是一次请求一次返回，没有这套暂停和恢复的机制。

---

## 4. 一个 /chat 请求是怎么走完的

以「贝克兰德 100 元以内的台灯」为例，入口是 `main.py` 的 `chat`：

1. **鉴权**：`current_user` 依赖取 `Authorization`，调 `java.get_me`。401 表示未登录或 token 失效；Java 熔断中返回 503。
2. **校验图片**：`images` 里的地址必须以 `UPLOAD_PUBLIC_BASE_URL` 开头，否则返回 400，防止服务器被诱导去访问内网地址（SSRF）。
3. **限流 + 建上下文**：`guarded_context` 调 `ratelimit.check_rate`，超限返回 429；再取分类和校区列表（缓存 10 分钟），组装 `RequestContext`。
4. **会话锁**：`lock_conversation` 按 `用户id:会话id` 加 Redis 锁，拿不到返回 409「上一条消息还在处理」。锁覆盖整个 SSE 流，在流结束时释放。
5. **输入防护**：`guard.is_injection` 命中就走 `refusal`，返回固定的拒答，不进模型，也不写入会话历史。
6. **清理悬空的确认卡片**：`cancel_pending` 发现会话里有没确认的写操作时，补上「已取消」的工具结果。否则消息列表里会留下一个没有结果的 tool_call，下次调用模型 API 会报错。
7. **执行图**：`stream_chat` 调 `graph.astream(..., stream_mode=["messages", "updates"])`，图的执行顺序见第 6.1 节。
8. **转换成 SSE 事件**（还是在 `stream_chat` 里）：
   - `messages` 模式里，只有 `langgraph_node == "agent"` 的文字片段才转成 `token` 事件。摘要和偏好抽取也是模型调用，同样会被流式输出，必须按节点过滤掉。
   - `updates` 模式里，`AIMessage.tool_calls` → `tool start`；`ToolMessage` → `tool end`，并用 `goods_card` / `listing_draft` 从工具结果里解析出 `goods` / `listing` 事件；`__interrupt__` → `confirm`；`output_guard` 节点改写了回复 → `revise`。
   - 正常结束发 `done`，出现异常发 `error`。
9. **收尾**（`finally`）：先释放会话锁，再记 Prometheus 指标（`metrics.observe_trace`），最后输出一行 `chat_trace` JSON 日志。顺序是刻意安排的：统计和日志即使出错，也不会让锁留到 TTL 过期。

`/resume`（用户点了确认卡片）走的是同一个 `stream_chat`，区别只有两点：先检查 `interruptId` 确实在待确认列表里（否则返回 409），图的输入是 `Command(resume=...)` 而不是新消息。

---

## 5. 推荐阅读顺序

| 步骤 | 读什么 | 学到什么 | 对照的测试 |
| --- | --- | --- | --- |
| 1 | `config.py`、`deps.py` | 有哪些可配的东西，进程启动时建了哪些对象 | — |
| 2 | `main.py` 的 `chat` → `stream_chat` | 第 4 节的完整链路，SSE 事件从哪来 | `test_api.py` |
| 3 | `graph/builder.py`：先读 `SYSTEM_PROMPT`，再读 `build_graph` | 提示词结构、状态图的 6 个节点和边 | `test_graph.py` |
| 4 | `graph/tools.py` + `graph/context.py` | 工具怎么定义、`ctx` 怎么注入、`*_impl` 两层写法 | `test_m2.py` |
| 5 | `rag/goods_index.py` 的 `hybrid_search`、`rrf_merge` | 两路召回、RRF、重排、索引增量同步 | `test_units.py`、`test_m5.py` |
| 6 | `rag/rules_index.py` | 按标题切片、jieba 全文召回、蓝绿重建 | `test_rules_hybrid.py`、`test_multi_instance.py` |
| 7 | `graph/drafts.py` + `main.py` 的 `resume`、`cancel_pending` | 人工确认：`interrupt()` 暂停、`Command` 恢复 | `test_m3.py` |
| 8 | `graph/memory.py` + `builder.py` 的 `prepare`、`save_memory` | 摘要压缩、偏好抽取预判、结构化输出 | `test_m2.py`、`test_graph.py` |
| 9 | `guard.py` + `builder.py` 的 `output_guard` | 输入、工具结果、输出三处防护 | `test_m5.py` |
| 10 | `observability.py`、`metrics.py` | 回调统计、成本、Prometheus 指标 | `test_observability.py` |
| 11 | `java_client.py` 的 `CircuitBreaker`、`builder.py` 的 `with_fallback` | 熔断、模型降级 | `test_resilience.py` |
| 12 | `redis_lock.py` + `main.py` 的 `lifespan` | 分布式锁、选主执行后台同步 | `test_multi_instance.py`、`test_conversation_lock.py` |
| 13 | `mcp_server.py` | 同一批工具怎么再暴露成 MCP | `test_mcp.py` |

前 4 步读完就能看懂一次普通对话；5～9 步是功能；10～13 步是上线需要的工程化能力，可以按需读。每读完一步，就去对应的测试里看一遍，测试是最直接的用法示例。

---

## 6. 关键流程的代码路线

### 6.1 状态图与工具调用循环

`graph/builder.py` 的 `build_graph`：

```text
START → load_memory → prepare → agent ⇄ tools
                                  │
                                  └─（没有 tool_calls）→ output_guard → save_memory → END
```

- `load_memory`：读长期偏好，放进 `state["profile"]`。
- `prepare`：消息超过 `AI_HISTORY_MAX_MESSAGES` 条时压缩成摘要（6.5 节）。
- `agent`：拼系统提示词（`_format_ctx`）→ 处理图片（`prepare_for_model`）→ 调用绑定了工具的模型。本轮工具调用次数达到 `AI_MAX_TOOL_CALLS` 时，改用不带工具的模型，并附一句「请直接作答」，防止死循环。
- `tools_condition`：最后一条消息有 `tool_calls` 就进 `tools`（LangGraph 现成的 `ToolNode`），执行完回到 `agent`；没有就进 `output_guard`。
- 状态 `State` 继承 `MessagesState`。节点返回的消息会**追加**进历史；id 相同则**替换**（`output_guard` 改写回复靠这一点）；返回 `RemoveMessage` 则**删除**（摘要压缩靠这一点）。

系统提示词的固定部分在前，校区、分类、当前用户这些每次请求才填入的内容放在末尾。这样不同用户的请求共享同一段前缀，能命中 DeepSeek 的上下文缓存，按缓存价计费（`test_observability.py::test_system_prompt_shares_prefix_across_users`）。

### 6.2 写操作的人工确认

1. 模型调用 `drafts.draft_want_post`。工具先校验，再 `interrupt({"kind": "want_post", "draft": ...})`，图在这里暂停，状态存进 checkpoint。
2. `stream_chat` 在 `updates` 里收到 `__interrupt__`，转成 `confirm` 事件，`done` 里带 `pending: true`。
3. 用户确认后，前端调 `/resume`，`main.resume` 构造 `Command(resume={interruptId: {decision, edits}})` 继续执行图。
4. 工具里 `interrupt()` 返回用户的决定 → `apply_edits` 只接受白名单字段 → **重新校验** → 调 Java `POST /wants`。

**最关键的一点**：恢复时 LangGraph 会**从头重新执行**整个 tools 节点，`interrupt()` 之前的代码会再跑一遍。所以 `interrupt()` 之前只能放校验和只读查询，写操作一律放在它之后（`drafts.py` 文件开头的说明）。

`suggest_listing`（照片生成发布草稿）确认后不调任何写接口，只通过 `listing` 事件把草稿交给前端预填发布页，最后一步「发布」由用户自己点。

### 6.3 商品检索

`tools.search_goods_impl` → `goods_index.hybrid_search`：

1. 用 `asyncio.gather` 并发执行两路召回：`GoodsIndex.vector_search`（Redis HNSW，带校区、分类、价格过滤）和 `java.search_goods`（Java 关键词搜索），各取 20 条。
2. 任意一路失败就只用另一路的结果。
3. `rrf_merge` 按排名合并，每路第 r 名得 `1/(60+r)` 分。
4. 有重排序模型时，对前 30 条重排；重排失败就退回 RRF 的顺序。最终返回 10 条。

索引同步在 `GoodsIndex.sync_once`：按水位线拉取 `updated_at >= 上次时间` 的行，在售的写入索引，下架或卖出的删掉。比较用 `>=`，同一秒内的更新不会漏，重复处理也是幂等的。

索引可能比实际状态晚几分钟，所以提示词要求推荐前必须调 `get_goods_detail` 实时核实，只有核实过、可购买的商品才会被 `main.goods_card` 推成卡片。检索负责召回，核实负责保证正确。

### 6.4 规则问答

`tools.search_rules_impl` → `RulesIndex.search`：

1. **切片**（`split_markdown`）：按 `## ` 切节，超过 500 字的再按段落拆分。每片的标题是「文档标题 > 小节标题」，这个标题就是回答末尾注明的出处。
2. **两路召回**：向量召回，加上 jieba 分词后的全文（BM25）召回。规则里有很多数字和专有词（「15 分钟」「信用分」），向量召回对这些不敏感，全文召回正好补上。领域词在 `DOMAIN_WORDS` 里，虚词在 `STOPWORDS` 里。
3. RRF 合并，再重排。

**蓝绿重建**（`RulesIndex.refresh`）：每 10 分钟收集一次 `knowledge/` 和 Java 公告，算出内容 hash。hash 变了就写入一个新索引 `ai_rules_<hash>`，完整写好后才把指针 `ai:rules_index:current` 切过去，再删掉旧索引。重建期间检索一直读旧版本。hash 里包含 schema 版本和向量维度，所以换 embedding 模型也会自动触发重建。

### 6.5 记忆

| | 短期：本次会话 | 长期：跨会话偏好 |
| --- | --- | --- |
| 存在哪 | checkpoint（`AsyncRedisSaver`），30 天过期，读取时续期 | Store（`AsyncRedisStore`），命名空间 `("user", 用户id)` |
| 写入 | 图每执行完一个节点自动存一次 | `save_memory` 节点 |
| 长度控制 | `prepare`：超过 20 条压缩成摘要，保留最近约 10 条 | 固定 5 个槽位，每个值不超过 60 字 |
| 关键代码 | `builder.split_for_summary` | `memory.should_extract`、`memory_extractor`、`clean_update` |

- `split_for_summary` 的切点必须落在用户消息上，保证一个 tool_call 和它的结果不会被拆开。
- `should_extract` 是零成本的预判：用户消息里有「平时、预算、以后、不要……」这类信号词才调用模型抽取，普通的找物、问规则直接跳过，省一次模型调用。词表宁宽勿窄，多调一次没有副作用，漏抽就丢了用户说过的偏好。
- 抽取用结构化输出（`with_structured_output(MemoryUpdate, method="function_calling")`），再由 `clean_update` 过滤掉白名单以外的槽位，模型想记手机号也会被丢弃。
- 用户可以在「我的偏好」页查看和删除，对应 `main.py` 的三个 `/memory` 接口。

### 6.6 识图

`graph/images.py`：checkpoint 里只存图片的公开地址。每次调用模型前，`prepare_for_model` 把**本轮**用户消息里的图片经 `java.fetch_upload` 走内网读出来，转成 base64 data URL（DeepSeek 访问不到内网地址）；**更早轮次**的图片换成文字占位，省 token。转好的结果缓存在 `ctx.image_cache`，一轮对话里多次调用模型时不重复读取。`fetch_upload` 拒绝含 `..` 的路径，只接受 4 种图片 MIME，大小不超过 5 MB。

---

## 7. 横切关注点：在哪实现

| 关注点 | 代码位置 | 要点 |
| --- | --- | --- |
| 输入注入检测 | `guard.is_injection`，`main.chat` 里调用 | 命中直接拒答，不进模型 |
| 间接注入（工具结果） | `guard.redact_untrusted`，在 `tools._dump` 里统一调用 | 所有只读工具的返回值都经过这里，商品描述里的指令整句替换掉 |
| 输出检查 | `guard.check_output`，`builder` 的 `output_guard` 节点 | 提示词泄露整段替换，手机号和身份证号打码，发 `revise` 事件 |
| 限流 | `ratelimit.check_rate` | Redis `INCR` + `EXPIRE` 固定窗口，每分钟和每天（东八区自然日）两档 |
| 会话互斥 | `main.lock_conversation` / `release_lock` | 同一会话同时只处理一个请求，锁覆盖整个 SSE 流，客户端断开也会释放 |
| 后台任务选主 | `redis_lock.run_as_leader`，`main.lifespan` 里启动 | 多实例时只有持锁实例同步索引；续期失败立即停止 |
| 模型降级 | `builder.with_fallback`，`main.build_fallback_llm` | 只在超时、429、5xx 时切到备用模型；主、备模型各自 `bind_tools` 再组合 |
| Java 熔断 | `java_client.CircuitBreaker` | 连续失败 5 次熔断 30 秒，之后半开放行一个试探请求；只有 5xx 和网络错误算失败 |
| 链路统计 | `observability.TraceCollector` | 每个请求一个实例，经 `config["callbacks"]` 传进图，统计每次模型和工具调用 |
| 指标 | `metrics.py`，`/api/v1/ai/metrics` | 标签只用取值有限的维度，不用用户 id 和会话 id，避免时间序列数无限增长 |
| 健康检查 | `/health`（进程活着）、`/ready`（Redis 通、规则索引已建好） | 接负载均衡或编排的健康检查时用 `/ready`，目前 compose 里还没配 |

---

## 8. Redis 里存了什么

排查问题时最常用的一张表。ai-service 和 Java 共用一个 Redis，`ai:` 前缀和几个索引名都是 ai-service 的。

| 键 / 索引 | 内容 | 写入方 |
| --- | --- | --- |
| `checkpoint*`、`checkpoint_write*`、`write_keys_zset*` | 会话状态快照（langgraph-checkpoint-redis 自己的键） | 图执行时自动写入 |
| `ai:memory` 前缀 | 长期偏好（Store） | `save_memory`、`/memory` 接口 |
| 索引 `ai_goods`，键 `ai:goods:<商品id>` | 商品向量和过滤字段 | `GoodsIndex.sync_once` |
| `ai:goods_index:watermark` | 商品同步的水位线 | 同上 |
| 索引 `ai_rules_<hash>`，键 `ai:kb:<hash>:*` | 某个版本的规则片段 | `RulesIndex.refresh` |
| `ai:rules_index:current` | 当前规则版本的指针 | 同上 |
| `ai:rl:m:<用户>:<分钟>`、`ai:rl:d:<用户>:<日>` | 限流计数 | `check_rate` |
| `ai:conv_lock:<用户>:<会话>` | 会话锁 | `lock_conversation` |
| `ai:leader:goods_sync`、`ai:leader:rules_sync` | 选主锁 | `run_as_leader` |

更换 embedding 模型后，要先删掉 `ai_goods` 索引和 `ai:goods_index:watermark`，否则新旧向量的维度对不上。规则索引不用手动处理，hash 变了会自动重建。

---

## 9. 测试与评测在哪

### 单测（`tests/`）

不调用 DeepSeek，不依赖 Redis、Java 或网络，每次 push 都在 CI 里跑（`.github/workflows/ai-service.yml`）。核心在 `tests/fakes.py`：

- `ScriptedModel`：继承 `BaseChatModel` 的假模型，按剧本依次返回回复（可以是工具调用），用来精确控制图的走向。偏好抽取的调用单独应答，不占用剧本。
- `FakeJava`、`FakeIndex`、`FakeReranker`、`FakeRedis`：外部依赖的内存实现。
- 接口测试用 `httpx.ASGITransport` 在进程内调用 FastAPI，不用起服务器。

| 文件 | 覆盖 |
| --- | --- |
| `test_api.py` | HTTP 接口：鉴权、SSE 事件序列、续聊、限流、出错、`/memory` |
| `test_graph.py` | 图的流转：先搜后核实再回答、追问保留条件、工具次数上限、摘要压缩 |
| `test_units.py` | 纯函数：RRF、过滤表达式、切点、限流计数 |
| `test_m2.py` | 长期记忆、规则切片、订单工具、规则检索工具 |
| `test_m3.py` | 写工具、`interrupt` 确认、`/resume`、图片输入 |
| `test_m5.py` | 注入检测的正反例、工具结果过滤、输出打码、链路日志、重排序 |
| `test_rules_hybrid.py` | 规则库的向量 + 全文混合召回及降级 |
| `test_observability.py` | 首 token 延迟、缓存命中计费、Prometheus 指标、就绪检查、提示词前缀 |
| `test_resilience.py` | 模型降级、Java 熔断 |
| `test_conversation_lock.py` | 同一会话的并发请求 |
| `test_multi_instance.py` | 分布式锁、选主、规则库蓝绿重建 |
| `test_tei.py` | 外置 TEI 服务（`httpx.MockTransport` 模拟） |
| `test_mcp.py` | MCP Server 的工具列表、参数 schema、鉴权 |
| `test_trajectory.py` | 验证评测里的轨迹判定函数本身是对的 |

### 评测（`evals/`）

单测证明「模型返回 X 时系统会做 Y」，评测用真实模型衡量效果好不好。需要 Java、Redis、演示数据和 DeepSeek 密钥，命令和指标定义见 README 的「评测」一节。

- `run_all.py`：依次跑各项评测，结果写进 `evals/results/<日期>.json`，方便前后对比。
- `harness.py`：直接调用真实的图（不经过 HTTP），会话和记忆用内存版，不会污染线上数据。`judge.py` 和 `run_trajectory.py` 都基于它。
- 评测集：`retrieval.jsonl`（商品检索）、`rules.jsonl`（规则问答）、`trajectory.jsonl`（工具调用轨迹的约束）。

---

## 10. 常见改动从哪下手

| 想做什么 | 改哪里 | 别忘了 |
| --- | --- | --- |
| 加一个只读工具 | `graph/tools.py`：写 `xxx_impl(ctx, ...)`，再用 `@tool` 包一层，结果用 `_dump` 输出；加进 `TOOLS` 和 `READ_ONLY_TOOLS` | `mcp_server.py` 里同步暴露（`test_mcp.py` 按 `READ_ONLY_TOOLS` 核对两边的参数 schema）；前端 `stores/assistant.ts` 的 `TOOL_LABELS` 加中文提示；`trajectory.jsonl` 加约束用例 |
| 加一个写工具 | `graph/drafts.py`：校验 → `interrupt()` → 白名单应用修改 → 再校验 → 写操作 | 写操作必须在 `interrupt()` 之后；不要加进 MCP；前端 `ConfirmCard.vue` 支持新的 `kind` |
| 改提示词 | `graph/builder.py` 的 `SYSTEM_PROMPT` | 固定内容放前面，每次请求才变的放末尾（缓存命中）；改完跑评测 |
| 加平台规则 | `knowledge/*.md`，按 `## ` 分节 | 本地运行时不用重启，10 分钟内自动重建；Docker 部署时 `knowledge/` 打包在镜像里，要重新构建镜像。重要规则在 `rules.jsonl` 加用例 |
| 换模型 / 加备用模型 | `.env`：`DEEPSEEK_MODEL`、`FALLBACK_*` | 换模型前确认它支持识图和工具调用 |
| 换 embedding 模型 | `.env`：`EMBEDDING_MODEL` 或 `EMBEDDING_SERVICE_URL` | 删除 `ai_goods` 索引和水位线（第 8 节） |
| 加一个偏好槽位 | `graph/memory.py` 的 `SLOTS`，必要时补 `PREFERENCE_SIGNALS` | 抽取提示词里的槽位说明（`EXTRACT_PROMPT` 的规则）；`test_m2.py`。前端偏好页的名称取自接口返回的 `label`，不用改 |
| 加一种 SSE 事件 | `main.stream_chat` 里产出 | 前端 `assistant/api.ts` 的 `StreamEvent` 类型和 `stores/assistant.ts` 的处理分支；README 的事件表 |
| 加一个配置项 | `config.py` 的 `Settings` 加字段（自动读对应的大写环境变量） | 仓库根目录 `.env.example` 加说明 |
| 加一条注入规则 | `guard.py` 的 `INJECTION_PATTERNS`（只针对工具结果的放 `TOOL_ONLY_PATTERNS`） | 在 `test_m5.py` 里同时加正例和不该误判的反例 |
