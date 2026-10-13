package com.kail.location.guest;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 游客配额 Mapper。
 *
 * <p>扣减使用带上限保护的条件 UPDATE（used_count &lt; total_limit），
 * 依赖 MySQL 行锁保证原子性：受影响行数为 0 即代表次数已用尽，
 * 避免"先查后扣"的并发超卖。</p>
 */
public interface GuestSimulationQuotaMapper extends BaseMapper<GuestSimulationQuota> {

    /**
     * 首次见到的游客插入一条记录（已存在则忽略）。
     * 依赖 uk_guest_id 唯一索引保证并发下只插入一条。
     */
    @Insert("INSERT IGNORE INTO kail_guest_simulation_quota " +
            "(guest_id, device_id, used_count, total_limit) VALUES (#{guestId}, #{deviceId}, 0, #{totalLimit})")
    int insertIfAbsent(@Param("guestId") String guestId,
                       @Param("deviceId") String deviceId,
                       @Param("totalLimit") int totalLimit);

    /** 按 guest_id 查询（唯一）。 */
    @Select("SELECT * FROM kail_guest_simulation_quota WHERE guest_id = #{guestId}")
    GuestSimulationQuota selectByGuestId(@Param("guestId") String guestId);

    /** 统计同一设备指纹下已创建的游客记录数（风控/限流可选使用）。 */
    @Select("SELECT COUNT(*) FROM kail_guest_simulation_quota WHERE device_id = #{deviceId}")
    int countByDeviceId(@Param("deviceId") String deviceId);

    /**
     * 原子扣减一次：仅当 used_count &lt; total_limit 时 +1。
     *
     * @return 1=扣减成功；0=次数已用尽（含行锁等待后条件不成立）
     */
    @Update("UPDATE kail_guest_simulation_quota SET used_count = used_count + 1, last_use_time = NOW() " +
            "WHERE guest_id = #{guestId} AND used_count < total_limit")
    int tryConsume(@Param("guestId") String guestId);

    /**
     * 离线对账：把客户端离线期间累计的 delta 次消耗补记到服务端，
     * 使用 LEAST 截断，保证 used_count 永不超过 total_limit（幂等友好：
     * 重复对账只会叠加，服务端与客户端最终一致）。
     */
    @Update("UPDATE kail_guest_simulation_quota SET used_count = LEAST(used_count + #{delta}, total_limit), " +
            "last_use_time = NOW() WHERE guest_id = #{guestId}")
    int addUsed(@Param("guestId") String guestId, @Param("delta") int delta);
}
