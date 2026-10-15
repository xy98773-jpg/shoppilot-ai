// 前端权限判断（仅体验层，纯函数，无副作用）。
// 红线：
// - 真正的权限判定在服务端，此处仅用于界面元素的体验层显隐，不得作为安全边界。
// - 不得硬编码业务规则或权限矩阵。
// - 本文件为纯函数，不依赖任何其它层；当前角色由调用方（组件/composable）传入。

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
