import { useCallback, useEffect, useRef, useState } from 'react'
import type { EtlSourceResponse } from '@/api/etl'
import {
  deleteSource,
  ingestFile,
  ingestUrl,
  listSources,
  subscribeProgress,
} from '@/api/etl'

// [역할] RAG 문서 인덱싱 + 지식베이스 관리 모달
//   탭 3종: URL 크롤링 / PDF 업로드 / 지식베이스(목록·삭제)
export default function RagUploadModal({ onClose }: { onClose: () => void }) {
  const [tab, setTab] = useState<'url' | 'file' | 'kb'>('url')
  const [url, setUrl] = useState('')
  const [status, setStatus] = useState<'idle' | 'loading' | 'success' | 'error'>('idle')
  const [message, setMessage] = useState('')
  const [progress, setProgress] = useState(0)
  const fileInputRef = useRef<HTMLInputElement>(null)
  // [설계] EventSource ref: 모달 닫힘 또는 탭 전환 시 SSE 연결 명시적 종료
  const esRef = useRef<EventSource | null>(null)

  // ── 지식베이스 탭 상태 ───────────────────────────────────
  const [sources, setSources] = useState<EtlSourceResponse[]>([])
  const [sourcesLoading, setSourcesLoading] = useState(false)
  // [설계] 삭제 중인 source 추적 → 버튼별 개별 로딩 (전역 status 사용 시 인덱싱 UI 충돌)
  const [deletingSource, setDeletingSource] = useState<string | null>(null)

  // ── 지식베이스 목록 로드 ──────────────────────────────────
  const fetchSources = useCallback(async () => {
    setSourcesLoading(true)
    try {
      const res = await listSources()
      setSources(res.data.data)
    } catch {
      setSources([])
    } finally {
      setSourcesLoading(false)
    }
  }, [])

  // [설계] kb 탭 진입 시 자동 로드 — 매번 최신 상태 반영
  useEffect(() => {
    if (tab === 'kb') void fetchSources()
  }, [tab, fetchSources])

  // ── 소스 삭제 ────────────────────────────────────────────
  const handleDeleteSource = async (source: string) => {
    setDeletingSource(source)
    try {
      await deleteSource(source)
      await fetchSources()  // 삭제 후 목록 갱신
    } finally {
      setDeletingSource(null)
    }
  }

  // ── 인덱싱 진행률 구독 (SSE) ─────────────────────────────
  const startProgress = (jobId: string) => {
    setStatus('loading')
    setProgress(0)
    setMessage('시작 중...')

    esRef.current = subscribeProgress(
      jobId,
      (p, msg) => { setProgress(p); setMessage(msg) },
      () => { setStatus('success'); setProgress(100) },
      (err) => { setStatus('error'); setMessage('오류: ' + err) },
    )
  }

  const handleUrl = async () => {
    if (!url.trim() || status === 'loading') return
    try {
      const res = await ingestUrl(url.trim())
      startProgress(res.data.data.jobId)
      setUrl('')
    } catch {
      setStatus('error')
      setMessage('인덱싱 요청 실패 — URL 확인')
    }
  }

  const handleFile = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0]
    if (!file) return
    try {
      const res = await ingestFile(file)
      startProgress(res.data.data.jobId)
    } catch {
      setStatus('error')
      setMessage('업로드 실패')
    } finally {
      e.target.value = ''
    }
  }

  const handleClose = () => {
    esRef.current?.close()
    onClose()
  }

  const handleTabChange = (t: 'url' | 'file' | 'kb') => {
    esRef.current?.close()
    setTab(t)
    setStatus('idle')
    setMessage('')
    setProgress(0)
  }

  const statusColor = {
    idle: '',
    loading: 'text-gray-400',
    success: 'text-green-400',
    error: 'text-red-400',
  }[status]

  // 탭 레이블 매핑
  const TAB_LABELS: Record<typeof tab, string> = {
    url: 'URL 크롤링',
    file: '파일 업로드',
    kb: '지식베이스',
  }

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/60"
      onClick={handleClose}
    >
      <div
        className="w-full max-w-md bg-gray-900 rounded-xl border border-gray-700 p-6 shadow-2xl"
        onClick={(e) => e.stopPropagation()}
      >
        {/* 헤더 */}
        <div className="flex items-center justify-between mb-5">
          <h2 className="text-white font-semibold text-base">문서 인덱싱 (RAG)</h2>
          <button
            onClick={handleClose}
            className="text-gray-500 hover:text-white text-lg leading-none transition"
            aria-label="닫기"
          >✕</button>
        </div>

        {/* 탭 — 3종 */}
        <div className="flex gap-1 mb-4 bg-gray-800 rounded-lg p-1">
          {(['url', 'file', 'kb'] as const).map((t) => (
            <button
              key={t}
              onClick={() => handleTabChange(t)}
              className={`flex-1 py-1.5 rounded-md text-sm font-medium transition ${
                tab === t ? 'bg-gray-700 text-white' : 'text-gray-500 hover:text-gray-300'
              }`}
            >
              {TAB_LABELS[t]}
            </button>
          ))}
        </div>

        {/* URL 탭 */}
        {tab === 'url' && (
          <div className="flex gap-2">
            <input
              type="url"
              value={url}
              onChange={(e) => setUrl(e.target.value)}
              onKeyDown={(e) => { if (e.key === 'Enter') void handleUrl() }}
              placeholder="https://example.com"
              className="flex-1 bg-gray-800 text-white rounded-lg px-3 py-2 text-sm
                         outline-none focus:ring-2 focus:ring-violet-500 placeholder-gray-600"
              disabled={status === 'loading'}
            />
            <button
              onClick={() => void handleUrl()}
              disabled={status === 'loading' || !url.trim()}
              className="bg-violet-600 hover:bg-violet-500 disabled:opacity-50
                         text-white rounded-lg px-4 py-2 text-sm font-medium transition shrink-0"
            >
              {status === 'loading' ? '처리 중...' : '인덱싱'}
            </button>
          </div>
        )}

        {/* 파일 업로드 탭 — TikaDocumentReader로 멀티포맷 처리 */}
        {tab === 'file' && (
          <div>
            <button
              onClick={() => fileInputRef.current?.click()}
              disabled={status === 'loading'}
              className="w-full border-2 border-dashed border-gray-700 hover:border-violet-500
                         disabled:opacity-50 rounded-xl p-8 text-center transition cursor-pointer"
            >
              <p className="text-gray-400 text-sm">
                {status === 'loading' ? '인덱싱 중...' : '파일 클릭하여 선택'}
              </p>
              {/* [설계] Tika 지원 포맷 안내 — 사용자가 업로드 가능 형식 예측 가능하게 */}
              <p className="text-gray-600 text-xs mt-1">PDF · DOCX · XLSX · PPTX · TXT 지원</p>
            </button>
            <input
              ref={fileInputRef}
              type="file"
              accept=".pdf,.docx,.xlsx,.pptx,.txt,.hwp"
              className="hidden"
              onChange={handleFile}
            />
          </div>
        )}

        {/* 지식베이스 탭 */}
        {tab === 'kb' && (
          <div className="min-h-[120px]">
            {sourcesLoading ? (
              // 로딩 중
              <p className="text-center text-gray-500 text-sm py-8">불러오는 중...</p>
            ) : sources.length === 0 ? (
              // 빈 상태
              <p className="text-center text-gray-500 text-sm py-8">인덱싱된 문서 없음</p>
            ) : (
              // 소스 목록 — 최대 높이 256px, 스크롤 허용
              <ul className="space-y-2 max-h-64 overflow-y-auto pr-1">
                {sources.map((s) => (
                  <li
                    key={s.source}
                    className="flex items-center gap-2 bg-gray-800 rounded-lg px-3 py-2"
                  >
                    <div className="flex-1 min-w-0">
                      {/* [설계] truncate: URL·파일명이 모달 너비 초과 시 말줄임표 처리 */}
                      <p className="text-white text-sm truncate" title={s.source}>
                        {s.source}
                      </p>
                      <p className="text-gray-500 text-xs">
                        {/* type 배지 + 청크 수 */}
                        <span className="inline-block bg-gray-700 rounded px-1 mr-1">{s.type}</span>
                        {s.chunkCount}청크
                      </p>
                    </div>
                    <button
                      onClick={() => void handleDeleteSource(s.source)}
                      disabled={deletingSource === s.source}
                      className="shrink-0 text-red-400 hover:text-red-300 disabled:opacity-50
                                 text-xs font-medium transition"
                    >
                      {deletingSource === s.source ? '삭제 중...' : '삭제'}
                    </button>
                  </li>
                ))}
              </ul>
            )}
            {/* 수동 새로고침 */}
            <button
              onClick={() => void fetchSources()}
              disabled={sourcesLoading}
              className="mt-3 text-xs text-gray-500 hover:text-gray-300 disabled:opacity-50 transition"
            >
              새로고침
            </button>
          </div>
        )}

        {/* 진행률 바 — loading 상태에서만 표시 */}
        {status === 'loading' && (
          <div className="mt-4">
            <div className="flex justify-between text-xs text-gray-400 mb-1">
              <span>{message}</span>
              <span>{progress}%</span>
            </div>
            <div className="w-full bg-gray-800 rounded-full h-2">
              <div
                className="bg-violet-500 h-2 rounded-full transition-all duration-300"
                style={{ width: `${progress}%` }}
              />
            </div>
          </div>
        )}

        {/* 완료/오류 메시지 — kb 탭 제외 */}
        {status !== 'loading' && message && tab !== 'kb' && (
          <p className={`mt-3 text-sm ${statusColor}`}>{message}</p>
        )}
      </div>
    </div>
  )
}
