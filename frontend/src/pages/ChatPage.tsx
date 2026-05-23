import { useCallback, useEffect, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useAuthStore } from '@/stores/authStore'
import { useChatStore } from '@/stores/chatStore'
import { createChatRoom, deleteChatRoom, getChatRooms, getChatHistories } from '@/api/chatRoom'
import { useWebSocket } from '@/hooks/useWebSocket'
import type { WsResponse } from '@/hooks/useWebSocket'
import type { ChatRoomResponse } from '@/types'
import TypingIndicator from '@/components/TypingIndicator'
import SkeletonRoom from '@/components/SkeletonRoom'
import RagUploadModal from '@/components/RagUploadModal'
import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'

// ── 로컬 타입 ────────────────────────────────────────────────
interface Message {
  id: string              // crypto.randomUUID() — key prop용 안정적 고유 ID
  role: 'user' | 'assistant'
  content: string
}

// ── ChatPage ─────────────────────────────────────────────────
/**
 * [역할] 전체 채팅 페이지 레이아웃 + 채팅방 CRUD
 *
 * [설계 결정사항]
 * - initialMsg: WelcomeView 첫 메시지 전송 시 채팅방 자동 생성 후 ChatView에 전달
 *   ChatView 마운트 → WebSocket 연결 완료 → 자동 전송 흐름
 * - ChatView에 key={room.id}: 방 전환 시 완전 재마운트 → 메시지 상태·WS 초기화
 */
export default function ChatPage() {
  const navigate = useNavigate()
  const { logout, isAuthenticated } = useAuthStore()
  const { rooms, selectedRoom, setRooms, addRoom, removeRoom, selectRoom } = useChatStore()
  const [newTitle, setNewTitle] = useState('')
  const [creating, setCreating] = useState(false)
  const [initialMsg, setInitialMsg] = useState<string | null>(null)
  const [roomsLoading, setRoomsLoading] = useState(true)  // 채팅방 목록 초기 로딩
  const [ragOpen, setRagOpen] = useState(false)            // 문서 인덱싱 모달 표시

  useEffect(() => {
    if (!isAuthenticated()) {
      navigate('/login')
      return
    }
    getChatRooms()
      .then((res) => setRooms(res.data.data ?? []))
      .catch((err) => {
        // 토큰 만료 또는 서버에 해당 사용자 없음(DB 리셋 등) → 강제 로그아웃
        const status = err?.response?.status
        if (status === 401 || status === 403 || status === 404) {
          logout()
          navigate('/login')
        }
      })
      .finally(() => setRoomsLoading(false))  // 성공·실패 무관하게 로딩 해제
  }, [])  // eslint-disable-line react-hooks/exhaustive-deps

  // [설계] 첫 메시지 입력 → 채팅방 자동 생성: LLM 서비스(Claude, ChatGPT) UX 패턴
  // 제목은 메시지 앞 30자 사용 → 별도 제목 입력 불필요
  const handleWelcomeSubmit = async (content: string) => {
    const title = content.slice(0, 30).trim() || '새 대화'
    const res = await createChatRoom(title)
    const room = res.data.data!
    addRoom(room)
    selectRoom(room)
    setInitialMsg(content)
  }

  const handleCreateRoom = async (e: React.FormEvent) => {
    e.preventDefault()
    const title = newTitle.trim()
    if (!title) return
    setCreating(true)
    try {
      const res = await createChatRoom(title)
      const room = res.data.data!
      addRoom(room)
      selectRoom(room)
      setNewTitle('')
      setInitialMsg(null)
    } finally {
      setCreating(false)
    }
  }

  // 사이드바에서 방 직접 선택: initialMsg 초기화 (자동 전송 없음)
  const handleSelectRoom = (room: ChatRoomResponse) => {
    selectRoom(room)
    setInitialMsg(null)
  }

  const handleDeleteRoom = async (id: number, e: React.MouseEvent) => {
    e.stopPropagation()  // 방 선택 이벤트로 버블링 방지
    await deleteChatRoom(id)
    removeRoom(id)
  }

  const handleLogout = () => {
    logout()
    navigate('/login')
  }

  return (
    <div className="flex h-screen bg-gray-950 text-white overflow-hidden">

      {/* ── 사이드바 ── */}
      <aside className="w-64 shrink-0 bg-gray-900 border-r border-gray-800 flex flex-col">
        <div className="px-4 py-3 border-b border-gray-800 font-bold text-violet-400 text-lg">
          BTLLM
        </div>

        {/* 채팅방 수동 생성 폼 (제목 직접 지정) */}
        <form onSubmit={handleCreateRoom} className="p-3 border-b border-gray-800 flex gap-2">
          <input
            className="flex-1 bg-gray-800 text-white text-sm rounded-lg px-3 py-1.5
                       outline-none focus:ring-1 focus:ring-violet-500 placeholder-gray-600"
            placeholder="채팅방 제목"
            value={newTitle}
            onChange={(e) => setNewTitle(e.target.value)}
          />
          <button
            type="submit"
            disabled={creating || !newTitle.trim()}
            className="bg-violet-700 hover:bg-violet-600 disabled:opacity-40
                       text-white text-sm rounded-lg px-3 py-1.5 transition shrink-0"
          >+</button>
        </form>

        {/* 채팅방 목록 */}
        <ul className="flex-1 overflow-y-auto p-2 flex flex-col gap-1">
          {roomsLoading ? (
            // 스켈레톤: 초기 로딩 중 shimmer UI
            <>
              <SkeletonRoom />
              <SkeletonRoom />
              <SkeletonRoom />
            </>
          ) : rooms.length === 0 ? (
            <li className="text-gray-600 text-xs text-center py-4">채팅방을 만들어보세요</li>
          ) : (
            rooms.map((room) => (
              <li
                key={room.id}
                onClick={() => handleSelectRoom(room)}
                className={`group flex justify-between items-center px-3 py-2 rounded-lg
                            cursor-pointer text-sm transition select-none
                            ${selectedRoom?.id === room.id
                              ? 'bg-gray-700 text-white'
                              : 'text-gray-400 hover:bg-gray-800 hover:text-white'}`}
              >
                <span className="truncate">{room.title}</span>
                <button
                  onClick={(e) => handleDeleteRoom(room.id, e)}
                  className="opacity-0 group-hover:opacity-100 text-gray-500
                             hover:text-red-400 ml-2 text-xs transition"
                  title="삭제"
                >✕</button>
              </li>
            ))
          )}
        </ul>

        <div className="p-3 border-t border-gray-800 flex flex-col gap-1">
          <button
            onClick={() => setRagOpen(true)}
            className="w-full text-left text-gray-400 hover:text-white text-sm
                       py-1.5 px-2 transition rounded-lg hover:bg-gray-800"
          >
            문서 인덱싱
          </button>
          <button
            onClick={handleLogout}
            className="w-full text-gray-500 hover:text-white text-sm py-1.5
                       transition rounded-lg hover:bg-gray-800"
          >로그아웃</button>
        </div>
      </aside>

      {/* ── 메인 영역 ── */}
      <main className="flex-1 flex flex-col min-w-0">
        {selectedRoom
          ? (
            // key={room.id}: 방 전환 시 ChatView 재마운트 → WS + 메시지 상태 초기화
            <ChatView
              key={selectedRoom.id}
              room={selectedRoom}
              initialMessage={initialMsg}
            />
          )
          : <WelcomeView onSubmit={handleWelcomeSubmit} />
        }
      </main>

      {/* RAG 문서 인덱싱 모달 */}
      {ragOpen && <RagUploadModal onClose={() => setRagOpen(false)} />}
    </div>
  )
}

