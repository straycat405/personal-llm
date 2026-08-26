interface TypingIndicatorProps {
  elapsedSeconds: number
  message?: string | null
}

// [역할] 요청 접수 후 첫 토큰 전까지 서버 처리 상태와 경과 시간을 명확히 표시
export default function TypingIndicator({ elapsedSeconds, message }: TypingIndicatorProps) {
  const detail = elapsedSeconds >= 10
    ? '로컬 모델과 문서 검색은 시간이 조금 걸릴 수 있어요.'
    : '잠시만 기다려 주세요.'

  return (
    <div className="flex justify-start">
      <div className="max-w-md rounded-2xl rounded-bl-sm border border-gray-700/80 bg-gray-800/80 px-4 py-3 shadow-sm">
        <div className="flex items-center gap-3">
          <div className="flex shrink-0 gap-1 items-center" aria-hidden="true">
            <span className="inline-block w-1.5 h-1.5 bg-violet-400 rounded-full animate-bounce [animation-delay:-0.32s]" />
            <span className="inline-block w-1.5 h-1.5 bg-violet-400 rounded-full animate-bounce [animation-delay:-0.16s]" />
            <span className="inline-block w-1.5 h-1.5 bg-violet-400 rounded-full animate-bounce" />
          </div>
          <div className="min-w-0">
            <p className="text-sm font-medium text-gray-100">
              {message || '답변을 준비하고 있어요.'}
            </p>
            <p className="mt-0.5 text-xs text-gray-400">
              {detail} <span className="tabular-nums text-gray-500">{elapsedSeconds}초</span>
            </p>
          </div>
        </div>
      </div>
    </div>
  )
}
