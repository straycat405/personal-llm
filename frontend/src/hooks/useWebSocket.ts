import { useCallback, useEffect, useRef } from 'react'

// 서버 → 클라이언트 WsResponse와 1:1 대응 (백엔드 WsResponse.java)
export interface WsResponse {
  type: 'TOKEN' | 'DONE' | 'ERROR'
  content?: string         // TOKEN 타입
  message?: string         // ERROR 타입
  promptTokens?: number
  completionTokens?: number
  totalTokens?: number     // DONE 타입
}

interface UseWebSocketOptions {
  conversationId: string | null
  provider: string          // [신규] LLM provider (예: "ollama", "claude")
  model: string             // [신규] 모델명 (예: "qwen3:8b", "claude-sonnet-4-6")
  onMessage: (res: WsResponse) => void
  onOpen?: () => void
  onClose?: () => void
}

/**
 * [역할] WebSocket 연결 생명주기 관리 훅
 *
 * [설계 결정사항]
 * - provider·model을 URL 쿼리 파라미터로 전달: 연결 시점에 모델 고정
 * - model 값 encodeURIComponent: "qwen3:8b"의 ':'가 URL 파라미터 파싱을 깨지 않도록
 * - provider·model을 useEffect 의존성에 포함: 모델 변경 시 WS 자동 재연결
 *   → cleanup(ws.close()) 후 새 provider·model 파라미터로 재연결
 * - 콜백을 ref로 래핑: onMessage 등이 매 렌더 재생성되어도 effect 재실행 방지
 */
export function useWebSocket({
  conversationId,
  provider,
  model,
  onMessage,
  onOpen,
  onClose,
}: UseWebSocketOptions) {
  const wsRef = useRef<WebSocket | null>(null)

  // [설계] ref로 콜백 래핑: effect 의존성에 함수 포함 시 매 렌더 재연결 방지
  const onMessageRef = useRef(onMessage)
  const onOpenRef = useRef(onOpen)
  const onCloseRef = useRef(onClose)
  onMessageRef.current = onMessage
  onOpenRef.current = onOpen
  onCloseRef.current = onClose

  useEffect(() => {
    if (!conversationId) return

    const token = localStorage.getItem('accessToken')
    if (!token) return

    // [신규] provider·model 파라미터 추가 — model은 ':'가 포함될 수 있어 URL 인코딩
    const ws = new WebSocket(
      `ws://${window.location.host}/ws/chat` +
      `?token=${token}` +
      `&provider=${provider}` +
      `&model=${encodeURIComponent(model)}`
    )
    wsRef.current = ws

    ws.onopen = () => onOpenRef.current?.()
    ws.onmessage = (e) => {
      try {
        onMessageRef.current(JSON.parse(e.data) as WsResponse)
      } catch {
        // 비정상 메시지 무시
      }
    }
    ws.onclose = () => onCloseRef.current?.()
    ws.onerror = () => {
      console.error('WebSocket 연결 오류')
      // onMessage ERROR 타입으로 전달 → ChatView에서 사용자에게 표시
      onMessageRef.current({ type: 'ERROR', message: 'WebSocket 연결이 끊어졌습니다.' })
    }

    // cleanup: 방 전환, 모델 변경, 언마운트 시 연결 닫기
    return () => ws.close()
  }, [conversationId, provider, model]) // [신규] provider·model 변경 시 재연결

  const sendMessage = useCallback(
    (content: string) => {
      if (wsRef.current?.readyState === WebSocket.OPEN && conversationId) {
        wsRef.current.send(JSON.stringify({ conversationId, content }))
      }
    },
    [conversationId],
  )

  return { sendMessage }
}