// ── WelcomeView ──────────────────────────────────────────────
/**
 * [역할] 채팅방 미선택 초기 화면
 *
 * [설계 결정사항]
 * - 중앙 입력창 배치: 첫 접속 시 바로 입력 가능 → 채팅방 자동 생성
 * - Enter 전송 / Shift+Enter 줄바꿈: 표준 채팅 UX
 */
function WelcomeView({ onSubmit }: { onSubmit: (content: string) => Promise<void> }) {
  const [input, setInput] = useState('')
  const [loading, setLoading] = useState(false)

  const doSubmit = async () => {
    const content = input.trim()
    if (!content || loading) return
    setLoading(true)
    try {
      await onSubmit(content)
    } finally {
      setLoading(false)
    }
  }

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault()
    void doSubmit()
  }

  const handleKeyDown = (e: React.KeyboardEvent<HTMLTextAreaElement>) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault()
      void doSubmit()
    }
  }

  return (
    <div className="flex-1 flex flex-col items-center justify-center gap-8 p-8">
      <div className="text-center">
        <p className="text-4xl font-light text-violet-400 mb-2">BTLLM</p>
        <p className="text-gray-500 text-sm">무엇을 도와드릴까요?</p>
      </div>

      <form onSubmit={handleSubmit} className="w-full max-w-2xl">
        <div className="relative bg-gray-900 border border-gray-700 rounded-2xl
                        focus-within:border-violet-500 transition">
          <textarea
            className="w-full bg-transparent text-white text-sm px-5 py-4 pr-20
                       resize-none outline-none placeholder-gray-600
                       min-h-[56px] max-h-40"
            placeholder="메시지를 입력하세요 (Shift+Enter: 줄바꿈)"
            value={input}
            onChange={(e) => setInput(e.target.value)}
            onKeyDown={handleKeyDown}
            rows={1}
            disabled={loading}
          />
          <button
            type="submit"
            disabled={!input.trim() || loading}
            className="absolute right-3 bottom-3 bg-violet-700 hover:bg-violet-600
                       disabled:opacity-30 text-white rounded-xl px-3 py-1.5
                       text-sm transition font-medium"
          >
            {loading ? '...' : '전송'}
          </button>
        </div>
      </form>
    </div>
  )
}

