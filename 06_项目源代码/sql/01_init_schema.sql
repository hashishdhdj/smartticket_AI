-- =============================================================
-- 项目：AI驱动的智能工单服务系统
-- 阶段：阶段1 最小骨架
-- 文件：01_init_schema.sql
-- 作用：初始化 ticket 与 ticket_log 两张表
-- 数据库：MySQL 8.0
-- 字符集：utf8mb4 / utf8mb4_unicode_ci
-- =============================================================

USE smartticket;

-- -------------------------------------------------------------
-- 表1：ticket 工单主表
-- -------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `ticket` (
    `id`           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `ticket_no`    VARCHAR(32)     NOT NULL                COMMENT '工单编号，业务唯一',
    `title`        VARCHAR(200)    NOT NULL                COMMENT '工单标题',
    `description`  TEXT             NULL                    COMMENT '工单描述',
    `category`     VARCHAR(50)      NULL                    COMMENT '工单分类',
    `priority`     VARCHAR(20)     NOT NULL DEFAULT 'MEDIUM' COMMENT '优先级：LOW/MEDIUM/HIGH/URGENT',
    `status`       VARCHAR(20)     NOT NULL DEFAULT 'PENDING_ASSIGN' COMMENT '状态：PENDING_ASSIGN/PROCESSING/PENDING_FEEDBACK/RESOLVED/CLOSED/SUSPENDED',
    `creator_id`   BIGINT UNSIGNED NOT NULL                COMMENT '创建人ID',
    `assignee_id`  BIGINT UNSIGNED  NULL                    COMMENT '处理人ID，未指派时为NULL',
    `tags`         VARCHAR(255)     NULL                    COMMENT '标签，逗号分隔',
    `created_at`   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at`   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`      TINYINT         NOT NULL DEFAULT 0       COMMENT '逻辑删除：0未删除，1已删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_ticket_no` (`ticket_no`),
    KEY `idx_status` (`status`),
    KEY `idx_creator_id` (`creator_id`),
    KEY `idx_assignee_id` (`assignee_id`),
    KEY `idx_created_at` (`created_at`),
    KEY `idx_status_priority` (`status`, `priority`)
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_unicode_ci
  COMMENT='工单主表';

-- -------------------------------------------------------------
-- 表2：ticket_log 工单操作日志表
-- -------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `ticket_log` (
    `id`           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `ticket_id`    BIGINT UNSIGNED NOT NULL                COMMENT '工单ID',
    `operator_id`  BIGINT UNSIGNED NOT NULL                COMMENT '操作人ID',
    `action`       VARCHAR(30)     NOT NULL                COMMENT '操作类型：CREATE/ASSIGN/UPDATE/CLOSE/STATUS_CHANGE/COMMENT',
    `from_status`  VARCHAR(20)      NULL                    COMMENT '变更前状态',
    `to_status`    VARCHAR(20)      NULL                    COMMENT '变更后状态',
    `remark`       VARCHAR(500)     NULL                    COMMENT '备注',
    `created_at`   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    KEY `idx_ticket_id` (`ticket_id`),
    KEY `idx_created_at` (`created_at`),
    KEY `idx_operator_id` (`operator_id`)
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_unicode_ci
  COMMENT='工单操作日志表';