import { useRef, useState } from 'react'
import { ingestPdf, ingestUrl, subscribeProgress } from '@/api/etl'

// [역할] RAG 문서 인덱싱 모달 — URL 크롤링 / PDF 업로드 탭 + SSE 진행률 바
export default function RagUploadModal({ onClose }: { onClose: () => void }) {
  const [tab, setTab] = useState<'url' | 'pdf'>('url')
  const [url, setUrl] = useState('')
  const [status, setStatus] = useState<'idle' | 'loading' | 'success' | 'error'>('idle')
  const [message, setMessage] = useState('')
  const [progress, setProgress] = useState(0)
  const fileInputRef = useRef<HTMLInputElement>(null)
  // [설계] EventSource ref: 모달 닫힘 또는 오류 시 SSE 연결 명시적 종료
  const esRef = useRef<EventSource | null>(null)

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

  const handlePdf = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0]
    if (!file) return
    try {
      const res = await ingestPdf(file)
      startProgress(res.data.data.jobId)
    } catch {
      setStatus('error')
      setMessage('업로드 실패')
    } finally {
      e.target.value = ''
    }
  }

  const handleClose = () => {
    // 모달 닫힐 때 SSE 연결 정리
    esRef.current?.close()
    onClose()
  }

  const handleTabChange = (t: 'url' | 'pdf') => {
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

        {/* 탭 */}
        <div className="flex gap-1 mb-4 bg-gray-800 rounded-lg p-1">
          {(['url', 'pdf'] as const).map((t) => (
            <button
              key={t}
              onClick={() => handleTabChange(t)}
              className={`flex-1 py-1.5 rounded-md text-sm font-medium transition ${
                tab === t ? 'bg-gray-700 text-white' : 'text-gray-500 hover:text-gray-300'
              }`}
            >
              {t === 'url' ? 'URL 크롤링' : 'PDF 업로드'}
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

        {/* PDF 탭 */}
        {tab === 'pdf' && (
          <div>
            <button
              onClick={() => fileInputRef.current?.click()}
              disabled={status === 'loading'}
              className="w-full border-2 border-dashed border-gray-700 hover:border-violet-500
                         disabled:opacity-50 rounded-xl p-8 text-center transition cursor-pointer"
            >
              <p className="text-gray-400 text-sm">
                {status === 'loading' ? '인덱싱 중...' : 'PDF 파일 클릭하여 선택'}
              </p>
              <p className="text-gray-600 text-xs mt-1">.pdf 형식만 지원</p>
            </button>
            <input
              ref={fileInputRef}
              type="file"
              accept=".pdf"
              className="hidden"
              onChange={handlePdf}
            />
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

        {/* 완료/오류 메시지 */}
        {status !== 'loading' && message && (
          <p className={`mt-3 text-sm ${statusColor}`}>{message}</p>
        )}
      </div>
    </div>
  )
}
