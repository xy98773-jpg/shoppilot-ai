package com.shoppilot.catalog.application.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.shoppilot.catalog.infrastructure.persistence.entity.WarehouseEntity;
import com.shoppilot.common.web.serialize.UtcDateTimeSerializer;
import java.time.LocalDateTime;
import lombok.Data;

/** 仓库响应体（列表项与详情共用一个形状；字段对齐 {@code DATABASE_DESIGN §5.4} 业务列 + created_at）。 */
@Data
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class WarehouseView {

    private Long id;
    private Long teamId;
    private String code;
    private String name;
    private String status;
    private Boolean isDefault;
    private String address;
    private String contactName;
    private String contactPhone;
    private String remark;

    @JsonSerialize(using = UtcDateTimeSerializer.class)
    private LocalDateTime createdAt;

    public static WarehouseView of(WarehouseEntity e) {
        WarehouseView v = new WarehouseView();
        v.setId(e.getId());
        v.setTeamId(e.getTeamId());
        v.setCode(e.getCode());
        v.setName(e.getName());
        v.setStatus(e.getStatus());
        v.setIsDefault(e.getIsDefault());
        v.setAddress(e.getAddress());
        v.setContactName(e.getContactName());
        v.setContactPhone(e.getContactPhone());
        v.setRemark(e.getRemark());
        v.setCreatedAt(e.getCreatedAt());
        return v;
    }
}
