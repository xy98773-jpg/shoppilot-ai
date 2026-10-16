package com.shoppilot.catalog.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shoppilot.catalog.infrastructure.persistence.entity.ProductEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * {@code products} 表 Mapper。
 *
 * <p>普通 CRUD 走 {@link BaseMapper}，租户线自动追加 {@code AND team_id = <当前团队>}。
 */
@Mapper
public interface ProductMapper extends BaseMapper<ProductEntity> {

    /**
     * 按主键取 {@code team_id}，<b>绕过租户线</b>——仅用于跨团队 403 与不存在 404 的区分探测。
     *
     * @param id 主键
     * @return 该行 {@code team_id}；行不存在返回 {@code null}
     */
    @InterceptorIgnore(tenantLine = "true")
    @Select("SELECT team_id FROM products WHERE id = #{id}")
    Long selectTeamIdById(@Param("id") Long id);

    /**
     * 按业务键 {@code product_code} 取 {@code team_id}，<b>绕过租户线</b>——
     * 供 {@code importdata} 模块做「引用对象是否属于当前团队」的归属探测（M3-T05）。
     *
     * <h2>为何必须绕过租户线</h2>
     * 导入校验按<b>编码</b>（而非 id）引用主数据。若走常规租户线查询，跨团队编码会「查不到」，
     * 与「不存在」表现一致，无法区分 {@code CAT-3005}（真实存在、异团队）与
     * {@code CAT-3001}（不存在）——而 {@code IMPORT_WORKFLOW §10.1} 团队隔离行明确要求两者分行。
     *
     * <p>只返回 {@code team_id} 一列，不泄露任何业务字段；编码重复时取 {@code id} 最小的一行
     * （库内 {@code uk_prod_team_code} 只保证团队内唯一，跨团队可重复，故需 {@code LIMIT 1}）。
     *
     * @param code 商品编码
     * @return 该编码所属团队的 {@code team_id}；编码不存在返回 {@code null}
     */
    @InterceptorIgnore(tenantLine = "true")
    @Select("SELECT team_id FROM products WHERE product_code = #{code} ORDER BY id LIMIT 1")
    Long selectTeamIdByCode(@Param("code") String code);
}
