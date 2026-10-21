package com.shoppilot.catalog.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shoppilot.catalog.infrastructure.persistence.entity.WarehouseEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * {@code warehouses} 表 Mapper。
 *
 * <p>普通 CRUD 走 {@link BaseMapper}，租户线自动追加 {@code AND team_id = <当前团队>}。
 * {@link #selectTeamIdById(Long)} 是本模块<b>唯一</b>受控的租户线例外（见其 javadoc）。
 */
@Mapper
public interface WarehouseMapper extends BaseMapper<WarehouseEntity> {

    /**
     * 按主键取 {@code team_id}，<b>绕过租户线</b>——仅用于「资源真实存在但属他人团队」的归属探测。
     *
     * <h2>为何必须绕过租户线</h2>
     * 常规查询（{@code selectById}）被租户线约束后，跨团队资源会「查不到」，与「不存在」表现一致，
     * 无法区分 {@code 403 TEAM-2001}（真实存在、异团队）与 {@code 404 CAT-3003}（不存在）。
     * 本方法用 {@code @InterceptorIgnore(tenantLine="true")} 仅取一列 {@code team_id}，
     * <b>不返回任何业务字段</b>，把结果交给服务层做「先归属、后 404」的判定（{@code API_CONTRACT §5.6} 例外口径）。
     *
     * <p>SQL 显式携带主键参数，团队归属判定<b>只由服务端</b>完成，绝不接受客户端 team_id。
     *
     * @param id 主键
     * @return 该行 {@code team_id}；行不存在返回 {@code null}
     */
    @InterceptorIgnore(tenantLine = "true")
    @Select("SELECT team_id FROM warehouses WHERE id = #{id}")
    Long selectTeamIdById(@Param("id") Long id);

    /**
     * 按业务键 {@code code} 取 {@code team_id}，<b>绕过租户线</b>——
     * 供 {@code importdata} 模块区分 {@code CAT-3005}（异团队）与 {@code CAT-3003}（仓库不存在）
     * （{@code IMPORT_WORKFLOW §10.2}：{@code INVENTORY_SNAPSHOT} / {@code SALES_DAILY} 行）。
     *
     * <p>只返回 {@code team_id} 一列；跨团队同编码可共存，故需 {@code ORDER BY ... LIMIT 1}。
     *
     * <p>★ <b>{@code R192}：必须「本团队优先」取行，不能只按 {@code id} 取最小者</b>。
     * 编码在业务上是<b>团队内唯一</b>（{@code DATABASE_DESIGN} 唯一键均带 {@code team_id}），
     * 两个团队各有一个 {@code WH-A-001} 完全合法。旧写法
     * {@code ORDER BY id LIMIT 1} 会稳定返回<b>全局最小 id</b> 的那一行——若那是<b>别的团队</b>，
     * 本团队自己的行反而被判 {@code CAT-3005}「不属于当前团队」，导入整批被拒。
     * 故排序改为「先按是否属于本团队降序，再按 id」：本团队有 ⇒ 返回本团队（校验通过）；
     * 本团队没有 ⇒ 返回别团队（{@code CAT-3005} 成立）；都没有 ⇒ {@code null}（{@code CAT-3003}）。
     *
     * @param code   仓库编码
     * @param teamId 当前团队（用于「本团队优先」排序；{@code null} 时退化为单纯 {@code id} 升序）
     * @return 该编码所属团队的 {@code team_id}；不存在返回 {@code null}
     */
    @InterceptorIgnore(tenantLine = "true")
    @Select("SELECT team_id FROM warehouses WHERE code = #{code} "
            + "ORDER BY (team_id = #{teamId}) DESC, id LIMIT 1")
    Long selectTeamIdByCode(@Param("code") String code, @Param("teamId") Long teamId);

    /**
     * 按业务键 {@code code} 取<b>本团队内</b>该行的主键，供 {@code upsertWarehouseByCode} 解析既有仓库
     * （M3-B4 裁定 G3 方案 1）。
     *
     * <p><b>不绕过租户线</b>（{@code IMPORT_WORKFLOW §7.4}）。
     *
     * @param code 仓库编码
     * @return 本团队内该编码的主键；不存在返回 {@code null}
     */
    @Select("SELECT id FROM warehouses WHERE code = #{code} ORDER BY id LIMIT 1")
    Long selectIdByCode(@Param("code") String code);
}
