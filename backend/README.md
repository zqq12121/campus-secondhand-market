# 校园二手交易平台 · 后端

Spring Boot 3.5 + MyBatis-Plus + Spring Security（JWT）+ MySQL 8 + Redis + RabbitMQ + WebSocket（STOMP），按 `docs/接口文档.md` 实现。

## 当前进度：V1.2 接口文档全部完成

| 模块 | 接口文档章节 | 状态 |
| --- | --- | --- |
| 公共（校区、分类、上传、首页、公告） | 2 | 已完成 |
| 账号与认证 | 3 | 已完成 |
| 个人中心与用户主页、拉黑 | 4 | 已完成 |
| 商品、搜索、热搜、收藏 | 5 | 已完成 |
| 站内聊天（HTTP + STOMP）、在线状态、站外交易提示 | 7 | V1.1 完成 |
| 面交订单 | 8 | 已完成 |
| 担保订单：支付、查询补偿、异步通知、交货、确认收货放款 | 8.7–8.11 | V1.1 完成 |
| 售后：交货前取消、收货前退款、拒绝、介入、撤销、超时自动同意、被拒后超时自动确认收货 | 9 | V1.1 完成 |
| 评价 | 10 | 已完成 |
| 通知（含 WebSocket 实时推送） | 11 | V1.1 完成 |
| 后台：登录、商品审核与管理、用户管理、操作日志 | 13.1 / 13.3–13.5 | 已完成 |
| 后台：订单查询、纠纷裁决 | 13.6–13.7 | V1.1 完成 |
| 求购广场（含到期自动过期）、用户举报 | 6、12 | V1.2 完成 |
| 求购匹配通知（商品上架 / 审核通过后异步匹配）、「我有」发起会话校验 `wantId` | 5.4、6、7.1、13.3 | V1.2 完成 |
| 后台：举报处理（成立时下架/关帖/扣分/封禁，同对象待处理举报一并处理） | 13.8 | V1.2 完成 |
| 后台：分类管理（两级、启停级联、批量排序、删除校验） | 13.9 | V1.2 完成 |
| 后台：数据看板（`stat_daily` 每小时汇总，启动时从最后统计日补算到当天） | 13.2 | V1.2 完成 |
| 后台：内容运营（公告/轮播、公告群发通知、敏感词 Redis 广播热更新） | 13.10 | V1.2 完成 |
| 后台：系统管理（管理员、角色与权限、操作日志查询） | 13.11 | V1.2 完成 |

## 本地运行

1. 在项目根目录复制环境变量模板并填写（`.env` 已被 git 忽略）：

   ```bash
   cp .env.example .env
   ```

2. 启动 MySQL、Redis、RabbitMQ（MySQL 首次启动会自动执行 `sql/schema.sql` 和 `sql/init_data.sql`）：

   ```bash
   set -a && source .env && set +a && docker compose up -d
   ```

   先 `source .env` 再启动：如果 shell 里已经导出了同名变量（比如别的项目的 `MYSQL_PASSWORD`），docker compose 会优先使用 shell 里的值。

3. 启动后端（默认端口 8080）：

   ```bash
   cd backend && set -a && source ../.env && set +a && mvn spring-boot:run
   ```

- 接口文档（OpenAPI）：http://localhost:8080/swagger-ui.html
- `MAIL_ENABLED=false` 时，邮箱验证码只打印到后端日志（搜索 `[MAIL_DISABLED]`）。
- 首次启动且库里没有管理员时，用 `ADMIN_INIT_USERNAME` / `ADMIN_INIT_PASSWORD` 创建超级管理员。
- 前端联调：在 `frontend/` 下执行 `npm run dev:real`，`/api` 和 `/ws` 会代理到 8080。
- RabbitMQ 管理台：http://localhost:15672（guest / guest，仅本地）。

### 测试

```bash
cd backend && mvn test
```

单元测试不依赖数据库和中间件：订单状态机、信用等级、敏感词 DFA、敏感词批量导入、求购匹配、「我有」的 `wantId` 校验。

### Docker 部署（后端 + 前端）

```bash
set -a && source .env && set +a && docker compose --profile app up -d --build
```

- 不带 `--profile app` 时只起 MySQL / Redis / RabbitMQ，和本地开发一致。
- 前端镜像用 Nginx 托管用户端（`/`）和后台（`/admin/`），并把 `/api`、`/ws`、`/uploads` 转发到后端容器。访问地址为 `http://localhost:${WEB_PORT}`，默认端口 8088。
- `.env` 里的 `PUBLIC_BASE_URL` 是浏览器访问站点的地址，图片地址和支付宝返回地址都由它生成。上传文件存放在 `uploads` 数据卷里。

### 支付模式

