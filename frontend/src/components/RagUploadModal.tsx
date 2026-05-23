import { useRef, useState } from 'react'
import { ingestPdf, ingestUrl } from '@/api/etl'

// [역할] RAG 문서 인덱싱 모달 — URL 크롤링 / PDF 업로드 탭 전환
export default function RagUploadModal({ onClose }: { onClose: () => void }) {
  const [tab, setTab] = useState<'url' | 'pdf'>('url')
  const [url, setUrl] = useState('')
  const [status, setStatus] = useState<'idle' | 'loading' | 'success' | 'error'>('idle')
  const [message, setMessage] = useState('')
  const fileInputRef = useRef<HTMLInputElement>(null)

  const handleUrl = async () => {
    if (!url.trim() || status === 'loading') return
    setStatus('loading')
    setMessage('')
    try {
      await ingestUrl(url.trim())
      setStatus('success')
      setMessage('인덱싱 완료')
      setUrl('')
    } catch {
      setStatus('error')
      setMessage('인덱싱 실패 — URL 접근 가능 여부 확인')
    }
  }

  const handlePdf = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0]
    if (!file) return
    setStatus('loading')
    setMessage('')
    try {
      await ingestPdf(file)
      setStatus('success')
      setMessage(`${file.name} 인덱싱 완료`)
    } catch {
      setStatus('error')
      setMessage('인덱싱 실패')
    } finally {
      // [설계] 같은 파일 재업로드 가능하도록 input value 초기화
      e.target.value = ''
    }
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
      onClick={onClose}
    >
      <div
        className="w-full max-w-md bg-gray-900 rounded-xl border border-gray-700 p-6 shadow-2xl"
        onClick={(e) => e.stopPropagation()}  // [설계] 배경 클릭 닫기와 분리
      >
        {/* 헤더 */}
        <div className="flex items-center justify-between mb-5">
          <h2 className="text-white font-semibold text-base">문서 인덱싱 (RAG)</h2>
          <button
            onClick={onClose}
            className="text-gray-500 hover:text-white text-lg leading-none transition"
            aria-label="닫기"
          >✕</button>
        </div>

        {/* 탭 */}
        <div className="flex gap-1 mb-4 bg-gray-800 rounded-lg p-1">
          {(['url', 'pdf'] as const).map((t) => (
            <button
              key={t}
              onClick={() => { setTab(t); setStatus('idle'); setMessage('') }}
              className={`flex-1 py-1.5 rounded-md text-sm font-medium transition ${
                tab === t
                  ? 'bg-gray-700 text-white'
                  : 'text-gray-500 hover:text-gray-300'
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
                {status === 'loading' ? '업로드 중...' : 'PDF 파일 클릭하여 선택'}
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

        {/* 결과 메시지 */}
        {message && (
          <p className={`mt-3 text-sm ${statusColor}`}>{message}</p>
        )}
      </div>
    </div>
  )
}
