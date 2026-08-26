import { useEffect, useState } from 'react'
import { useKnowledgeStore } from '@/stores/knowledgeStore'

/**
 * [역할] 사이드바 상시 노출용 지식베이스 패널 — 현재 참조 가능한 문서 목록 + 삭제
 *
 * [설계 결정사항]
 * - 상시 노출로 옮긴 이유: 기존에는 "문서 인덱싱" 모달의 세 번째 탭에만 있어
 *   사용자가 지금 어떤 문서를 참조할 수 있는지 채팅 중에 알 방법이 없었다.
 *   RAG가 답을 못 할 때 "문서가 없어서인지, 검색이 안 된 건지" 구분이 불가능했다.
 * - 기본 접힘: 문서가 많아지면 채팅방 목록을 밀어내므로, 헤더(개수)만 보이고
 *   펼쳐야 목록이 나오게 함. 개수만으로도 "지금 N개 참조 가능"은 항상 전달된다.
 * - 삭제는 확인 단계 없이 즉시 실행하지 않고 2단계(삭제 → 확인)로 처리 —
 *   되돌릴 수 없는 작업이고 목록이 촘촘해 오클릭 위험이 있다.
 */
export default function KnowledgePanel({ onAddClick }: { onAddClick: () => void }) {
  const { sources, loading, deletingSource, refresh, remove } = useKnowledgeStore()
  const [expanded, setExpanded] = useState(false)
  const [confirmTarget, setConfirmTarget] = useState<string | null>(null)

  // 마운트 시 1회 로드 — 이후 업로드·삭제 시 스토어가 알아서 갱신
  useEffect(() => {
    void refresh()
  }, [refresh])

  const totalChunks = sources.reduce((sum, s) => sum + s.chunkCount, 0)

  return (
    <div className="border-t border-gray-800">
      {/* 헤더 — 접기/펼치기 + 문서 수 */}
      <div className="flex items-center gap-2 px-3 py-2">
        <button
          onClick={() => setExpanded((v) => !v)}
          className="flex-1 flex items-center gap-1.5 text-left group"
          aria-expanded={expanded}
        >
          <span className={`text-gray-600 text-[10px] transition-transform ${expanded ? 'rotate-90' : ''}`}>
            ▶
          </span>
          <span className="text-xs text-gray-500 group-hover:text-gray-300 transition">
            지식베이스
          </span>
          <span className="text-xs text-violet-400/80 font-medium">
            {sources.length}
          </span>
        </button>
        <button
          onClick={onAddClick}
          className="text-gray-500 hover:text-violet-400 text-sm leading-none transition shrink-0"
          title="문서 추가 (URL 크롤링 / 파일 업로드)"
        >+</button>
      </div>

      {/* 목록 — 펼쳤을 때만 */}
      {expanded && (
        <div className="px-3 pb-2">
          {loading ? (
            <p className="text-xs text-gray-600 py-2">불러오는 중...</p>
          ) : sources.length === 0 ? (
            <p className="text-xs text-gray-600 py-2 leading-relaxed">
              인덱싱된 문서가 없습니다.<br />
              <button onClick={onAddClick} className="text-violet-400 hover:text-violet-300 underline underline-offset-2">
                문서를 추가
              </button>
              하면 AI가 그 내용을 근거로 답변합니다.
            </p>
          ) : (
            <>
              <ul className="space-y-1 max-h-48 overflow-y-auto pr-0.5">
                {sources.map((s) => (
                  <li
                    key={s.source}
                    className="group flex items-center gap-1.5 bg-gray-800/60 rounded px-2 py-1.5"
                  >
                    <div className="flex-1 min-w-0">
                      {/* title 속성: 파일명·URL이 길어 잘리므로 hover로 전체 확인 */}
                      <p className="text-[11px] text-gray-300 truncate" title={s.source}>
                        {s.source}
                      </p>
                      <p className="text-[10px] text-gray-600">
                        {s.type} · {s.chunkCount}청크
                      </p>
                    </div>

                    {confirmTarget === s.source ? (
                      // 2단계 확인 — 되돌릴 수 없는 삭제라 오클릭 방지
                      <div className="flex items-center gap-1 shrink-0">
                        <button
                          onClick={() => { setConfirmTarget(null); void remove(s.source) }}
                          disabled={deletingSource === s.source}
                          className="text-[10px] text-red-400 hover:text-red-300 disabled:opacity-50"
                        >
                          {deletingSource === s.source ? '삭제 중' : '확인'}
                        </button>
                        <button
                          onClick={() => setConfirmTarget(null)}
                          className="text-[10px] text-gray-500 hover:text-gray-300"
                        >취소</button>
                      </div>
                    ) : (
                      <button
                        onClick={() => setConfirmTarget(s.source)}
                        className="opacity-0 group-hover:opacity-100 focus:opacity-100
                                   text-gray-600 hover:text-red-400 text-[10px] shrink-0 transition"
                        title="이 문서 삭제"
                      >✕</button>
                    )}
                  </li>
                ))}
              </ul>
              <p className="text-[10px] text-gray-700 mt-1.5">
                총 {totalChunks}청크 · AI가 답변 근거로 검색합니다
              </p>
            </>
          )}
        </div>
      )}
    </div>
  )
}
