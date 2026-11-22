SET NAMES utf8mb4;
-- =====================================================================
-- 校园二手交易平台 数据库表结构
-- MySQL 8.x / InnoDB / utf8mb4
-- 约定：
--   1. 主键 BIGINT UNSIGNED 自增；不建物理外键，关联完整性由应用层保证
--   2. 金额 DECIMAL(10,2)，单位：元
--   3. 状态字段 TINYINT，取值含义写在字段注释里，与 Java 枚举一一对应
--   4. 需要逻辑删除的表带 deleted 字段（0 未删除 / 1 已删除）
-- =====================================================================

CREATE DATABASE IF NOT EXISTS campus_trade
  DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
USE campus_trade;

-- ---------------------------------------------------------------------
-- 一、学校与用户
-- ---------------------------------------------------------------------

CREATE TABLE school (
  id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  name         VARCHAR(64)  NOT NULL COMMENT '学校名称',
  email_suffix VARCHAR(64)  NOT NULL COMMENT '校园邮箱后缀，如 @wu.edu.cn',
  created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_email_suffix (email_suffix)
) COMMENT '学校（当前单校部署，预留多校扩展）';

CREATE TABLE campus (
  id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  school_id  BIGINT UNSIGNED NOT NULL,
  name       VARCHAR(32)  NOT NULL COMMENT '校区名称',
  sort       INT          NOT NULL DEFAULT 0,
  created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_school_name (school_id, name)
) COMMENT '校区';

CREATE TABLE `user` (
  id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  school_id     BIGINT UNSIGNED NOT NULL,
  campus_id     BIGINT UNSIGNED NOT NULL COMMENT '所属校区',
  email         VARCHAR(128) NOT NULL COMMENT '校园邮箱，注册即认证',
  password_hash VARCHAR(100) NOT NULL COMMENT 'BCrypt 哈希',
  nickname      VARCHAR(16)  NOT NULL,
  avatar_url    VARCHAR(255) DEFAULT NULL,
  college       VARCHAR(64)  DEFAULT NULL COMMENT '学院',
  bio           VARCHAR(200) DEFAULT NULL COMMENT '个人简介',
  credit_score  INT          NOT NULL DEFAULT 80 COMMENT '信用分 0-100',
  balance       DECIMAL(10,2) NOT NULL DEFAULT 0.00 COMMENT '平台余额（担保交易放款，演示用不提现）',
  status        TINYINT      NOT NULL DEFAULT 0 COMMENT '0正常 1禁言 2封禁',
  ban_until     DATETIME     DEFAULT NULL COMMENT '禁言/封禁截止时间，NULL 表示永久或未处罚',
  last_login_at DATETIME     DEFAULT NULL,
  deleted       TINYINT      NOT NULL DEFAULT 0,
  created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_email (email),
  KEY idx_campus (campus_id),
  KEY idx_created (created_at)
) COMMENT '用户';

CREATE TABLE user_block (
  id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  user_id         BIGINT UNSIGNED NOT NULL COMMENT '拉黑发起人',
  blocked_user_id BIGINT UNSIGNED NOT NULL COMMENT '被拉黑的人',
  created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_pair (user_id, blocked_user_id)
) COMMENT '拉黑关系：被拉黑方不能给发起人发消息和下单';

CREATE TABLE credit_log (
  id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  user_id     BIGINT UNSIGNED NOT NULL,
  delta       INT          NOT NULL COMMENT '变动分值，正加负减',
  score_after INT          NOT NULL COMMENT '变动后信用分',
  reason_type TINYINT      NOT NULL COMMENT '1完成交易 2收到好评 3收到差评 4违约取消/爽约 5纠纷败诉 6举报成立 7管理员调整',
  ref_id      BIGINT UNSIGNED DEFAULT NULL COMMENT '关联业务ID（订单/举报等）',
  remark      VARCHAR(200) DEFAULT NULL,
  operator_id BIGINT UNSIGNED DEFAULT NULL COMMENT '管理员调整时的管理员ID',
  created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_user_time (user_id, created_at)
) COMMENT '信用分变动流水';

