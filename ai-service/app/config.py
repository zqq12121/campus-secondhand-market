"""全部配置走环境变量；本地开发时可读仓库根目录的 .env。"""
from functools import lru_cache

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=("../.env", ".env"), extra="ignore")

    # DeepSeek（OpenAI 兼容）
    deepseek_api_key: str = ""
    deepseek_base_url: str = "https://api.deepseek.com"
    deepseek_model: str = "deepseek-flash"
    # enabled / disabled；工具调用场景默认关闭思考，更快也更省
    deepseek_thinking: str = "disabled"

    # Java 后端
    backend_base_url: str = "http://localhost:8080"
    # 与 Java 的 UPLOAD_PUBLIC_BASE_URL 一致：用户图片必须是这个前缀下的地址
    upload_public_base_url: str = "http://localhost:8080/uploads"

    # Redis（与 Java 共用实例）
    redis_host: str = "localhost"
    redis_port: int = 6379
    redis_password: str = ""

    # MySQL 只读账号，仅用于商品索引同步
    mysql_host: str = "localhost"
    mysql_port: int = 3306
    mysql_db: str = "campus_trade"
    ai_mysql_user: str = ""
    ai_mysql_password: str = ""

    # RAG
    embedding_model: str = "BAAI/bge-small-zh-v1.5"
    # bge 中文模型对短查询建议加的检索指令
    embedding_query_instruction: str = "为这个句子生成表示以用于检索相关文章："
    goods_sync_interval_seconds: int = 120

    # agent
    ai_max_tool_calls: int = 8
    ai_history_max_messages: int = 20
    ai_history_keep_messages: int = 10
    ai_session_ttl_days: int = 30
    ai_rate_per_minute: int = 10
    ai_rate_per_day: int = 100

    @property
    def redis_url(self) -> str:
        auth = f":{self.redis_password}@" if self.redis_password else ""
        return f"redis://{auth}{self.redis_host}:{self.redis_port}/0"


@lru_cache
def get_settings() -> Settings:
    return Settings()
