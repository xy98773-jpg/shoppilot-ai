package com.shoppilot.common.tenant;

import com.shoppilot.common.context.TeamContext;
import com.shoppilot.common.error.ErrorCodes;
import com.shoppilot.common.web.BizException;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 团队隔离校验器单测（M2-Foundation / F-B2）。
 *
 * <p>覆盖 {@code DESIGN_BASIS §6} 六条硬规则在代码侧的可断言部分，以及
 * {@code USER_ROLES §4.1} 判定顺序对应的错误码语义：
 * 跨团队 → {@code TEAM-2001}(403)、团队停用 → {@code TEAM-2002}、成员/角色 → {@code TEAM-2003}、
 * 查不到 → {@code COM-0003}(404)。
 */
class TeamScopeValidatorTest {

    @AfterEach
    void tearDown() {
        TeamContext.clear();
    }

    @Test
    @DisplayName("规则 3：上下文缺失 → AUTH-1001；只认服务端上下文，忽略客户端传入 team_id")
    void contextRules() {
        // 缺失
        BizException missing = assertThrows(BizException.class, TeamScopeValidator::requireContext);
        assertEquals(ErrorCodes.AUTH_1001, missing.getErrorCode());

        // 存在：客户端传入不一致的值应被忽略（不报错），返回服务端上下文 teamId
        TeamContext.set(TeamContext.Snapshot.of(7L, List.of("OPERATOR"), 100L, "op"));
        assertEquals(7L, TeamScopeValidator.resolveTeamId(999L), "必须忽略客户端传入的 team_id");
        assertEquals(7L, TeamScopeValidator.resolveTeamId(null));
        assertSame(TeamContext.get(), TeamScopeValidator.requireContext());
    }

    @Test
    @DisplayName("判定顺序第 4 步：路径 {teamId} 与 JWT 不符 → TEAM-2001（403）")
    void pathTeamMismatch() {
        TeamContext.set(TeamContext.Snapshot.of(7L, List.of("OWNER"), 100L, "owner"));

        TeamScopeValidator.assertPathTeam(7L);       // 一致：通过
        TeamScopeValidator.assertPathTeam(null);     // 无路径参数：通过

        BizException ex = assertThrows(BizException.class, () -> TeamScopeValidator.assertPathTeam(8L));
        assertEquals(ErrorCodes.TEAM_2001, ex.getErrorCode());
    }

    @Test
    @DisplayName("规则 4 / §5.6：资源 team_id 不符或跨表关联不同团队 → TEAM-2001（403）")
    void resourceTeamMismatch() {
        TeamContext.set(TeamContext.Snapshot.of(7L, List.of("OWNER"), 100L, "owner"));

        TeamScopeValidator.assertResourceTeam(7L);
        TeamScopeValidator.assertResourceTeam(null); // 资源无团队维度（如 users）：不判

        assertEquals(ErrorCodes.TEAM_2001,
                assertThrows(BizException.class, () -> TeamScopeValidator.assertResourceTeam(8L)).getErrorCode());

        TeamScopeValidator.assertSameTeam(7L, 7L);
        assertEquals(ErrorCodes.TEAM_2001,
                assertThrows(BizException.class, () -> TeamScopeValidator.assertSameTeam(7L, 8L)).getErrorCode());
        assertEquals(ErrorCodes.TEAM_2001,
                assertThrows(BizException.class, () -> TeamScopeValidator.assertSameTeam(7L, null)).getErrorCode());
    }

    @Test
    @DisplayName("判定顺序第 2/5/6 步：用户/团队/成员状态异常 → AUTH-1003 / TEAM-2002 / TEAM-2003")
    void statusRules() {
        TeamScopeValidator.assertUserActive("ACTIVE");
        assertEquals(ErrorCodes.AUTH_1003,
                assertThrows(BizException.class, () -> TeamScopeValidator.assertUserActive("DISABLED")).getErrorCode());

        TeamScopeValidator.assertTeamActive("ACTIVE");
        assertEquals(ErrorCodes.TEAM_2002,
                assertThrows(BizException.class, () -> TeamScopeValidator.assertTeamActive("DISABLED")).getErrorCode());

        TeamScopeValidator.assertMembershipActive("ACTIVE");
        assertEquals(ErrorCodes.TEAM_2003,
                assertThrows(BizException.class,
                        () -> TeamScopeValidator.assertMembershipActive("REMOVED")).getErrorCode());
    }

    @Test
    @DisplayName("判定顺序第 7 步：角色按名称匹配（非列位置）→ TEAM-2003")
    void roleRules() {
        // OPERATOR 可审批？不允许（USER_ROLES §2.2 第 8 行：审批仅 OWNER/ADMIN/APPROVER）
        TeamContext.set(TeamContext.Snapshot.of(7L, List.of("OPERATOR"), 100L, "op"));
        assertEquals(ErrorCodes.TEAM_2003,
                assertThrows(BizException.class,
                        () -> TeamScopeValidator.assertAnyRole(List.of("OWNER", "ADMIN", "APPROVER"))).getErrorCode());

        // APPROVER 可审批
        TeamContext.set(TeamContext.Snapshot.of(7L, List.of("APPROVER"), 100L, "ap"));
        TeamScopeValidator.assertAnyRole(List.of("OWNER", "ADMIN", "APPROVER"));

        // 多角色：命中其一即通过
        TeamContext.set(TeamContext.Snapshot.of(7L, List.of("VIEWER", "ADMIN"), 100L, "ad"));
        TeamScopeValidator.assertAnyRole(List.of("ADMIN"));
    }

    @Test
    @DisplayName("防枚举：按 team_id 过滤后查不到 → COM-0003（404），不泄露存在性")
    void notFoundRule() {
        assertEquals("x", TeamScopeValidator.orNotFound("x"));
        BizException ex = assertThrows(BizException.class, () -> TeamScopeValidator.orNotFound(null));
        assertEquals(ErrorCodes.COM_0003, ex.getErrorCode());

        BizException domain = assertThrows(BizException.class,
                () -> TeamScopeValidator.orNotFound(null, ErrorCodes.CAT_3001));
        assertEquals(ErrorCodes.CAT_3001, domain.getErrorCode());

        assertNull(TeamContext.get());
    }

    @Test
    @DisplayName("表清单：users/teams/refresh_tokens 为 §6 规则 1 例外表，team_members 等含 team_id")
    void tenantTableCatalog() {
        assertTrue(!TenantTables.hasTeamIdColumn("users"));
        assertTrue(!TenantTables.hasTeamIdColumn("teams"));
        // R15 闭环：refresh_tokens 按 user_id 组织、无 team_id；漏登记会导致登出静默失效
        assertFalse(TenantTables.hasTeamIdColumn("refresh_tokens"));
        assertTrue(!TenantTables.hasTeamIdColumn("`users`"));
        assertTrue(!TenantTables.hasTeamIdColumn("shoppilot.users"));
        assertTrue(!TenantTables.hasTeamIdColumn("flyway_schema_history"));
        assertTrue(TenantTables.hasTeamIdColumn("team_members"));
        assertTrue(TenantTables.hasTeamIdColumn("inventory_transactions"));
    }
}
