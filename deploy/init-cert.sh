#!/usr/bin/env bash
# 首次签发 Let's Encrypt 证书（只需执行一次，之后由 certbot 容器自动续期）。
# 前提：域名已解析到本机公网 IP，安全组放行 80/443，且 80 端口当前没被占用。
# 用法：在仓库根目录执行 ./deploy/init-cert.sh（读取 .env 中的 DOMAIN 与 CERT_EMAIL）
set -euo pipefail

cd "$(dirname "$0")/.."
set -a
# shellcheck disable=SC1091
source .env
set +a

: "${DOMAIN:?请在 .env 中设置 DOMAIN}"
: "${CERT_EMAIL:?请在 .env 中设置 CERT_EMAIL（证书到期提醒邮箱）}"

# 卷名与 docker-compose.prod.yml 一致（compose 项目名 campus-trade）
docker volume create campus-trade_letsencrypt >/dev/null

if docker run --rm -v campus-trade_letsencrypt:/etc/letsencrypt alpine \
    test -f "/etc/letsencrypt/live/${DOMAIN}/fullchain.pem"; then
  echo "证书已存在：${DOMAIN}，无需重复签发"
  exit 0
fi

echo "用 standalone 模式为 ${DOMAIN} 签发证书（临时占用 80 端口）..."
docker run --rm -p 80:80 \
  -v campus-trade_letsencrypt:/etc/letsencrypt \
  certbot/certbot certonly --standalone \
  -d "${DOMAIN}" --email "${CERT_EMAIL}" --agree-tos --no-eff-email --non-interactive

# 之后的续期由 certbot 容器以 --webroot 参数执行（命令行参数覆盖这里记录的 standalone），无需占用 80 端口

echo "证书签发完成。接着启动全部服务："
echo "  docker compose -f docker-compose.yml -f docker-compose.prod.yml --profile app up -d --build"
