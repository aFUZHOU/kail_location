package com.kail.location.guest;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import java.util.Map;

/**
 * 游客模拟配额接口（对应 Android 端 RuoYiClient 的 guest 系列方法）。
 *
 * <p>挂载路径与会员接口同级：{baseUrl}/guest/simulation/*，
 * 无登录 token，通过 guest-id / device-id 请求头标识身份，
 * 需在安全配置中放行 /guest/simulation/**（见 SQL 脚本注释）。</p>
 *
 * <p>响应统一为 RuoYi 约定：{"code":0,"msg":"","data":{used,limit,remaining}}；
 * 次数用尽返回 code=1401（客户端据此区分"用尽"与"网络异常"）。</p>
 */
@RestController
@RequestMapping("/guest/simulation")
public class GuestSimulationController {

    @Resource
    private GuestSimulationService guestSimulationService;

    /**
     * 查询游客配额（只读）。未注册过的 guestId 自动创建记录并返回全量配额。
     */
    @GetMapping("/check")
    public CommonResult<GuestQuotaVO> check(
            @RequestHeader(value = "guest-id", required = false) String guestId,
            @RequestHeader(value = "device-id", required = false) String deviceId) {
        requireGuestId(guestId);
        return CommonResult.success(guestSimulationService.check(guestId, defaultDevice(deviceId)));
    }

    /**
     * 扣减一次游客模拟机会。次数用尽返回 1401。
     */
    @PostMapping("/use")
    public CommonResult<GuestQuotaVO> use(
            @RequestHeader(value = "guest-id", required = false) String guestId,
            @RequestHeader(value = "device-id", required = false) String deviceId) {
        requireGuestId(guestId);
        return CommonResult.success(guestSimulationService.consume(guestId, defaultDevice(deviceId)));
    }

    /**
     * 离线对账：补记客户端离线期间累计的消耗。
     * 请求体：{"delta": 3}；服务端原子累加并截断到上限。
     */
    @PostMapping("/sync")
    public CommonResult<GuestQuotaVO> sync(
            @RequestHeader(value = "guest-id", required = false) String guestId,
            @RequestHeader(value = "device-id", required = false) String deviceId,
            @RequestBody Map<String, Object> body) {
        requireGuestId(guestId);
        int delta = parseInt(body == null ? null : body.get("delta"));
        return CommonResult.success(guestSimulationService.sync(guestId, defaultDevice(deviceId), delta));
    }

    private static void requireGuestId(String guestId) {
        if (guestId == null || guestId.trim().isEmpty()) {
            // 400 业务错误：与 1401（次数用尽）区分，客户端对非 0/1401 码走离线兜底
            throw new ServiceException(400, "缺少游客标识（guest-id）");
        }
    }

    private static String defaultDevice(String deviceId) {
        return deviceId == null ? "" : deviceId;
    }

    private static int parseInt(Object v) {
        if (v == null) {
            return 0;
        }
        try {
            return Integer.parseInt(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