CREATE TABLE balance_log (
  id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  user_id       BIGINT UNSIGNED NOT NULL,
  amount        DECIMAL(10,2) NOT NULL COMMENT '变动金额，正入负出',
  balance_after DECIMAL(10,2) NOT NULL,
  type          TINYINT      NOT NULL COMMENT '1担保订单放款',
  order_id      BIGINT UNSIGNED DEFAULT NULL,
  created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_user_time (user_id, created_at),
  UNIQUE KEY uk_order_type (order_id, type) COMMENT '同一订单只放款一次'
) COMMENT '平台余额流水';

-- ---------------------------------------------------------------------
-- 二、商品与求购
-- ---------------------------------------------------------------------

CREATE TABLE category (
  id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  parent_id  BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '0 表示一级分类',
  name       VARCHAR(32)  NOT NULL,
  icon_url   VARCHAR(255) DEFAULT NULL,
  sort       INT          NOT NULL DEFAULT 0,
  enabled    TINYINT      NOT NULL DEFAULT 1 COMMENT '有商品时只能停用不能删除',
  created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_parent (parent_id, sort)
) COMMENT '商品分类（二级）';

CREATE TABLE goods (
  id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  seller_id      BIGINT UNSIGNED NOT NULL,
  school_id      BIGINT UNSIGNED NOT NULL,
  campus_id      BIGINT UNSIGNED NOT NULL COMMENT '交易校区',
  category_id    BIGINT UNSIGNED NOT NULL COMMENT '二级分类ID',
  title          VARCHAR(30)  NOT NULL,
  description    VARCHAR(1000) NOT NULL,
  cover_url      VARCHAR(255) NOT NULL COMMENT '封面图，冗余首张图片',
  price          DECIMAL(10,2) NOT NULL COMMENT '0 表示免费送',
  original_price DECIMAL(10,2) DEFAULT NULL,
  condition_level TINYINT     NOT NULL COMMENT '1全新 2几乎全新 3轻微使用 4明显使用',
  trade_mode     TINYINT      NOT NULL COMMENT '1仅面交 2仅担保 3都支持',
  location       VARCHAR(64)  DEFAULT NULL COMMENT '面交地点描述',
  stock          INT          NOT NULL DEFAULT 1 COMMENT '可售库存',
  status         TINYINT      NOT NULL DEFAULT 0 COMMENT '0草稿 1待审核 2在售 3审核驳回 4交易中 5已售出 6已下架 7强制下架',
  audit_remark   VARCHAR(200) DEFAULT NULL COMMENT '驳回/强制下架原因',
  view_count     INT          NOT NULL DEFAULT 0,
  favorite_count INT          NOT NULL DEFAULT 0,
  published_at   DATETIME     DEFAULT NULL COMMENT '最近一次上架时间，在售超60天自动下架以此计算',
  version        INT          NOT NULL DEFAULT 0 COMMENT '乐观锁，扣库存使用',
  deleted        TINYINT      NOT NULL DEFAULT 0,
  created_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_list (status, campus_id, category_id, published_at),
  KEY idx_status_price (status, price),
  KEY idx_seller_status (seller_id, status),
  FULLTEXT KEY ft_title_desc (title, description) WITH PARSER ngram
) COMMENT '商品';

CREATE TABLE goods_image (
  id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  goods_id   BIGINT UNSIGNED NOT NULL,
  url        VARCHAR(255) NOT NULL,
  sort       TINYINT      NOT NULL DEFAULT 0 COMMENT '0 为封面',
  created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_goods (goods_id, sort)
) COMMENT '商品图片（1-9张）';

CREATE TABLE favorite (
  id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  user_id    BIGINT UNSIGNED NOT NULL,
  goods_id   BIGINT UNSIGNED NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_user_goods (user_id, goods_id),
  KEY idx_goods (goods_id)
) COMMENT '收藏';

