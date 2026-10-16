// 团队与角色状态（Pinia，体验层）。
//
// ★ 口径（M2-T12 修正，源自 `API_CONTRACT §11.1`）：
//   - 直接持有 wire 形状的 `team` 对象（snake_case）：`team.team_id` / `team.team_name`
//     / `team.default_timezone`；**不再**用旧版的 `teamId` / `teamName` 归一化字段。
//   - `role` 是**单数**（wire `data.role`；旧版 team store 的 `roles[]` 口径作废）。
//
// ★ 红线：真正的团队归属与角色判定在服务端（`TEAM-2001` / `TEAM-2003`）；
//   本 store 仅供界面展示与体验层拦截，**不得**作为安全边界。
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import type { AuthTeamSummary } from '@/types/auth'

export const useTeamStore = defineStore('team', () => {
  /** 当前团队摘要（wire 形状，snake_case）。 */
  const team = ref<AuthTeamSummary | null>(null)
  /** 当前用户在团队内的角色名（单数，`USER_ROLES §2.2` 取值）。 */
  const role = ref<string>('')

  const teamId = computed(() => team.value?.team_id ?? null)
  const teamName = computed(() => team.value?.team_name ?? '')
  const timezone = computed(() => team.value?.default_timezone ?? '')

  /** 一次性写入团队与角色（登录响应 / `/auth/me` 共用）。 */
  function setSession(nextTeam: AuthTeamSummary | null, nextRole: string): void {
    team.value = nextTeam
    role.value = nextRole
  }

  /** 仅更新角色（如后续 switch-team 场景）。 */
  function setRole(nextRole: string): void {
    role.value = nextRole
  }

  /** 清空团队态（登出）。 */
  function clear(): void {
    team.value = null
    role.value = ''
  }

  return { team, role, teamId, teamName, timezone, setSession, setRole, clear }
})
