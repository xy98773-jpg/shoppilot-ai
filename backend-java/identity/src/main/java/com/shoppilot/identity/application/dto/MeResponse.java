package com.shoppilot.identity.application.dto;

import java.util.Map;

/**
 * 当前登录态（{@code GET /api/v1/auth/me} 的 {@code data}）。
 *
 * <p>{@code API_CONTRACT §10.1} 对该端点只规定「返回当前用户资料 + 当前团队 + 当前角色 + 权限位」，
 * 未给出字段级示例（§11 无 {@code /me} 小节）。故此处沿用登录响应的
 * {@code user} / {@code team} / {@code role} 三段（保证前端只需一套解析逻辑），
 * 并新增 {@code permissions}：
 *
 * <p><b>权限位的定义</b>：{@code USER_ROLES.md §2.2} 权限矩阵 14 行的布尔位，key 为
 * {@code Permission#key()}（小写下划线），value 由 {@code TeamRole#grants} <b>矩阵直查</b>得出
 * （不使用继承链推导，{@code USER_ROLES §1.1}）。前端仅用于按钮显隐——
 * <b>服务端仍是唯一安全边界</b>（{@code USER_ROLES §4}）。
 *
 * @param user        当前用户摘要
 * @param team        当前团队摘要
 * @param role        当前团队内角色名
 * @param permissions 权限矩阵 14 行的布尔位（key = 小写下划线能力名）
 */
public record MeResponse(
        UserSummary user,
        TeamSummary team,
        String role,
        Map<String, Boolean> permissions) {
}
