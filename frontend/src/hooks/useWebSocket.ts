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
  onMessage: (res: WsResponse) => void
  onOpen?: () => void
  onClose?: () => void
}

/**
 * [역할] WebSocket 연결 생명주기 관리 훅
 *
 * [설계 결정사항]
 * - conversationId 변경 시 재연결: 채팅방 전환 시 기존 연결 닫고 새 연결 자동 생성
 * - 콜백을 ref로 래핑: onMessage 등이 매 렌더 재생성되어도 effect 재실행 방지
 *   (useEffect 의존성에 콜백 포함 시 연결 중 매 렌더마다 재연결되는 문제 방지)
 * - token을 localStorage에서 직접 읽음: 연결 시점에 항상 최신 토큰 사용
 * - window.location.host 사용: Vite dev proxy(/ws → ws://localhost:8080)와
 *   운영 환경 Nginx reverse proxy 모두 자동 대응
 *
 * [주의] sendMessage는 WebSocket.OPEN 상태일 때만 실제 전송됨
 * WS 연결 전 sendMessage 호출은 무시 → 호출측에서 pendingRef 패턴으로 처리
 */
export function useWebSocket({
  conversationId,
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

    const ws = new WebSocket(`ws://${window.location.host}/ws/chat?token=${token}`)
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

    // cleanup: 방 전환 또는 언마운트 시 연결 닫기
    return () => ws.close()
  }, [conversationId])

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
