package com.shoppilot.catalog.application.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.shoppilot.catalog.infrastructure.persistence.entity.ProductEntity;
import com.shoppilot.common.web.serialize.UtcDateTimeSerializer;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 商品响应体——字段严格对齐契约 {@code API_CONTRACT §11.2} 的商品成功响应。
 *
 * <p><b>注意：不含 {@code team_id}</b>（契约该响应未输出 team_id；与 SKU 响应不同）。含 {@code spec_summary}
 * （展示摘要），不含 {@code spec_json}（商品无该列）、不含 {@code remark}。
 */
@Data
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class ProductView {

    private Long id;
    private String productCode;
    private String name;
    private String category;
    private String brand;
    private String unit;
    private String status;
    private Long defaultSupplierId;
    private String specSummary;

    @JsonSerialize(using = UtcDateTimeSerializer.class)
    private LocalDateTime createdAt;

    public static ProductView of(ProductEntity e) {
        ProductView v = new ProductView();
        v.setId(e.getId());
        v.setProductCode(e.getProductCode());
        v.setName(e.getName());
        v.setCategory(e.getCategory());
        v.setBrand(e.getBrand());
        v.setUnit(e.getUnit());
        v.setStatus(e.getStatus());
        v.setDefaultSupplierId(e.getDefaultSupplierId());
        v.setSpecSummary(e.getSpecSummary());
        v.setCreatedAt(e.getCreatedAt());
        return v;
    }
}
