// 前端权限判断（仅体验层，纯函数，无副作用）。
// 红线：
// - 真正的权限判定在服务端，此处仅用于界面元素的体验层显隐，不得作为安全边界。
// - 不得硬编码业务规则或权限矩阵。
// - 本文件为纯函数，不依赖任何其它层；当前角色 / 权限位由调用方（组件）传入。

/**
 * 判断给定角色是否满足目标角色（体验层）。
 * @param currentRole 当前用户/团队角色（由调用方从 store 读取后传入）。
 * @param required 需要匹配的角色。
 */
export function hasRole(currentRole: string | undefined, required: string): boolean {
  return !!currentRole && currentRole === required
}

/**
 * 判断给定角色是否满足目标角色之一（体验层）。
 */
export function hasAnyRole(currentRole: string | undefined, required: string[]): boolean {
  return !!currentRole && required.includes(currentRole)
}

/**
 * 判断当前用户是否具备某能力位（体验层）。
 *
 * 能力位来自 `GET /auth/me` 的 `permissions`（`USER_ROLES §2.2` 矩阵 14 行的布尔位，
 * key 为**小写下划线**，如 `master_data_manage`）。
 *
 * ★ 用途**仅限按钮/入口显隐**；服务端仍是唯一安全边界（`USER_ROLES §4`）。
 * ★ 缺省（未水合 / 未下发）一律视为**无权限**，避免"未加载即放行"造成误操作展示。
 *
 * @param permissions 权限位映射（由调用方从 user store 读取后传入）。
 * @param key 能力位 key（小写下划线）。
 */
export function hasPermission(
  permissions: Record<string, boolean> | null | undefined,
  key: string,
): boolean {
  return !!permissions && permissions[key] === true
}
