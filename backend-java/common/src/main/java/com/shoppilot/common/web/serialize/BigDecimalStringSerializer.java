package com.shoppilot.common.web.serialize;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import java.io.IOException;
import java.math.BigDecimal;

/**
 * 数量 / 单价 / 金额类字段的 JSON 序列化器：一律以<b>字符串</b>输出（契约 {@code API_CONTRACT §9.4}）。
 *
 * <p><b>为什么是字符串</b>：JSON number 在多数前端运行时是 IEEE-754 double，会丢失 DECIMAL 精度，
 * 且 {@code DECIMAL(18,3)} / {@code DECIMAL(18,4)} 的<b>标度（scale）</b>无法用 number 表达
 * （{@code 50.000} 与 {@code 50.0} 数值相等但语义不同）。故本项目红线：数量/单价/金额一律
 * {@code BigDecimal → toPlainString()}，并<b>保留原始标度</b>（{@code 50.000} 输出 {@code "50.000"}，
 * {@code 35.5000} 输出 {@code "35.5000"}）。
 *
 * <p>用 {@link BigDecimal#toPlainString()}（而非 {@code toString()}）以杜绝科学计数法（{@code 1E+2}）。
 * {@code null} 输出 JSON {@code null}（"未配置"语义）。
 */
public class BigDecimalStringSerializer extends JsonSerializer<BigDecimal> {

    @Override
    public void serialize(BigDecimal value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
        if (value == null) {
            gen.writeNull();
            return;
        }
        gen.writeString(value.toPlainString());
    }
}
