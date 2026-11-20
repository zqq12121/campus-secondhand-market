SET NAMES utf8mb4;
-- 基础数据：学校、校区、分类、角色
-- 管理员账号不在此写入（避免硬编码密码），由应用启动时读取环境变量创建
USE campus_trade;

INSERT INTO school (id, name, email_suffix) VALUES (1, 'WU', '@wu.edu.cn');

INSERT INTO campus (id, school_id, name, sort) VALUES
  (1, 1, '贝克兰德', 1),
  (2, 1, '廷根', 2);

INSERT INTO category (id, parent_id, name, sort) VALUES
  (1, 0, '教材书籍', 1),
  (2, 0, '数码电子', 2),
  (3, 0, '生活用品', 3),
  (4, 0, '服饰鞋包', 4),
  (5, 0, '运动出行', 5),
  (6, 0, '其他', 6),
  (101, 1, '专业教材', 1),
  (102, 1, '考研考证', 2),
  (103, 1, '课外读物', 3),
  (201, 2, '手机平板', 1),
  (202, 2, '电脑配件', 2),
  (203, 2, '耳机音箱', 3),
  (301, 3, '宿舍电器', 1),
  (302, 3, '日用杂物', 2),
  (401, 4, '衣服', 1),
  (402, 4, '鞋包', 2),
  (501, 5, '自行车电动车', 1),
  (502, 5, '运动器材', 2),
  (601, 6, '其他闲置', 1);

INSERT INTO admin_role (id, code, name) VALUES
  (1, 'SUPER_ADMIN', '超级管理员'),
  (2, 'ADMIN', '普通管理员');
