import { create } from 'zustand'

// [설계] Zustand persist 미들웨어 미사용, localStorage 직접 관리:
// persist는 내부적으로 JSON 직렬화를 강제 → 향후 토큰 TTL 체크 로직 추가 시 유연성 제한
// 초기값을 localStorage에서 직접 읽어 새로고침 후에도 로그인 상태 유지
interface AuthState {
  accessToken: string | null
  login: (token: string) => void
  logout: () => void
  isAuthenticated: () => boolean
}

export const useAuthStore = create<AuthState>((set, get) => ({
  accessToken: localStorage.getItem('accessToken'),

  login: (token) => {
    localStorage.setItem('accessToken', token)
    set({ accessToken: token })
  },

  logout: () => {
    localStorage.removeItem('accessToken')
    set({ accessToken: null })
  },

  // 함수로 정의: 렌더링 시점이 아닌 호출 시점 상태를 참조
  isAuthenticated: () => !!get().accessToken,
}))
