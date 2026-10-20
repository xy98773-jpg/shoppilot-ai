package r62test;

import com.shoppilot.common.error.ErrorCodes;
import com.shoppilot.common.web.BizException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code R62} 测试专用故障端点（M4-B23）。
 *
 * <p>★ 刻意放在 {@code com.shoppilot} 之外：应用主类的 {@code @ComponentScan} 以
 * {@code com.shoppilot} 为根，若放在其下会被<b>所有</b>测试上下文扫到；
 * 这里由 {@code @Import} 显式导入，只在需要它的测试里注册。
 *
 * <p>只用于验证「异常 → HTTP 409 + COM-0015」的映射，<b>不写任何业务数据</b>。
 */
@RestController
public class R62FaultController {

    /** 主路径：业务层判 CAS 返回 0 行后抛出的业务异常。 */
    @GetMapping("/api/v1/test/r62/com0015")
    public void throwCom0015() {
        throw new BizException(ErrorCodes.COM_0015, "该数据已被他人修改，请刷新后重试");
    }

    /** 兜底路径：框架若以异常形态报告乐观锁失败（Spring 语义）。 */
    @GetMapping("/api/v1/test/r62/optimistic")
    public void throwSpringOptimisticLock() {
        throw new OptimisticLockingFailureException("simulated stale version");
    }
}
