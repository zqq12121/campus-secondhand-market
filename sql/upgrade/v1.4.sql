-- 已有数据库升级到 v1.4（新库直接用 schema.sql，不需要执行本文件）
-- 用法：docker exec -i campus-trade-mysql sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" campus_trade' < sql/upgrade/v1.4.sql
SET NAMES utf8mb4;

-- 关注卖家
CREATE TABLE user_follow (
  id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  user_id     BIGINT UNSIGNED NOT NULL COMMENT '关注发起人',
  followee_id BIGINT UNSIGNED NOT NULL COMMENT '被关注的卖家',
  created_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_pair (user_id, followee_id),
  KEY idx_followee (followee_id)
) COMMENT '关注卖家：卖家有商品上架时通知粉丝';

ALTER TABLE notification
  MODIFY COLUMN type TINYINT NOT NULL COMMENT '1订单 2审核 3求购匹配 4举报结果 5信用分 6系统公告 7关注上新 8降价提醒';

-- 面交核销码
ALTER TABLE trade_order
  ADD COLUMN meet_code CHAR(6) DEFAULT NULL COMMENT '面交核销码：卖家确认时生成，仅买家可见，卖家输入后完成交易' AFTER seller_confirmed_at;

-- 信用分申诉
CREATE TABLE credit_appeal (
  id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  user_id       BIGINT UNSIGNED NOT NULL,
  credit_log_id BIGINT UNSIGNED NOT NULL COMMENT '被申诉的扣分流水，每条只能申诉一次',
  delta         INT          NOT NULL COMMENT '被扣分值（负数），通过时按其绝对值加回',
  reason        VARCHAR(500) NOT NULL,
  status        TINYINT      NOT NULL DEFAULT 0 COMMENT '0待处理 1通过 2驳回',
  handler_id    BIGINT UNSIGNED DEFAULT NULL COMMENT '处理的管理员',
  reply         VARCHAR(200) DEFAULT NULL COMMENT '处理说明',
  handled_at    DATETIME     DEFAULT NULL,
  created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_credit_log (credit_log_id),
  KEY idx_status (status, id),
  KEY idx_user (user_id, id)
) COMMENT '信用分申诉';

ALTER TABLE credit_log
  MODIFY COLUMN reason_type TINYINT NOT NULL COMMENT '1完成交易 2收到好评 3收到差评 4违约取消/爽约 5纠纷败诉 6举报成立 7管理员调整 8申诉通过';

-- 邮件通知开关
ALTER TABLE `user`
  ADD COLUMN email_notify TINYINT NOT NULL DEFAULT 0 COMMENT '1 开启邮件通知：订单、审核、举报结果、信用分通知同时发邮件' AFTER last_login_at;
