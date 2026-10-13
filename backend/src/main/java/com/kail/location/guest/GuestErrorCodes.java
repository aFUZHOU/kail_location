package com.kail.location.guest;

/**
 * 游客模式业务错误码。
 *
 * <p>客户端 RuoYiClient 对 1401 做了专门映射
 * （抛出 GuestQuotaExhaustedException，与网络异常区分），
 * 修改此处时必须同步修改客户端。</p>
 */
public final class GuestErrorCodes {

    /** 游客免费次数已用完。 */
    public static final int GUEST_QUOTA_EXHAUSTED = 1401;

    /** 同一设备指纹创建的游客记录数超限（风控，默认未启用）。 */
    public static final int DEVICE_GUEST_LIMIT_EXCEEDED = 1402;

    private GuestErrorCodes() {
    }
}