// ── ChatView ─────────────────────────────────────────────────
/**
 * [역할] LLM 스트리밍 채팅 UI (방 1개에 대응)
 *
 * [설계 결정사항]
 * - initialMessage 처리 흐름:
 *   ① 마운트 시 사용자 메시지 즉시 UI 표시 (낙관적 업데이트)
 *   ② pendingRef에 저장 → WS 연결 완료(handleOpen) 시 자동 전송
 * - pendingRef: state 대신 ref 사용 → isConnected 변화와 렌더링 타이밍 충돌 방지
 * - sendMsgRef: handleOpen이 useWebSocket 호출 이전 정의되므로 sendMessage 직접 참조 불가
 *   ref를 경유하여 매 렌더 최신 sendMessage 참조
 * - TOKEN 응답: 마지막 assistant 메시지에 누적 (불변성 유지 위해 배열 교체 방식)
 *
 * [TODO Phase 3] 마운트 시 REST API로 대화 이력 로드
 */
function ChatView({
  room,
  initialMessage,
}: {
  room: ChatRoomResponse
  initialMessage?: string | null
}) {
  const [messages, setMessages] = useState<Message[]>([])
  const [input, setInput] = useState('')
  const [isStreaming, setIsStreaming] = useState(false)
  const [isWaiting, setIsWaiting] = useState(false)  // 전송 후 첫 토큰 도착 전 대기
  const [isConnected, setIsConnected] = useState(false)
  const bottomRef = useRef<HTMLDivElement>(null)
  const pendingRef = useRef<string | null>(null)
  const sendMsgRef = useRef<(content: string) => void>(() => {})

  // 마운트 시 처리: initialMessage(자동 생성 흐름) 또는 기존 이력 로드
  // key={room.id}로 방 전환마다 재마운트 → 매번 올바른 이력 fetch
  useEffect(() => {
    if (initialMessage) {
      // 새로 생성된 방: 첫 메시지 낙관적 표시 후 WS 연결 시 자동 전송
      setMessages([{ id: crypto.randomUUID(), role: 'user', content: initialMessage }])
      pendingRef.current = initialMessage
    } else {
      // 기존 방 선택: DB 저장 이력 로드 (USER→user, ASSISTANT→assistant 변환)
      // DB id(number)를 string으로 변환해 Message.id로 사용 → 새 메시지 UUID와 충돌 없음
      getChatHistories(room.id)
        .then((res) => {
          const histories = res.data.data ?? []
          setMessages(histories.map((h) => ({
            id: String(h.id),
            role: h.role === 'USER' ? 'user' : 'assistant' as const,
            content: h.content,
          })))
        })
        .catch(() => {})  // 이력 로드 실패 시 빈 상태로 시작
    }
  }, [])  // eslint-disable-line react-hooks/exhaustive-deps

  const handleWsMessage = useCallback((res: WsResponse) => {
    if (res.type === 'TOKEN') {
      setIsWaiting(false)  // 첫 토큰 도착 → 대기 인디케이터 해제
      setMessages((prev) => {
        const last = prev[prev.length - 1]
        if (last?.role === 'assistant') {
          // 마지막 assistant 메시지에 토큰 누적 (배열 교체로 불변성 유지)
          return [...prev.slice(0, -1), { ...last, content: last.content + (res.content ?? '') }]
        }
        // 첫 TOKEN: assistant 메시지 신규 생성
        return [...prev, { id: crypto.randomUUID(), role: 'assistant', content: res.content ?? '' }]
      })
    } else if (res.type === 'DONE') {
      setIsWaiting(false)
      setIsStreaming(false)
    } else if (res.type === 'ERROR') {
      setIsWaiting(false)
      setMessages((prev) => [
        ...prev,
        { id: crypto.randomUUID(), role: 'assistant', content: `⚠ ${res.message ?? '오류가 발생했습니다.'}` },
      ])
      setIsStreaming(false)
    }
  }, [])

  const handleOpen = useCallback(() => {
    setIsConnected(true)
    // WS 연결 완료 시 대기 중 메시지 자동 전송 (initialMessage 자동 생성 흐름)
    if (pendingRef.current) {
      const msg = pendingRef.current
      pendingRef.current = null
      setIsStreaming(true)
      setIsWaiting(true)  // 자동 전송 시 대기 시작
      sendMsgRef.current(msg)
    }
  }, [])

  const handleClose = useCallback(() => setIsConnected(false), [])

  const { sendMessage } = useWebSocket({
    conversationId: room.conversationId,
    onMessage: handleWsMessage,
    onOpen: handleOpen,
    onClose: handleClose,
  })

  // 매 렌더마다 ref 동기화: handleOpen에서 최신 sendMessage 참조 가능
  sendMsgRef.current = sendMessage

  // 새 메시지 추가 시 하단 자동 스크롤
  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: 'smooth' })
  }, [messages])

  const handleSend = (content: string) => {
    if (!content.trim() || isStreaming || !isConnected) return
    setMessages((prev) => [...prev, { id: crypto.randomUUID(), role: 'user', content }])
    setIsStreaming(true)
    setIsWaiting(true)  // 전송 → 대기 시작
    sendMessage(content)
    setInput('')
  }

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault()
    handleSend(input)
  }

  const handleKeyDown = (e: React.KeyboardEvent<HTMLTextAreaElement>) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault()
      handleSend(input)
    }
  }

  return (
    <div className="flex flex-col h-full">

      {/* 헤더 */}
      <div className="px-6 py-4 border-b border-gray-800 flex items-center gap-3 shrink-0">
        <span className="font-semibold text-gray-200 truncate">{room.title}</span>
        <span className={`ml-auto shrink-0 text-xs px-2 py-0.5 rounded-full ${
          isConnected ? 'bg-green-950 text-green-400' : 'bg-gray-800 text-gray-500'
        }`}>
          {isConnected ? '연결됨' : '연결 중...'}
        </span>
      </div>

      {/* 메시지 목록 */}
      <div className="flex-1 overflow-y-auto px-6 py-6 flex flex-col gap-4">
        {messages.length === 0 && !isStreaming && (
          <p className="text-center text-gray-700 text-sm mt-8">대화를 시작해보세요</p>
        )}
        {messages.map((msg, i) => (
          <div
            key={msg.id}
            className={`flex ${msg.role === 'user' ? 'justify-end' : 'justify-start'}`}
          >
            <div
              className={`max-w-[75%] px-4 py-3 rounded-2xl text-sm leading-relaxed
                          whitespace-pre-wrap break-words ${
                msg.role === 'user'
                  ? 'bg-violet-800 text-white rounded-br-sm'
                  : 'bg-gray-800 text-gray-100 rounded-bl-sm'
              }`}
            >
              {msg.role === 'user' ? (
                // 사용자 메시지: 마크다운 불필요, 입력 그대로 출력
                msg.content
              ) : (
                // assistant 메시지: 마크다운 렌더링
                <div
                  className="prose prose-invert prose-sm max-w-none
                             prose-p:my-1 prose-li:my-0.5 prose-headings:my-2
                             prose-code:text-violet-300 prose-a:text-violet-400"
                >
                  <ReactMarkdown remarkPlugins={[remarkGfm]}>
                    {msg.content}
                  </ReactMarkdown>
                </div>
              )}
              {/* 스트리밍 커서: 마지막 assistant 메시지에만 표시 */}
              {isStreaming && msg.role === 'assistant' && i === messages.length - 1 && (
                <span className="inline-block w-0.5 h-4 bg-violet-400 ml-0.5 animate-pulse align-middle" />
              )}
            </div>
          </div>
        ))}
        {/* 응답 대기 스피너: 전송 후 첫 토큰 도착 전 */}
        {isWaiting && <TypingIndicator />}
        <div ref={bottomRef} />
      </div>

      {/* 입력창 */}
      <div className="px-4 pb-4 pt-2 border-t border-gray-800 shrink-0">
        <form
          onSubmit={handleSubmit}
          className="relative bg-gray-900 border border-gray-700 rounded-2xl
                     focus-within:border-violet-500 transition"
        >
          <textarea
            className="w-full bg-transparent text-white text-sm px-5 py-4 pr-20
                       resize-none outline-none placeholder-gray-600
                       min-h-[56px] max-h-40"
            placeholder={isStreaming ? '응답 대기 중...' : '메시지 입력 (Shift+Enter: 줄바꿈)'}
            value={input}
            onChange={(e) => setInput(e.target.value)}
            onKeyDown={handleKeyDown}
            rows={1}
            disabled={isStreaming}
          />
          <button
            type="submit"
            disabled={!input.trim() || isStreaming || !isConnected}
            className="absolute right-3 bottom-3 bg-violet-700 hover:bg-violet-600
                       disabled:opacity-30 text-white rounded-xl px-3 py-1.5
                       text-sm transition font-medium"
          >전송</button>
        </form>
      </div>
    </div>
  )
}
