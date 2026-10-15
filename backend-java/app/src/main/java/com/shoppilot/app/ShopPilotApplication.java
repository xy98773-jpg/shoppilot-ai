package com.shoppilot.app;

import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * ShopPilot AI 应用启动类（组合根）。
 *
 * <p>本模块是 DAG 顶点，仅负责装配，不含任何 Controller / Service / Mapper / 业务实体。
 * 组件扫描根包为 {@code com.shoppilot}，覆盖全部业务模块；业务类的实际逻辑在 M2 起落地。
 *
 * <p><b>Mapper 扫描（M2-T02 起）</b>：MyBatis-Plus 的自动装配只扫描「启动类所在包」
 * （{@code com.shoppilot.app}），无法发现各业务模块 {@code infrastructure.persistence.mapper}
 * 下的 Mapper。故在组合根显式声明 {@code @MapperScan}：扫描 {@code com.shoppilot} 下
 * <b>所有带 {@code @Mapper} 注解的接口</b>（用 annotationClass 收窄，避免误把普通接口当 Mapper）。
 */
@SpringBootApplication(scanBasePackages = "com.shoppilot")
@MapperScan(basePackages = "com.shoppilot", annotationClass = Mapper.class)
public class ShopPilotApplication {

    public static void main(String[] args) {
        SpringApplication.run(ShopPilotApplication.class, args);
    }
}
