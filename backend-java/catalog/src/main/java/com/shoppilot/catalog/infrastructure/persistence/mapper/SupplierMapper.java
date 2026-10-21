package com.shoppilot.catalog.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shoppilot.catalog.infrastructure.persistence.entity.SupplierEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * {@code suppliers} 表 Mapper。
 *
 * <p>普通 CRUD 走 {@link BaseMapper}，租户线自动追加 {@code AND team_id = <当前团队>}。
 */
@Mapper
public interface SupplierMapper extends BaseMapper<SupplierEntity> {

    /**
     * 按主键取 {@code team_id}，<b>绕过租户线</b>——仅用于跨团队 403 与不存在 404 的区分探测。
     *
     * @param id 主键
     * @return 该行 {@code team_id}；行不存在返回 {@code null}
     */
    @InterceptorIgnore(tenantLine = "true")
    @Select("SELECT team_id FROM suppliers WHERE id = #{id}")
    Long selectTeamIdById(@Param("id") Long id);

    /**
     * 按业务键 {@code code} 取 {@code team_id}，<b>绕过租户线</b>——
     * 供 {@code importdata} 模块区分 {@code CAT-3005}（异团队）与 {@code CAT-3001}（不存在）
     * （{@code IMPORT_WORKFLOW §10.1} 团队隔离行 / §10.2 PRODUCT·SKU 行）。
     *
     * <p>只返回 {@code team_id} 一列；跨团队同编码可共存，故需 {@code LIMIT 1}，
     * 且必须<b>本团队优先</b>（{@code ORDER BY id} 会取到别团队的行 ⇒ 自家行误判 {@code CAT-3005}，
     * {@code R192}，详见 {@code WarehouseMapper} 同名方法）。
     *
     * @param code   供应商编码
     * @param teamId 当前团队（用于「本团队优先」排序）
     * @return 该编码所属团队的 {@code team_id}；不存在返回 {@code null}
     */
    @InterceptorIgnore(tenantLine = "true")
    @Select("SELECT team_id FROM suppliers WHERE code = #{code} "
            + "ORDER BY (team_id = #{teamId}) DESC, id LIMIT 1")
    Long selectTeamIdByCode(@Param("code") String code, @Param("teamId") Long teamId);

    /**
     * 按业务键 {@code code} 取<b>本团队内</b>该行的主键，供 {@code upsertSupplierByCode} 解析既有供应商
     * （M3-B4 裁定 G3 方案 1）；亦用于解析 {@code default_supplier_code}/{@code supplier_code}
     * 的引用（写入路径，必须限本团队）。
     *
     * <p><b>不绕过租户线</b>（{@code IMPORT_WORKFLOW §7.3}）。
     *
     * @param code 供应商编码
     * @return 本团队内该编码的主键；不存在返回 {@code null}
     */
    @Select("SELECT id FROM suppliers WHERE code = #{code} ORDER BY id LIMIT 1")
    Long selectIdByCode(@Param("code") String code);
}
