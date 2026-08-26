import { create } from 'zustand'
import type { EtlSourceResponse } from '@/api/etl'
import { listSources, deleteSource } from '@/api/etl'

/**
 * [역할] 지식베이스(인덱싱된 문서) 목록 전역 상태
 *
 * [설계 결정사항]
 * - 스토어로 끌어올린 이유: 목록을 사이드바와 인덱싱 모달 두 곳에서 함께 보여준다.
 *   각자 로컬 state로 들고 있으면 모달에서 업로드·삭제한 결과가 사이드바에 반영되지 않는다.
 * - refresh()를 async로 노출: 업로드 완료(SSE done)·삭제 직후 호출해 즉시 동기화
 * - 삭제는 스토어에서 API까지 처리 — 호출부가 "삭제 후 목록 갱신"을 매번 잊지 않도록
 */
interface KnowledgeState {
  sources: EtlSourceResponse[]
  loading: boolean
  deletingSource: string | null   // 삭제 진행 중인 source (버튼별 개별 로딩 표시용)
  refresh: () => Promise<void>
  remove: (source: string) => Promise<void>
}

export const useKnowledgeStore = create<KnowledgeState>((set, get) => ({
  sources: [],
  loading: false,
  deletingSource: null,

  refresh: async () => {
    set({ loading: true })
    try {
      const res = await listSources()
      set({ sources: res.data.data ?? [] })
    } catch {
      // 조회 실패 시 빈 목록 유지 — 채팅 기능에는 영향 없음
      set({ sources: [] })
    } finally {
      set({ loading: false })
    }
  },

  remove: async (source: string) => {
    set({ deletingSource: source })
    try {
      await deleteSource(source)
      await get().refresh()
    } finally {
      set({ deletingSource: null })
    }
  },
}))
