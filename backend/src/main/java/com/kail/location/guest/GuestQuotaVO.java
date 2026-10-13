package com.kail.location.guest;

import lombok.Data;

/**
 * 游客配额返回对象（对应客户端 RuoYiClient.GuestQuotaInfo）。
 *
 * <p>字段与客户端解析严格一致：used / limit / remaining；
 * limit 或 remaining 为 -1 表示无限次。</p>
 */
@Data
public class GuestQuotaVO {

    /** 已用次数。 */
    private Integer used;

    /** 总上限；-1 表示无限。 */
    private Integer limit;

    /** 剩余次数；-1 表示无限。 */
    private Integer remaining;

    public static GuestQuotaVO of(int used, int limit) {
        GuestQuotaVO vo = new GuestQuotaVO();
        vo.setUsed(used);
        vo.setLimit(limit);
        vo.setRemaining(limit < 0 ? -1 : Math.max(limit - used, 0));
        return vo;
    }
}
