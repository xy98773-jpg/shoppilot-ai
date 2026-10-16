package com.shoppilot.catalog.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shoppilot.catalog.infrastructure.persistence.entity.SkuEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * {@code skus} 表 Mapper。
 *
 * <p>普通 CRUD 走 {@link BaseMapper}，租户线自动追加 {@code AND team_id = <当前团队>}。
 */
@Mapper
public interface SkuMapper extends BaseMapper<SkuEntity> {

    /**
     * 按主键取 {@code team_id}，<b>绕过租户线</b>——仅用于跨团队 403 与不存在 404 的区分探测。
     *
     * @param id 主键
     * @return 该行 {@code team_id}；行不存在返回 {@code null}
     */
    @InterceptorIgnore(tenantLine = "true")
    @Select("SELECT team_id FROM skus WHERE id = #{id}")
    Long selectTeamIdById(@Param("id") Long id);
}
