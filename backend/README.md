# Kail Location 游客模式 - 后端实现

本目录是**游客模式**的服务端实现，与仓库中的 Android 客户端配套。
客户端代码在主仓库 `app/src/main/java/com/kail/location/`：

- `auth/GuestModeManager.kt` — 游客身份、配额校验、离线兜底与对账
- `network/RuoYiClient.kt` — `guestCheck` / `guestUse` / `guestSync`
- `auth/UsageManager.kt` — 未登录用户统一走游客门控

> 说明：RuoYi 服务端不在本仓库内，以下代码按 **RuoYi-Plus / yudao**
> （Spring Boot + MyBatis-Plus + CommonResult）风格编写，合并到现有后台工程时
> 只需替换包名/个别 import，业务逻辑无需改动。

## 文件清单

| 文件 | 用途 |
| --- | --- |
| `sql/kail_guest_simulation_quota.sql` | 配额表 DDL + 安全放行配置说明 |
| `src/main/java/com/kail/location/guest/GuestSimulationQuota.java` | 实体（表 `kail_guest_simulation_quota`） |
| `.../GuestSimulationQuotaMapper.java` | Mapper：条件 UPDATE 原子扣减 + LEAST 截断对账 |
| `.../GuestQuotaVO.java` | 返回对象（used / limit / remaining） |
| `.../GuestErrorCodes.java` | 错误码（1401 次数用尽，与客户端约定） |
| `.../GuestSimulationService.java` | 服务接口 |
| `.../GuestSimulationServiceImpl.java` | 服务实现（事务 + 行锁原子扣减） |
| `.../GuestSimulationController.java` | 接口：`/guest/simulation/check\|use\|sync` |

## 接口契约

统一请求头：`tenant-id: 1`、`guest-id: <UUID>`、`device-id: <ANDROID_ID>`。

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/guest/simulation/check` | 查询配额，首次自动建记录（默认无限次） |
| POST | `/guest/simulation/use` | 原子扣减 1 次；用尽返回 `code=1401` |
| POST | `/guest/simulation/sync` | 离线对账，body `{"delta": n}`，服务端截断到上限 |

成功响应：`{"code":0,"msg":"","data":{"used":12,"limit":-1,"remaining":-1}}`；
`limit=-1` / `remaining=-1` 表示无限次（当前默认配置：DDL 默认值与
`DEFAULT_TOTAL_LIMIT` 均为 -1）。恢复限量模式：把 DDL 默认值、
`DEFAULT_TOTAL_LIMIT` 与客户端 `GuestModeManager.GUEST_FREE_TOTAL`
同步改为正整数（如 100）即可。

## 合并步骤

1. 执行 `sql/kail_guest_simulation_quota.sql` 建表；
2. 将 `com.kail.location.guest` 包复制进后台工程（按实际包名调整）；
3. 放行匿名访问（二选一）：
   - yudao：`application.yaml` → `yudao.security.permit-all-urls` 增加 `- /guest/simulation/**`
   - Spring Security 原生：对 `/guest/simulation/**` 执行 `permitAll()`；
4. （可选）风控：`GuestSimulationServiceImpl.DEVICE_GUEST_LIMIT` 设为正整数
   可限制同一设备指纹反复"清除数据"刷配额，默认 `-1` 关闭。

## 并发与一致性设计

- **扣减**：`UPDATE ... SET used_count = used_count + 1 WHERE guest_id = ? AND used_count < total_limit`，
  受影响行数为 0 即用尽，无超卖窗口；
- **对账**：`LEAST(used_count + delta, total_limit)`，天然幂等且不超过上限；
- **建记录**：`INSERT IGNORE` + `uk_guest_id` 唯一索引，并发首访只插一条；
- **游客 ID**：客户端生成 UUID 持久化，退出登录不重置，防止"登出刷配额"。
