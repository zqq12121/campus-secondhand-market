# 校园二手交易平台 · 前端

Vue 3 + TypeScript + Vite + Element Plus + Pinia。按 `docs/接口文档.md` 对接，一个工程两个入口：

- 用户端：`/`（`index.html` → `src/web`）
- 管理后台：`/admin/`（`admin.html` → `src/admin`）

## 运行

```bash
npm install
npm run dev        # Mock 模式，不需要后端，http://localhost:5180
npm run dev:real   # 把 /api、/ws 代理到 .env.real 里的 VITE_API_TARGET；/api/v1/ai 代理到 VITE_AI_TARGET（默认 http://localhost:8090）
npm run build      # 类型检查 + 生产构建
```

## Mock 模式（仅本地自测）

`mock/` 下按接口文档实现了内存版后端，重启 dev server 数据重置。测试账号（仅存在于 Mock 数据中）：

- 用户端：`linxiao2022@wu.edu.cn` / `abc12345`（卖家账号 `chenxx21@wu.edu.cn` 同密码）
- 注册 / 找回密码验证码：`123456`
- 后台：`admin` / `admin123`（超级管理员），`audit01` / `admin123`（普通管理员）
- 担保支付会打开本地模拟的支付宝收银台；Mock 模式没有 WebSocket，聊天和未读数改为轮询。
