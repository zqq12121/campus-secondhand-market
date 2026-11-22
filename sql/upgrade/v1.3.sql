-- 已有数据库升级到 v1.3（新库直接用 schema.sql，不需要执行本文件）
-- 用法：docker exec -i campus-trade-mysql sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" campus_trade' < sql/upgrade/v1.3.sql
SET NAMES utf8mb4;

ALTER TABLE after_sale
  ADD COLUMN appeal_remark VARCHAR(500) DEFAULT NULL COMMENT '买家申请平台介入时的补充说明' AFTER seller_reply;

CREATE TABLE want_response (
  id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  want_id      BIGINT UNSIGNED NOT NULL,
  responder_id BIGINT UNSIGNED NOT NULL COMMENT '点「我有」的用户',
  created_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_want_responder (want_id, responder_id)
) COMMENT '求购响应：同一用户对同一求购只记一次';