CREATE TABLE want_post (
  id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  user_id     BIGINT UNSIGNED NOT NULL,
  school_id   BIGINT UNSIGNED NOT NULL,
  campus_id   BIGINT UNSIGNED NOT NULL,
  category_id BIGINT UNSIGNED NOT NULL,
  title       VARCHAR(30)  NOT NULL,
  description VARCHAR(500) DEFAULT NULL,
  price_min   DECIMAL(10,2) DEFAULT NULL,
  price_max   DECIMAL(10,2) DEFAULT NULL,
  status      TINYINT      NOT NULL DEFAULT 0 COMMENT '0进行中 1已解决 2已过期 3被管理员关闭',
  expire_at   DATETIME     NOT NULL COMMENT '有效期 3/7/15/30 天',
  deleted     TINYINT      NOT NULL DEFAULT 0,
  created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_list (status, campus_id, category_id, created_at),
  KEY idx_user_status (user_id, status),
  KEY idx_expire (status, expire_at)
) COMMENT '求购帖';

CREATE TABLE want_response (
  id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  want_id      BIGINT UNSIGNED NOT NULL,
  responder_id BIGINT UNSIGNED NOT NULL COMMENT '点「我有」的用户',
  created_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_want_responder (want_id, responder_id)
) COMMENT '求购响应：同一用户对同一求购只记一次';

-- ---------------------------------------------------------------------
-- 三、聊天
-- ---------------------------------------------------------------------

CREATE TABLE conversation (
  id                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  user_small_id     BIGINT UNSIGNED NOT NULL COMMENT '两人中ID较小者，保证一对用户只有一个会话',
  user_big_id       BIGINT UNSIGNED NOT NULL COMMENT '两人中ID较大者',
  goods_id          BIGINT UNSIGNED DEFAULT NULL COMMENT '最近一次关联的商品，会话顶部展示',
  last_message      VARCHAR(100) DEFAULT NULL COMMENT '最后一条消息摘要',
  last_message_at   DATETIME     DEFAULT NULL,
  small_unread      INT          NOT NULL DEFAULT 0 COMMENT 'user_small 的未读数',
  big_unread        INT          NOT NULL DEFAULT 0 COMMENT 'user_big 的未读数',
  created_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_pair (user_small_id, user_big_id),
  KEY idx_small_time (user_small_id, last_message_at),
  KEY idx_big_time (user_big_id, last_message_at)
) COMMENT '会话';

CREATE TABLE message (
  id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  conversation_id BIGINT UNSIGNED NOT NULL,
  sender_id       BIGINT UNSIGNED NOT NULL COMMENT '系统消息为 0',
  type            TINYINT      NOT NULL COMMENT '1文字 2图片 3商品卡片 4系统消息',
  content         VARCHAR(1000) NOT NULL COMMENT '文字内容/图片URL/商品ID，已做敏感词替换',
  created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_conv_id (conversation_id, id)
) COMMENT '聊天消息（按 id 倒序分页拉历史）';

-- ---------------------------------------------------------------------
-- 四、订单与支付
-- ---------------------------------------------------------------------

