// [역할] LLM 응답 대기 중 표시 — 메시지 전송 후 첫 토큰 도착 전 렌더링
export default function TypingIndicator() {
  return (
    <div className="flex justify-start">
      <div className="bg-gray-800 rounded-2xl rounded-bl-sm px-4 py-3.5">
        <div className="flex gap-1.5 items-center">
          <span className="inline-block w-2 h-2 bg-gray-400 rounded-full animate-bounce [animation-delay:-0.32s]" />
          <span className="inline-block w-2 h-2 bg-gray-400 rounded-full animate-bounce [animation-delay:-0.16s]" />
          <span className="inline-block w-2 h-2 bg-gray-400 rounded-full animate-bounce" />
        </div>
      </div>
    </div>
  )
}
