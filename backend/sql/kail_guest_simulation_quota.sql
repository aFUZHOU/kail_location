-- =====================================================================
-- Kail Location 游客模式配额表
--
-- 与现有会员体系（member/simulation/*）并行：
--   - 会员：按 user_id 记录每日/总量免费次数 + 订阅状态；
--   - 游客：按 guest_id（客户端首次启动生成的 UUID）记录总量配额。
-- 服务端是该表唯一权威写入口，客户端本地缓存仅作离线兜底。
-- =====================================================================

CREATE TABLE IF NOT EXISTS `kail_guest_simulation_quota` (
    `id`             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `guest_id`       VARCHAR(64)  NOT NULL COMMENT '游客标识（客户端生成的 UUID）',
    `device_id`      VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '设备指纹（ANDROID_ID），用于风控/限流',
    `used_count`     INT          NOT NULL DEFAULT 0 COMMENT '已用模拟次数',
    `total_limit`    INT          NOT NULL DEFAULT 100 COMMENT '免费总上限；-1 表示无限次（服务端补偿）',
    `first_time`     DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '首次使用时间',
    `last_use_time`  DATETIME     DEFAULT NULL COMMENT '最近一次消耗时间',
    `create_time`    DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`    DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_guest_id` (`guest_id`),
    KEY `idx_device_id` (`device_id`)
) ENGINE = InnoDB COMMENT = '游客模拟配额表';

-- =====================================================================
-- 安全放行：游客接口无登录 token，需在网关/安全配置中放行。
-- yudao / RuoYi-Plus 在 application.yaml 中追加：
--
--   yudao:
--     security:
--       permit-all-urls:
--         - /guest/simulation/**
--
-- 若使用 Spring Security 原生配置，则在 WebSecurityConfiguration 中
-- 对 /guest/simulation/** 执行 permitAll()。
-- =====================================================================