CREATE TABLE trade_order (
  id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  order_no        VARCHAR(32)  NOT NULL COMMENT '业务订单号',
  buyer_id        BIGINT UNSIGNED NOT NULL,
  seller_id       BIGINT UNSIGNED NOT NULL,
  goods_id        BIGINT UNSIGNED NOT NULL,
  goods_title     VARCHAR(30)  NOT NULL COMMENT '下单时商品快照',
  goods_cover     VARCHAR(255) NOT NULL,
  unit_price      DECIMAL(10,2) NOT NULL,
  quantity        INT          NOT NULL DEFAULT 1,
  total_amount    DECIMAL(10,2) NOT NULL,
  trade_mode      TINYINT      NOT NULL COMMENT '1面交 2担保',
  status          TINYINT      NOT NULL COMMENT '10待卖家确认 20待面交 30待支付 40待交货 50待收货 60售后中 70已完成 80已取消 90已退款',
  cancel_reason   VARCHAR(200) DEFAULT NULL,
  cancel_by       TINYINT      DEFAULT NULL COMMENT '1买家 2卖家 3系统超时 4管理员',
  seller_confirmed_at DATETIME DEFAULT NULL COMMENT '面交：卖家确认时间',
  paid_at         DATETIME     DEFAULT NULL COMMENT '担保：支付时间',
  delivered_at    DATETIME     DEFAULT NULL COMMENT '担保：卖家交货时间',
  finished_at     DATETIME     DEFAULT NULL,
  canceled_at     DATETIME     DEFAULT NULL,
  review_deadline DATETIME     DEFAULT NULL COMMENT '互评截止时间 = 完成后15天',
  version         INT          NOT NULL DEFAULT 0 COMMENT '乐观锁，防止状态并发流转',
  created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_order_no (order_no),
  KEY idx_buyer_status (buyer_id, status, created_at),
  KEY idx_seller_status (seller_id, status, created_at),
  KEY idx_goods (goods_id),
  KEY idx_status_time (status, created_at) COMMENT '定时任务兜底扫描超时订单'
) COMMENT '交易订单';

CREATE TABLE order_log (
  id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  order_id      BIGINT UNSIGNED NOT NULL,
  from_status   TINYINT      DEFAULT NULL COMMENT '创建时为 NULL',
  to_status     TINYINT      NOT NULL,
  action        VARCHAR(32)  NOT NULL COMMENT '状态机动作，如 PAY / DELIVER / CONFIRM_RECEIPT',
  operator_type TINYINT      NOT NULL COMMENT '1买家 2卖家 3系统 4管理员',
  operator_id   BIGINT UNSIGNED DEFAULT NULL,
  remark        VARCHAR(200) DEFAULT NULL,
  created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_order (order_id, id)
) COMMENT '订单状态流转日志';

CREATE TABLE payment (
  id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  order_id        BIGINT UNSIGNED NOT NULL,
  out_trade_no    VARCHAR(64)  NOT NULL COMMENT '商户支付单号，传给支付宝',
  alipay_trade_no VARCHAR(64)  DEFAULT NULL COMMENT '支付宝交易号',
  amount          DECIMAL(10,2) NOT NULL,
  status          TINYINT      NOT NULL DEFAULT 0 COMMENT '0待支付 1支付成功 2已关闭 3已全额退款',
  paid_at         DATETIME     DEFAULT NULL,
  notify_payload  TEXT         DEFAULT NULL COMMENT '最近一次异步通知原文，排查用',
  created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_out_trade_no (out_trade_no) COMMENT '回调幂等',
  KEY idx_order (order_id)
) COMMENT '支付单（担保交易）';

CREATE TABLE after_sale (
  id                 BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  order_id           BIGINT UNSIGNED NOT NULL,
  type               TINYINT      NOT NULL COMMENT '1交货前取消退款 2收货前申请退款',
  buyer_id           BIGINT UNSIGNED NOT NULL,
  seller_id          BIGINT UNSIGNED NOT NULL,
  reason             VARCHAR(200) NOT NULL,
  evidence_urls      JSON         DEFAULT NULL COMMENT '凭证图片URL数组',
  refund_amount      DECIMAL(10,2) NOT NULL,
  status             TINYINT      NOT NULL DEFAULT 0 COMMENT '0待卖家处理 1卖家同意 2卖家拒绝 3平台介入中 4裁决退款 5裁决放款 6买家撤销',
  seller_reply       VARCHAR(200) DEFAULT NULL,
  appeal_remark      VARCHAR(500) DEFAULT NULL COMMENT '买家申请平台介入时的补充说明',
  seller_deadline    DATETIME     NOT NULL COMMENT '卖家处理截止时间，超时自动同意',
  arbitrator_id      BIGINT UNSIGNED DEFAULT NULL COMMENT '裁决管理员',
  arbitration_reason VARCHAR(500) DEFAULT NULL,
  out_refund_no      VARCHAR(64)  DEFAULT NULL COMMENT '支付宝退款请求号，退款幂等',
  refunded_at        DATETIME     DEFAULT NULL,
  closed_at          DATETIME     DEFAULT NULL,
  created_at         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_order (order_id),
  KEY idx_status (status, created_at),
  UNIQUE KEY uk_out_refund_no (out_refund_no)
) COMMENT '售后（退款/纠纷）';

