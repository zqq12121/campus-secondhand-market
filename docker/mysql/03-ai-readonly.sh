#!/bin/sh
# MySQL 首次初始化时执行（docker-entrypoint-initdb.d）：为 ai-service 建只读账号，
# 只能读商品向量索引需要的三张表。未设置 AI_MYSQL_USER 时跳过。
set -e
if [ -z "${AI_MYSQL_USER:-}" ] || [ -z "${AI_MYSQL_PASSWORD:-}" ]; then
  echo "[ai-readonly] 未设置 AI_MYSQL_USER / AI_MYSQL_PASSWORD，跳过"
  exit 0
fi
mysql -uroot -p"$MYSQL_ROOT_PASSWORD" <<SQL
CREATE USER IF NOT EXISTS '${AI_MYSQL_USER}'@'%' IDENTIFIED BY '${AI_MYSQL_PASSWORD}';
GRANT SELECT ON campus_trade.goods TO '${AI_MYSQL_USER}'@'%';
GRANT SELECT ON campus_trade.category TO '${AI_MYSQL_USER}'@'%';
GRANT SELECT ON campus_trade.campus TO '${AI_MYSQL_USER}'@'%';
SQL
echo "[ai-readonly] 已创建只读账号 ${AI_MYSQL_USER}"
