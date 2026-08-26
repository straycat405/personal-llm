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
  onReconnecting?: (attempt: number, maxAttempts: number) => void  // [신규] 재연결 시도 중 UI 피드백용
}

const MAX_RECONNECT_ATTEMPTS = 5
const RECONNECT_BASE_DELAY_MS = 1000  // 1s → 2s → 4s → 8s → 16s (지수 백오프)

/**
 * [역할] WebSocket 연결 생명주기 관리 훅
 *
 * [설계 결정사항]
 * - provider·model을 URL 쿼리 파라미터로 전달: 연결 시점에 모델 고정
 * - model 값 encodeURIComponent: "qwen3:8b"의 ':'가 URL 파라미터 파싱을 깨지 않도록
 * - provider·model을 useEffect 의존성에 포함: 모델 변경 시 WS 자동 재연결
 *   → cleanup(ws.close()) 후 새 provider·model 파라미터로 재연결
 * - 콜백을 ref로 래핑: onMessage 등이 매 렌더 재생성되어도 effect 재실행 방지
 * - [신규] 지수 백오프 자동 재연결: 서버 재배포·네트워크 순단으로 연결이 끊겨도
 *   사용자가 새로고침하지 않아도 되도록 함 (개선안 #5). 의도적 종료(언마운트·방 전환·
 *   모델 변경)는 intentionalCloseRef로 구분해 재연결 시도하지 않는다.
 * - [신규] 연결 전 전송 큐잉: sendMessage 호출 시점에 소켓이 아직 OPEN이 아니면
 *   메시지를 큐에 담아뒀다가 연결 완료 즉시 전송 — 유실 방지 (개선안 #7)
 */
export function useWebSocket({
  conversationId,
  provider,
  model,
  onMessage,
  onOpen,
  onClose,
  onReconnecting,
}: UseWebSocketOptions) {
  const wsRef = useRef<WebSocket | null>(null)
  const intentionalCloseRef = useRef(false)
  const reconnectAttemptRef = useRef(0)
  const reconnectTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  const outboundQueueRef = useRef<string[]>([])

  // [설계] ref로 콜백 래핑: effect 의존성에 함수 포함 시 매 렌더 재연결 방지
  const onMessageRef = useRef(onMessage)
  const onOpenRef = useRef(onOpen)
  const onCloseRef = useRef(onClose)
  const onReconnectingRef = useRef(onReconnecting)

  // 렌더 중 ref를 변경하면 React의 순수 렌더링 규칙을 깨므로 commit 이후 동기화한다.
  // WebSocket effect의 의존성에는 콜백을 넣지 않아 콜백 재생성으로 인한 재연결은 방지한다.
  useEffect(() => {
    onMessageRef.current = onMessage
    onOpenRef.current = onOpen
    onCloseRef.current = onClose
    onReconnectingRef.current = onReconnecting
  }, [onMessage, onOpen, onClose, onReconnecting])

  useEffect(() => {
    if (!conversationId) return

    intentionalCloseRef.current = false
    reconnectAttemptRef.current = 0

    const connect = () => {
      const token = localStorage.getItem('accessToken')
      if (!token) return

      const ws = new WebSocket(
        `ws://${window.location.host}/ws/chat` +
        `?token=${token}` +
        `&provider=${provider}` +
        `&model=${encodeURIComponent(model)}`
      )
      wsRef.current = ws

      ws.onopen = () => {
        reconnectAttemptRef.current = 0
        // 연결 대기 중 쌓인 메시지 순서대로 flush
        const queued = outboundQueueRef.current
        outboundQueueRef.current = []
        queued.forEach((content) => ws.send(content))
        onOpenRef.current?.()
      }
      ws.onmessage = (e) => {
        try {
          onMessageRef.current(JSON.parse(e.data) as WsResponse)
        } catch {
          // 비정상 메시지 무시
        }
      }
      ws.onclose = () => {
        onCloseRef.current?.()
        if (intentionalCloseRef.current) return  // 의도적 종료(언마운트·방 전환 등) — 재연결 안 함

        if (reconnectAttemptRef.current >= MAX_RECONNECT_ATTEMPTS) return

        reconnectAttemptRef.current += 1
        const attempt = reconnectAttemptRef.current
        const delay = RECONNECT_BASE_DELAY_MS * 2 ** (attempt - 1)  // 1s,2s,4s,8s,16s
        onReconnectingRef.current?.(attempt, MAX_RECONNECT_ATTEMPTS)
        reconnectTimerRef.current = setTimeout(connect, delay)
      }
      ws.onerror = () => {
        console.error('WebSocket 연결 오류')
        // onMessage ERROR 타입으로 전달 → ChatView에서 사용자에게 표시
        onMessageRef.current({ type: 'ERROR', message: 'WebSocket 연결이 끊어졌습니다.' })
      }
    }

    connect()

    // cleanup: 방 전환, 모델 변경, 언마운트 시 연결 닫기 — 재연결 시도하지 않도록 플래그 선처리
    return () => {
      intentionalCloseRef.current = true
      if (reconnectTimerRef.current) clearTimeout(reconnectTimerRef.current)
      wsRef.current?.close()
    }
  }, [conversationId, provider, model]) // [신규] provider·model 변경 시 재연결

  const sendMessage = useCallback(
    (content: string) => {
      if (!conversationId) return false
      const payload = JSON.stringify({ conversationId, content })
      if (wsRef.current?.readyState === WebSocket.OPEN) {
        wsRef.current.send(payload)
        return true
      }
      // 아직 연결 전(또는 재연결 중) — 큐에 담아뒀다가 open 시 자동 전송
      outboundQueueRef.current.push(payload)
      return false
    },
    [conversationId],
  )

  return { sendMessage }
}