CREATE TABLE review (
  id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  order_id    BIGINT UNSIGNED NOT NULL,
  reviewer_id BIGINT UNSIGNED NOT NULL,
  reviewee_id BIGINT UNSIGNED NOT NULL,
  role        TINYINT      NOT NULL COMMENT '评价人身份：1买家评卖家 2卖家评买家',
  rating      TINYINT      NOT NULL COMMENT '1好评 2中评 3差评',
  content     VARCHAR(200) DEFAULT NULL,
  tags        JSON         DEFAULT NULL COMMENT '如 ["描述相符","准时赴约"]',
  reply       VARCHAR(200) DEFAULT NULL COMMENT '被评方追加解释，仅一次',
  is_default  TINYINT      NOT NULL DEFAULT 0 COMMENT '1 表示到期未评系统默认好评',
  visible     TINYINT      NOT NULL DEFAULT 0 COMMENT '双方都评或到期后才公开',
  created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_order_reviewer (order_id, reviewer_id),
  KEY idx_reviewee (reviewee_id, visible, created_at)
) COMMENT '交易互评';

-- ---------------------------------------------------------------------
-- 五、举报、审核、通知、运营
-- ---------------------------------------------------------------------

CREATE TABLE report (
  id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  reporter_id   BIGINT UNSIGNED NOT NULL,
  target_type   TINYINT      NOT NULL COMMENT '1商品 2用户 3求购帖',
  target_id     BIGINT UNSIGNED NOT NULL,
  reason_type   TINYINT      NOT NULL COMMENT '1禁售品 2虚假信息 3诈骗 4辱骂骚扰 5其他',
  description   VARCHAR(500) DEFAULT NULL,
  evidence_urls JSON         DEFAULT NULL,
  status        TINYINT      NOT NULL DEFAULT 0 COMMENT '0待处理 1成立 2不成立',
  result        VARCHAR(200) DEFAULT NULL COMMENT '处理结果说明',
  handler_id    BIGINT UNSIGNED DEFAULT NULL,
  handled_at    DATETIME     DEFAULT NULL,
  created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_status (status, created_at),
  KEY idx_target (target_type, target_id),
  KEY idx_reporter_target (reporter_id, target_type, target_id, created_at) COMMENT '24小时内同一对象只能举报一次'
) COMMENT '举报';

CREATE TABLE audit_record (
  id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  target_type TINYINT      NOT NULL COMMENT '1商品',
  target_id   BIGINT UNSIGNED NOT NULL,
  auditor_type TINYINT     NOT NULL COMMENT '1系统自动 2管理员',
  auditor_id  BIGINT UNSIGNED DEFAULT NULL,
  result      TINYINT      NOT NULL COMMENT '1通过 2驳回 3转人工',
  reason      VARCHAR(200) DEFAULT NULL,
  hit_words   VARCHAR(255) DEFAULT NULL COMMENT '命中的敏感词',
  created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_target (target_type, target_id, id)
) COMMENT '审核记录';

CREATE TABLE notification (
  id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  user_id    BIGINT UNSIGNED NOT NULL,
  type       TINYINT      NOT NULL COMMENT '1订单 2审核 3求购匹配 4举报结果 5信用分 6系统公告',
  title      VARCHAR(64)  NOT NULL,
  content    VARCHAR(500) NOT NULL,
  ref_type   TINYINT      DEFAULT NULL COMMENT '跳转目标：1订单 2商品 3求购帖',
  ref_id     BIGINT UNSIGNED DEFAULT NULL,
  is_read    TINYINT      NOT NULL DEFAULT 0,
  created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_user_read (user_id, is_read, id)
) COMMENT '站内通知';

