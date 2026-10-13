package com.kail.location.guest;

/**
 * 游客配额服务接口。
 */
public interface GuestSimulationService {

    /**
     * 查询游客配额（只读，不扣次数）。游客首次访问时自动创建记录。
     */
    GuestQuotaVO check(String guestId, String deviceId);

    /**
     * 扣减一次游客模拟机会（原子扣减，越界抛业务异常 1401）。
     */
    GuestQuotaVO consume(String guestId, String deviceId);

    /**
     * 离线对账：补记客户端离线期间累计的 delta 次消耗（服务端截断到上限）。
     */
    GuestQuotaVO sync(String guestId, String deviceId, int delta);
}
