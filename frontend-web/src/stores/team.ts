// 团队与角色状态（Pinia，体验层）。
// 真正的团队/角色权限判定在服务端，前端仅持有展示用数据。
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'

export interface TeamInfo {
  teamId: string
  teamName: string
  /** 当前用户在团队内的角色（体验层展示，非真正鉴权）。 */
  role: string
}

export const useTeamStore = defineStore('team', () => {
  const currentTeam = ref<TeamInfo | null>(null)

  const teamId = computed(() => currentTeam.value?.teamId ?? '')
  const role = computed(() => currentTeam.value?.role ?? '')

  function setTeam(info: TeamInfo): void {
    currentTeam.value = info
  }

  function clearTeam(): void {
    currentTeam.value = null
  }

  return { currentTeam, teamId, role, setTeam, clearTeam }
})