CREATE TABLE sensitive_word (
  id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  word       VARCHAR(32) NOT NULL,
  created_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_word (word)
) COMMENT '敏感词库（启动时加载为 DFA，更新后刷新缓存）';

CREATE TABLE announcement (
  id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  type       TINYINT      NOT NULL COMMENT '1公告 2首页轮播图',
  title      VARCHAR(64)  NOT NULL,
  content    TEXT         DEFAULT NULL,
  image_url  VARCHAR(255) DEFAULT NULL COMMENT '轮播图图片',
  link_url   VARCHAR(255) DEFAULT NULL,
  sort       INT          NOT NULL DEFAULT 0,
  status     TINYINT      NOT NULL DEFAULT 1 COMMENT '0下线 1上线',
  created_by BIGINT UNSIGNED NOT NULL,
  created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_type_status (type, status, sort)
) COMMENT '公告与轮播图';

CREATE TABLE stat_daily (
  stat_date   DATE          NOT NULL,
  new_users   INT           NOT NULL DEFAULT 0,
  new_goods   INT           NOT NULL DEFAULT 0,
  new_orders  INT           NOT NULL DEFAULT 0,
  finished_orders INT       NOT NULL DEFAULT 0,
  gmv         DECIMAL(12,2) NOT NULL DEFAULT 0.00 COMMENT '当日完成订单金额',
  updated_at  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (stat_date)
) COMMENT '数据看板日统计（定时任务每小时汇总当天）';

-- ---------------------------------------------------------------------
-- 六、后台管理员与权限（RBAC）
-- ---------------------------------------------------------------------

CREATE TABLE admin_user (
  id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  username      VARCHAR(32)  NOT NULL,
  password_hash VARCHAR(100) NOT NULL,
  real_name     VARCHAR(32)  DEFAULT NULL,
  status        TINYINT      NOT NULL DEFAULT 1 COMMENT '0停用 1启用',
  last_login_at DATETIME     DEFAULT NULL,
  created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_username (username)
) COMMENT '管理员';

CREATE TABLE admin_role (
  id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  code       VARCHAR(32) NOT NULL COMMENT 'SUPER_ADMIN / ADMIN',
  name       VARCHAR(32) NOT NULL,
  created_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_code (code)
) COMMENT '角色';

CREATE TABLE admin_permission (
  id        BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  parent_id BIGINT UNSIGNED NOT NULL DEFAULT 0,
  code      VARCHAR(64) NOT NULL COMMENT '如 goods:audit',
  name      VARCHAR(32) NOT NULL,
  type      TINYINT     NOT NULL COMMENT '1菜单 2按钮/接口',
  sort      INT         NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_code (code)
) COMMENT '权限点';

CREATE TABLE admin_user_role (
  admin_id BIGINT UNSIGNED NOT NULL,
  role_id  BIGINT UNSIGNED NOT NULL,
  PRIMARY KEY (admin_id, role_id)
) COMMENT '管理员-角色';

CREATE TABLE admin_role_permission (
  role_id       BIGINT UNSIGNED NOT NULL,
  permission_id BIGINT UNSIGNED NOT NULL,
  PRIMARY KEY (role_id, permission_id)
) COMMENT '角色-权限';

CREATE TABLE operation_log (
  id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  admin_id    BIGINT UNSIGNED NOT NULL,
  module      VARCHAR(32)  NOT NULL COMMENT '如 商品审核 / 用户管理',
  action      VARCHAR(64)  NOT NULL,
  target_id   BIGINT UNSIGNED DEFAULT NULL,
  detail      VARCHAR(1000) DEFAULT NULL COMMENT '请求参数摘要',
  ip          VARCHAR(45)  DEFAULT NULL,
  created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_admin_time (admin_id, created_at),
  KEY idx_time (created_at)
) COMMENT '后台操作日志';
