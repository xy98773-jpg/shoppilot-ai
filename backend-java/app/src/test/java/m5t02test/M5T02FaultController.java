package m5t02test;

import com.shoppilot.replenishment.application.ReplenishmentGenerationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * M5-T02 测试专用端点：把 {@code generateFromRun} 的异常经真实安全链 + 全局异常处理器
 * 暴露为 HTTP 响应（用于 V5 取证）。
 *
 * <p>★ 刻意放在 {@code com.shoppilot} 之外：应用主类以 {@code com.shoppilot} 为组件扫描根，
 * 放在其下会被<b>所有</b>测试上下文扫到；这里由 {@code @Import} 显式导入。
 *
 * <p>M5-T02 <b>不提供</b>任何生产 API（G5），本类只存在于测试源码。
 */
@RestController
public class M5T02FaultController {

    private final ReplenishmentGenerationService generationService;

    public M5T02FaultController(ReplenishmentGenerationService generationService) {
        this.generationService = generationService;
    }

    /** 触发一次生成；成功返回结果，前置校验失败由全局处理器返回对应状态码 + code。 */
    @GetMapping("/api/v1/test/m5t02/generate")
    public Object generate(@RequestParam("run_id") Long runId) {
        return generationService.generateFromRun(runId);
    }
}
