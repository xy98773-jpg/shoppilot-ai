package com.shoppilot.catalog.application.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.shoppilot.catalog.infrastructure.persistence.entity.SupplierEntity;
import com.shoppilot.common.web.serialize.BigDecimalStringSerializer;
import com.shoppilot.common.web.serialize.UtcDateTimeSerializer;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

/** 供应商响应体（字段对齐 {@code DATABASE_DESIGN §5.5}）。数量类以字符串输出（{@code API_CONTRACT §9.4}）。 */
@Data
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class SupplierView {

    private Long id;
    private Long teamId;
    private String code;
    private String name;
    private String status;
    private String contactName;
    private String contactPhone;
    private String email;
    private String address;
    private Integer leadTimeDays;

    @JsonSerialize(using = BigDecimalStringSerializer.class)
    private BigDecimal moq;

    @JsonSerialize(using = BigDecimalStringSerializer.class)
    private BigDecimal orderMultiple;

    private String paymentTerms;
    private String remark;

    @JsonSerialize(using = UtcDateTimeSerializer.class)
    private LocalDateTime createdAt;

    public static SupplierView of(SupplierEntity e) {
        SupplierView v = new SupplierView();
        v.setId(e.getId());
        v.setTeamId(e.getTeamId());
        v.setCode(e.getCode());
        v.setName(e.getName());
        v.setStatus(e.getStatus());
        v.setContactName(e.getContactName());
        v.setContactPhone(e.getContactPhone());
        v.setEmail(e.getEmail());
        v.setAddress(e.getAddress());
        v.setLeadTimeDays(e.getLeadTimeDays());
        v.setMoq(e.getMoq());
        v.setOrderMultiple(e.getOrderMultiple());
        v.setPaymentTerms(e.getPaymentTerms());
        v.setRemark(e.getRemark());
        v.setCreatedAt(e.getCreatedAt());
        return v;
    }
}
