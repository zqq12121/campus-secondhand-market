# 校园二手交易平台

面向高校学生的二手交易平台：同校区发布与搜索闲置、站内聊天、面交或支付宝担保交易、售后纠纷由平台裁决，另有求购广场、信用分和管理后台。附带一个基于 LangGraph 的 AI 助手，负责找物、规则问答和辅助发布。

## 功能

| 模块 | 内容 |
| --- | --- |
| 账号 | 校园邮箱验证码注册 / 找回密码（图形验证码防刷），JWT 登录，连续失败 3 次要求图形验证码、5 次锁定 |
| 商品 | 发布 / 草稿 / 编辑 / 上下架，敏感词 + 信用分自动审核，MySQL 或 Elasticsearch 全文检索、热搜，收藏与降价提醒，猜你喜欢 |
| 求购 | 求购广场、到期自动过期、商品上架后异步匹配求购并通知、「我有」响应计数 |
| 聊天 | WebSocket（STOMP）实时消息、商品卡片、在线状态、站外交易风险提示 |
| 交易 | 面交订单（6 位核销码）；担保订单（支付宝沙箱 / 本地模拟收银台）、交货、确认收货放款；RabbitMQ 延时消息处理各类超时 |
| 售后 | 交货前取消、收货前退款、卖家拒绝后申请平台介入、超时自动同意 / 自动确认收货 |
| 信用与评价 | 信用分与等级、扣分申诉、双向评价、举报 |
| 社交与通知 | 关注卖家（上新通知）、站内通知 + WebSocket 角标，可选邮件通知 |
| 管理后台 | 数据看板、商品审核、用户管理、订单查询、纠纷裁决、举报处理、信用分申诉、分类管理、公告 / 轮播 / 敏感词、管理员与角色权限、操作日志 |
| AI 助手 | 自然语言找物（向量 + 关键词混合检索）、平台规则问答（带出处）、长期偏好记忆、订单查询、发求购 / 看图生成发布草稿（需用户确认） |

## 架构

```text
浏览器 ──► Nginx（前端静态资源 /、/admin/）
              │ /api、/ws、/uploads               │ /api/v1/ai
              ▼                                   ▼
        Java 后端 :8080  ◄──── 带用户 token 调用 ──── ai-service :8090（Python）
        Spring Boot 3                              FastAPI + LangGraph + DeepSeek
              │                                    │
   MySQL 8 ─ Redis ─ RabbitMQ                MySQL 只读 + Redis（向量索引、记忆）
        └─ Elasticsearch（可选，SEARCH_ENGINE=es）
```

| 目录 | 说明 |
| --- | --- |
| [backend](backend/README.md) | Spring Boot 3.5 + MyBatis-Plus + Spring Security（JWT）+ Redis + RabbitMQ + WebSocket |
| [frontend](frontend/README.md) | Vue 3 + TypeScript + Vite + Element Plus + Pinia；用户端和后台两个入口，自带 Mock 后端 |
| [ai-service](ai-service/README.md) | Python 3.11 + FastAPI + LangGraph，DeepSeek 模型，本地 bge-small-zh 向量 |
| [docs/阅读指南.md](docs/阅读指南.md) | 从零读懂项目：包结构约定、模块地图、推荐阅读顺序、关键流程的代码路线、中间件用途 |
| [docs/接口文档.md](docs/接口文档.md) | 前后端接口约定，含状态机、错误码和设计假设 |
| docker/elasticsearch | 带 smartcn 中文分词插件的 ES 镜像（`docker compose --profile es`） |
| [sql](sql) | `schema.sql` 表结构、`init_data.sql` 初始数据、`demo_goods.sql` 演示商品、`upgrade/` 已有库升级脚本 |
| prototype | 早期静态原型页面 |

## 快速开始

只看前端（不需要后端）：

```bash
cd frontend && npm install && npm run dev
```

打开 http://localhost:5180 ，测试账号见 [frontend/README.md](frontend/README.md)。

完整运行：

1. 复制环境变量模板并填写：

   ```bash
   cp .env.example .env
   ```

2. 启动 MySQL、Redis、RabbitMQ：

   ```bash
   set -a && source .env && set +a && docker compose up -d
   ```

3. 启动后端和前端，步骤见 [backend/README.md](backend/README.md)；AI 助手见 [ai-service/README.md](ai-service/README.md)。

一键部署全部服务：

```bash
set -a && source .env && set +a && docker compose --profile app up -d --build
```

## 测试

```bash
cd backend && mvn test
```

```bash
cd frontend && npm run build
```

AI 助手的单测和评测见 [ai-service/README.md](ai-service/README.md)。
