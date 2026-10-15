package com.shoppilot.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * ShopPilot AI 应用启动类（组合根）。
 *
 * <p>本模块是 DAG 顶点，仅负责装配，不含任何 Controller / Service / Mapper / 业务实体。
 * 组件扫描根包为 {@code com.shoppilot}，覆盖全部业务模块；业务类的实际逻辑在 M2 起落地。
 */
@SpringBootApplication(scanBasePackages = "com.shoppilot")
public class ShopPilotApplication {

    public static void main(String[] args) {
        SpringApplication.run(ShopPilotApplication.class, args);
    }
}
