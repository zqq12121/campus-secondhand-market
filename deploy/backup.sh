#!/usr/bin/env bash
# 备份 MySQL 全库与用户上传的图片，保留最近 BACKUP_KEEP_DAYS 天（默认 14）。
# 用法：./deploy/backup.sh [备份目录，默认 ./backups]
# 建议加入 crontab，每天凌晨执行：
#   0 3 * * * cd /opt/campus-trade && ./deploy/backup.sh >> backups/backup.log 2>&1
# 备份目录请再同步到另一台机器或对象存储，单机备份防不了磁盘损坏。
set -euo pipefail

cd "$(dirname "$0")/.."
DEST="${1:-./backups}"
KEEP="${BACKUP_KEEP_DAYS:-14}"
TS="$(date +%Y%m%d-%H%M%S)"
mkdir -p "$DEST"

echo "[$(date '+%F %T')] 开始备份 -> $DEST"

# --single-transaction：InnoDB 一致性快照，不锁表
docker exec campus-trade-mysql sh -c \
  'exec mysqldump -uroot -p"$MYSQL_ROOT_PASSWORD" --single-transaction --routines --events --databases campus_trade' \
  | gzip > "$DEST/mysql-$TS.sql.gz"

docker run --rm -v campus-trade_uploads:/data:ro -v "$(cd "$DEST" && pwd)":/backup alpine \
  tar czf "/backup/uploads-$TS.tar.gz" -C /data .

find "$DEST" -maxdepth 1 -name 'mysql-*.sql.gz' -mtime +"$KEEP" -delete
find "$DEST" -maxdepth 1 -name 'uploads-*.tar.gz' -mtime +"$KEEP" -delete

echo "[$(date '+%F %T')] 备份完成：mysql-$TS.sql.gz uploads-$TS.tar.gz"
