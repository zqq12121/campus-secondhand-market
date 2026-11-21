# 校园二手助手：FastAPI + LangGraph。Linux 上 torch 走 CPU 版（见 pyproject 的 tool.uv.sources）
FROM python:3.11-slim
WORKDIR /app
ENV TZ=Asia/Shanghai PYTHONUNBUFFERED=1 UV_PROJECT_ENVIRONMENT=/usr/local
COPY --from=ghcr.io/astral-sh/uv:0.11 /uv /usr/local/bin/uv
COPY pyproject.toml uv.lock ./
RUN uv sync --frozen --no-dev --no-install-project
COPY app ./app
EXPOSE 8090
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8090"]
