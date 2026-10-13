package com.kail.location.guest;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

/**
 * 游客配额服务实现。
 *
 * <p>并发模型：依赖 MySQL 行锁 + 条件 UPDATE 保证原子扣减，
 * 不存在"先查后扣"的超卖窗口；对账使用 LEAST 截断，天然幂等。</p>
 */
@Service
public class GuestSimulationServiceImpl implements GuestSimulationService {

    /** 新游客的默认免费总次数。 */
    private static final int DEFAULT_TOTAL_LIMIT = 100;

    /**
     * 同一设备指纹允许创建的游客记录数上限（防"清除数据反复刷配额"）。
     * 设为 -1 表示不启用设备维度限制；生产环境可按风控策略调整。
     */
    private static final int DEVICE_GUEST_LIMIT = -1;

    @Resource
    private GuestSimulationQuotaMapper quotaMapper;

    @Override
    public GuestQuotaVO check(String guestId, String deviceId) {
        GuestSimulationQuota quota = ensureRecord(guestId, deviceId);
        return GuestQuotaVO.of(nullToZero(quota.getUsedCount()), nullToDefault(quota.getTotalLimit()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public GuestQuotaVO consume(String guestId, String deviceId) {
        GuestSimulationQuota quota = ensureRecord(guestId, deviceId);
        int limit = nullToDefault(quota.getTotalLimit());

        // 无限次补偿：直接放行，不扣减
        if (limit < 0) {
            return GuestQuotaVO.of(nullToZero(quota.getUsedCount()), limit);
        }

        // 条件 UPDATE 原子扣减；受影响行数为 0 即次数已用尽（含并发抢占最后一格）
        int affected = quotaMapper.tryConsume(guestId);
        if (affected == 0) {
            throw new ServiceException(GuestErrorCodes.GUEST_QUOTA_EXHAUSTED, "游客免费次数已用完");
        }
        GuestSimulationQuota fresh = quotaMapper.selectByGuestId(guestId);
        return GuestQuotaVO.of(nullToZero(fresh.getUsedCount()), nullToDefault(fresh.getTotalLimit()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public GuestQuotaVO sync(String guestId, String deviceId, int delta) {
        if (delta <= 0) {
            // 无对账增量时按查询处理
            return check(guestId, deviceId);
        }
        GuestSimulationQuota quota = ensureRecord(guestId, deviceId);
        int limit = nullToDefault(quota.getTotalLimit());
        if (limit < 0) {
            // 无限次：无需对账
            return GuestQuotaVO.of(nullToZero(quota.getUsedCount()), limit);
        }
        // LEAST 截断到上限，重复对账不会超过 total_limit
        quotaMapper.addUsed(guestId, delta);
        GuestSimulationQuota fresh = quotaMapper.selectByGuestId(guestId);
        return GuestQuotaVO.of(nullToZero(fresh.getUsedCount()), nullToDefault(fresh.getTotalLimit()));
    }

    /**
     * 确保游客记录存在（INSERT IGNORE 依赖唯一索引，并发安全）。
     * 可选启用设备维度限流：同一 device_id 创建的游客记录数超过
     * {@link #DEVICE_GUEST_LIMIT} 时拒绝新游客（不影响已有游客）。
     */
    private GuestSimulationQuota ensureRecord(String guestId, String deviceId) {
        quotaMapper.insertIfAbsent(guestId, deviceId == null ? "" : deviceId, DEFAULT_TOTAL_LIMIT);
        if (DEVICE_GUEST_LIMIT > 0) {
            int existed = quotaMapper.countByDeviceId(deviceId == null ? "" : deviceId);
            // 刚插入的这一条也计入，因此 > 阈值即超限
            if (existed > DEVICE_GUEST_LIMIT) {
                throw new ServiceException(GuestErrorCodes.DEVICE_GUEST_LIMIT_EXCEEDED,
                        "设备游客配额已达上限，请登录后继续使用");
            }
        }
        return quotaMapper.selectByGuestId(guestId);
    }

    private static int nullToZero(Integer v) {
        return v == null ? 0 : v;
    }

    private static int nullToDefault(Integer v) {
        return v == null ? DEFAULT_TOTAL_LIMIT : v;
    }
}