| `PAY_MODE` | 说明 |
| --- | --- |
| `mock`（默认） | 本地模拟收银台 `/api/pay/mock/cashier`，点「确认支付」后走与支付宝通知相同的入账逻辑；退款直接成功。用于没有沙箱密钥时开发演示 |
| `alipay` | 支付宝开放平台沙箱（电脑网站支付）。需在 `.env` 填 `ALIPAY_APP_ID`、`ALIPAY_PRIVATE_KEY`、`ALIPAY_PUBLIC_KEY` |

支付宝异步通知需要公网可达的 `ALIPAY_NOTIFY_URL`。本地收不到通知时，前端从收银台返回后会轮询 `pay-status`，后端会主动查询支付宝做补偿；订单超时关闭前也会先查一次。

### 超时时长

全部可通过环境变量覆盖（Spring Duration 格式，如 `20s`、`15m`、`7d`），本地测试时可以调短：

| 变量 | 默认 | 含义 |
| --- | --- | --- |
| `RULE_PAY_TIMEOUT` | 15m | 担保订单未支付自动取消 |
| `RULE_FACE_CONFIRM_TIMEOUT` | 24h | 面交卖家未确认自动取消 |
| `RULE_FACE_AUTO_COMPLETE` | 7d | 面交卖家确认后自动完成 |
| `RULE_AUTO_CONFIRM` | 7d | 担保交货后自动确认收货（售后期间暂停） |
| `RULE_AFTER_SALE_CANCEL_TIMEOUT` | 24h | 交货前取消，卖家未处理自动同意 |
| `RULE_AFTER_SALE_REFUND_TIMEOUT` | 48h | 收货前退款，卖家未处理自动同意 |
| `RULE_AFTER_SALE_APPEAL_TIMEOUT` | 48h | 收货前退款被拒，买家未介入也未撤销，自动确认收货并放款 |

## 代码结构

```text
com.campustrade
├── common        统一响应、错误码、分页、状态常量、全局异常
├── config        配置属性、Jackson、MyBatis-Plus、Security、WebSocket、静态资源
├── security      JWT 签发/解析、HTTP 过滤器、STOMP 鉴权、token 失效（Redis）
├── infra         限流、邮件、敏感词 DFA、本地文件存储、在线状态、实时推送、mq（延时队列）
├── entity/mapper 与 sql/schema.sql 一一对应
└── module        按业务分包：auth、user、common、goods、want、chat、order、pay、aftersale、review、report、notification、admin
```

## 实现要点

- **ID 与金额**：Long 全局序列化为字符串，BigDecimal 序列化为 2 位小数字符串（接口文档 1.5）。
- **token 失效**：登出时 jti 进 Redis 黑名单；改密码或封禁时写入毫秒级时间点，此前签发的 token 全部失效。
- **库存**：下单时用 `goods.version` 乐观锁扣库存，冲突重试 3 次；库存归 0 时商品进入「交易中」。V1.2 再加 Redis 预扣。
- **订单状态机**：`OrderStateMachine` 校验「状态 + 动作 + 操作人」，更新时带状态和版本号条件，并写 `order_log`。
- **超时处理**：RabbitMQ「TTL 队列 + 死信」实现延时消息（每种超时一个队列，队列名带 TTL 并作为路由键，改时长后旧队列不会再收到消息）。消费时重新校验状态和截止时间，过时或重复的消息直接忽略；投递失败或 MQ 不可用时，由每分钟一次的定时扫描兜底。
- **状态流转**：`OrderFlow` 统一完成状态机校验、乐观锁更新、写 `order_log`、通知对方、在双方会话插入系统消息、投递延时消息。
- **支付幂等**：入账以 `out_trade_no` 加状态条件更新，重复通知只处理一次；订单已关闭后才到账的，自动原路退款。放款靠 `balance_log (order_id, type)` 唯一键，保证同一订单只放款一次。
- **退款**：渠道退款在事务外调用，`out_refund_no` 保证幂等；调用失败时售后保持「已同意 / 裁决退款」，由定时任务重试。
- **售后暂停自动确认**：不加字段。自动确认时间 = 交货时间 + 7 天 + 历次已关闭的收货前退款持续时长。
- **实时推送**：STOMP 简单代理，`/user/queue/messages|notifications|errors`；推送在事务提交后发送，CONNECT 帧用用户端 JWT 鉴权。
- **审核策略**：敏感词未命中且信用分 ≥ 80 时直接上架，其余转人工审核，每次都写 `audit_record`。
- **求购匹配**：商品进入在售状态后（自动审核通过或人工审核通过），在事务提交后异步匹配同校、同二级分类、价格落在预算内的进行中求购，排除卖家本人和拉黑了卖家的求购者，单个商品最多通知 100 个求购帖。Redis 记录「求购帖 + 商品」，30 天内不重复通知。
- **上传**：V1.0 存本地磁盘（`UPLOAD_DIR`），通过 `/uploads/**` 访问；按文件头校验，只允许 jpg/png/webp。
