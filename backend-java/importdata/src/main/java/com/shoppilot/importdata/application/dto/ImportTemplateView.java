package com.shoppilot.importdata.application.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.shoppilot.importdata.domain.model.ImportFieldSpec;
import java.util.List;
import lombok.Data;

/**
 * 模板清单项（{@code GET /api/v1/import/templates}，{@code API_CONTRACT §10.6} F-IMP-02；
 * M3-B4 裁定 A5）。
 *
 * <p>数据源是<b>唯一模板定义处</b> {@code ImportTemplateRegistry}（{@code IMPORT_WORKFLOW §9.1}
 * 「别名表在单一模块维护」），故本端点不会出现口径漂移。
 *
 * <p>{@code 6 类 bizType} 全部返回（含尚未支持落库的 {@code INVENTORY_SNAPSHOT}/{@code SALES_DAILY}
 * ——它们的<b>模板</b>在解析/映射/校验阶段已可用，落库能力见裁定 A2）。
 */
@Data
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class ImportTemplateView {

    private String bizType;
    private String templateVersion;
    private List<Field> fields;

    public static ImportTemplateView of(String bizType, String templateVersion, List<ImportFieldSpec> specs) {
        ImportTemplateView v = new ImportTemplateView();
        v.setBizType(bizType);
        v.setTemplateVersion(templateVersion);
        v.setFields(specs.stream().map(Field::of).toList());
        return v;
    }

    /** 单个模板字段（{@code §7.1–§7.6} 表格一行的对外形态）。 */
    @Data
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public static class Field {

        private String name;
        private String label;
        private boolean required;
        private String type;
        private Integer maxLength;
        private String reference;
        private List<String> aliases;

        static Field of(ImportFieldSpec spec) {
            Field f = new Field();
            f.setName(spec.name());
            f.setLabel(spec.label());
            f.setRequired(spec.required());
            f.setType(spec.type().name());
            f.setMaxLength(spec.maxLength());
            f.setReference(spec.reference() == null ? null : spec.reference().name());
            f.setAliases(spec.aliases());
            return f;
        }
    }
}
