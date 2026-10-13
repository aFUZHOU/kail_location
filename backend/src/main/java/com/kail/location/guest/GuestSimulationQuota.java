package com.kail.location.guest;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 游客模拟配额实体，对应表 kail_guest_simulation_quota。
 *
 * <p>guest_id 由客户端首次启动时生成 UUID 并经 guest-id 请求头上报；
 * total_limit 为 -1 时表示服务端开启了无限次补偿（不扣减）。</p>
 *
 * <p>注意：建记录走 Mapper 原生 @Insert（不含 first_time 列，由 DDL 默认值填充），
 * 不使用 MyBatis-Plus 的 insert()，因此无需自动填充注解。</p>
 */
@Data
@TableName("kail_guest_simulation_quota")
public class GuestSimulationQuota {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 游客标识（客户端 UUID）。 */
    private String guestId;

    /** 设备指纹（ANDROID_ID）。 */
    private String deviceId;

    /** 已用模拟次数。 */
    private Integer usedCount;

    /** 免费总上限；-1 表示无限。 */
    private Integer totalLimit;

    private LocalDateTime firstTime;

    private LocalDateTime lastUseTime;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
