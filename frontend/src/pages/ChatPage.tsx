import { useCallback, useEffect, useRef, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { useAuthStore } from '@/stores/authStore'
import { useChatStore } from '@/stores/chatStore'
import { createChatRoom, deleteChatRoom, getChatRooms, getChatHistories } from '@/api/chatRoom'
import { getModels } from '@/api/models'  // [신규] 모델 목록 조회
import { useWebSocket } from '@/hooks/useWebSocket'
import type { WsResponse } from '@/hooks/useWebSocket'
import type { ChatRoomResponse, ProviderInfo } from '@/types'
import TypingIndicator from '@/components/TypingIndicator'
import SkeletonRoom from '@/components/SkeletonRoom'
import RagUploadModal from '@/components/RagUploadModal'
import KnowledgePanel from '@/components/KnowledgePanel'
import MarkdownMessage from '@/components/MarkdownMessage'

// ── 로컬 타입 ────────────────────────────────────────────────
interface Message {
  id: string              // crypto.randomUUID() — key prop용 안정적 고유 ID
  role: 'user' | 'assistant'
  content: string
  isError?: boolean       // true면 TOKEN append 대상에서 제외 (StrictMode 이중 연결 방어)
  retryContent?: string   // [신규] 에러 발생 직전 사용자 메시지 — "다시 시도" 버튼에서 재사용
}

type ResponsePhase = 'idle' | 'queued' | 'generating'

// ── ChatPage ─────────────────────────────────────────────────
/**
 * [역할] 전체 채팅 페이지 레이아웃 + 채팅방 CRUD + 모델 선택
 *
 * [설계 결정사항]
 * - selectedProvider·selectedModel: ChatPage 전역 상태 → ChatView prop으로 전달
 *   → useWebSocket URL 파라미터화 → WS 연결 시 모델 고정
 * - providers: 마운트 시 /api/v1/models 조회 → 사이드바 selector 렌더링
 *   실패 시 selector 미표시 (auth flow 영향 없음)
 * - 기본값: ollama + qwen3:8b → API key 없어도 정상 동작 (하위 호환)
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
  const [sidebarOpen, setSidebarOpen] = useState(false)     // [신규] 모바일 사이드바 토글 (개선안 #9)

  // [신규] 모델 선택 상태 — 사이드바에서 전역 관리
  const [providers, setProviders] = useState<ProviderInfo[]>([])     // /api/v1/models 결과
  const [selectedProvider, setSelectedProvider] = useState('ollama') // 기본: Ollama
  const [selectedModel, setSelectedModel] = useState('qwen3:8b')     // 기본: qwen3:8b

  useEffect(() => {
    if (!isAuthenticated()) {
      navigate('/login')
      return
    }

    // 채팅방 목록 로딩 (auth 실패 시 로그아웃)
    getChatRooms()
      .then((res) => setRooms(res.data.data ?? []))
      .catch((err) => {
        const status = err?.response?.status
        if (status === 401 || status === 403 || status === 404) {
          logout()
          navigate('/login')
        }
      })
      .finally(() => setRoomsLoading(false))

    // 모델 목록 로딩 (실패해도 auth flow 영향 없음 → selector 미표시)
    getModels()
      .then((res) => setProviders(res.data.data ?? []))
      .catch(() => {}) // 실패 시 selector 미표시 (빈 providers 상태 유지)
  }, [])  // eslint-disable-line react-hooks/exhaustive-deps

  // [신규] 사이드바 모델 선택 핸들러
  // value 형식: "provider|model" (예: "claude|claude-sonnet-4-6")
  // '|' 구분자 사용 이유: Ollama 모델명에 ':'가 포함됨 (예: qwen3:8b)
  const handleModelChange = (value: string) => {
    const separatorIdx = value.indexOf('|')
    if (separatorIdx === -1) return
    setSelectedProvider(value.slice(0, separatorIdx))
    setSelectedModel(value.slice(separatorIdx + 1))
    // [설계] 모델 변경 시 useWebSocket 의존성(provider, model) 변경 → WS 자동 재연결
  }

  // [설계] 첫 메시지 입력 → 채팅방 자동 생성: LLM 서비스(Claude, ChatGPT) UX 패턴
  const handleWelcomeSubmit = async (content: string) => {
    const title = content.slice(0, 30).trim() || '새 대화'
    const res = await createChatRoom(title)
    const room = res.data.data!
    addRoom(room)
    selectRoom(room)
    setInitialMsg(content)
    setSidebarOpen(false)  // [신규] 모바일 대응
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
      setSidebarOpen(false)  // [신규] 모바일 대응
    } finally {
      setCreating(false)
    }
  }

  // 사이드바에서 방 직접 선택: initialMsg 초기화 (자동 전송 없음)
  const handleSelectRoom = (room: ChatRoomResponse) => {
    selectRoom(room)
    setInitialMsg(null)
    setSidebarOpen(false)  // [신규] 모바일에서 방 선택 시 사이드바 자동 닫힘
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
    <div className="flex h-screen bg-gray-950 text-white overflow-hidden relative">

      {/* [신규] 모바일 오버레이 — 사이드바 열렸을 때 바깥 클릭으로 닫기 (개선안 #9) */}
      {sidebarOpen && (
        <div
          className="fixed inset-0 bg-black/60 z-30 md:hidden"
          onClick={() => setSidebarOpen(false)}
        />
      )}

      {/* ── 사이드바 ── */}
      {/* [신규] 모바일: 기본 숨김 + 슬라이드인 오버레이 / md 이상: 항상 표시되는 고정 컬럼 */}
      <aside
        className={`fixed inset-y-0 left-0 z-40 w-64 shrink-0 bg-gray-900 border-r border-gray-800
                    flex flex-col transition-transform duration-200 ease-out
                    md:static md:translate-x-0
                    ${sidebarOpen ? 'translate-x-0' : '-translate-x-full'}`}
      >
        <div className="px-4 py-3 border-b border-gray-800 font-bold text-violet-400 text-lg flex items-center justify-between">
          BTLLM
          {/* [신규] 모바일 전용 닫기 버튼 */}
          <button
            onClick={() => setSidebarOpen(false)}
            className="md:hidden text-gray-500 hover:text-white text-base leading-none"
            aria-label="사이드바 닫기"
          >✕</button>
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

        {/* [신규] 모델 선택 — provider별 optgroup으로 그룹핑 */}
        {providers.length > 0 && (
          <div className="px-3 py-2 border-t border-gray-800">
            <p className="text-xs text-gray-500 mb-1.5">언어 모델</p>
            <select
              value={`${selectedProvider}|${selectedModel}`}
              onChange={(e) => handleModelChange(e.target.value)}
              className="w-full bg-gray-800 text-white text-xs rounded-lg px-2 py-1.5
                         border border-gray-700 focus:border-violet-500 outline-none cursor-pointer"
            >
              {providers.map((prov) => (
                // [설계] available=false provider는 optgroup label에 "(API key 없음)" 표시
                //        option disabled → 선택 불가, 단 목록에는 표시 (어떤 provider 지원하는지 파악 가능)
                <optgroup
                  key={prov.provider}
                  label={prov.available ? prov.providerName : `${prov.providerName} (API key 없음)`}
                >
                  {prov.models.map((m) => (
                    <option
                      key={m.id}
                      value={`${prov.provider}|${m.id}`}
                      disabled={!prov.available}  // API key 없으면 선택 불가
                    >
                      {m.name}
                    </option>
                  ))}
                </optgroup>
              ))}
            </select>
          </div>
        )}

        {/* [신규] 지식베이스 상시 노출 — 지금 AI가 참조 가능한 문서를 채팅 중에도 확인·삭제 가능 */}
        <KnowledgePanel onAddClick={() => setRagOpen(true)} />

        <div className="p-3 border-t border-gray-800 flex flex-col gap-1">
          <Link
            to="/about"
            className="w-full text-left text-gray-500 hover:text-white text-sm
                       py-1.5 px-2 transition rounded-lg hover:bg-gray-800"
          >
            사용 가이드
          </Link>
          <button
            onClick={handleLogout}
            className="w-full text-left text-gray-500 hover:text-white text-sm py-1.5 px-2
                       transition rounded-lg hover:bg-gray-800"
          >로그아웃</button>
        </div>
      </aside>

      {/* ── 메인 영역 ── */}
      <main className="flex-1 flex flex-col min-w-0">
        {/* [신규] 모바일 전용 상단바 — 사이드바가 기본 숨김이라 여는 진입점 필요 (개선안 #9) */}
        <div className="md:hidden flex items-center gap-3 px-4 py-3 border-b border-gray-800 shrink-0">
          <button
            onClick={() => setSidebarOpen(true)}
            className="text-gray-400 hover:text-white text-xl leading-none"
            aria-label="사이드바 열기"
          >☰</button>
          <span className="font-bold text-violet-400 text-sm">BTLLM</span>
        </div>
        {selectedRoom
          ? (
            // key={room.id}: 방 전환 시 ChatView 재마운트 → WS + 메시지 상태 초기화
            <ChatView
              key={selectedRoom.id}
              room={selectedRoom}
              initialMessage={initialMsg}
              provider={selectedProvider}   // [신규] 선택된 provider 전달
              model={selectedModel}         // [신규] 선택된 model 전달
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
 * - provider·model: useWebSocket URL 파라미터로 전달 → WS 연결 시 모델 고정
 * - provider·model 변경 시 useWebSocket의 useEffect 재실행 → WS 재연결 (새 모델 적용)
 * - initialMessage 처리 흐름:
 *   ① 마운트 시 사용자 메시지 즉시 UI 표시 (낙관적 업데이트)
 *   ② pendingRef에 저장 → WS 연결 완료(handleOpen) 시 자동 전송
 */
function ChatView({
  room,
  initialMessage,
  provider,
  model,
}: {
  room: ChatRoomResponse
  initialMessage?: string | null
  provider: string  // [신규] LLM provider (예: "ollama", "claude")
  model: string     // [신규] 모델명 (예: "qwen3:8b", "claude-sonnet-4-6")
}) {
  const [messages, setMessages] = useState<Message[]>(() => (
    initialMessage
      ? [{ id: crypto.randomUUID(), role: 'user', content: initialMessage }]
      : []
  ))
  const [input, setInput] = useState('')
  const [isStreaming, setIsStreaming] = useState(false)
  const [responsePhase, setResponsePhase] = useState<ResponsePhase>('idle')
  const [queueMessage, setQueueMessage] = useState<string | null>(null)
  const [elapsedSeconds, setElapsedSeconds] = useState(0)
  const [isConnected, setIsConnected] = useState(false)
  const [reconnectInfo, setReconnectInfo] = useState<{ attempt: number; max: number } | null>(null)
  const bottomRef = useRef<HTMLDivElement>(null)
  const pendingRef = useRef<string | null>(initialMessage ?? null)
  const sendMsgRef = useRef<(content: string) => boolean>(() => false)
  const lastSentContentRef = useRef<string | null>(null)  // [신규] 재전송 버튼용 — 직전에 보낸 사용자 메시지

  // initialMessage는 state/ref 초기값에서 처리한다. 기존 방일 때만 저장 이력을 비동기로 로드한다.
  useEffect(() => {
    if (!initialMessage) {
      // 기존 방 선택: DB 저장 이력 로드
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
    if (res.type === 'READY') {
      return
    } else if (res.type === 'QUEUED') {
      setResponsePhase('queued')
      setQueueMessage(res.message ?? null)
    } else if (res.type === 'TOKEN') {
      setResponsePhase('generating')
      setMessages((prev) => {
        const last = prev[prev.length - 1]
        if (last?.role === 'assistant' && !last.isError) {
          // 마지막 assistant 메시지에 토큰 누적 (배열 교체로 불변성 유지)
          return [...prev.slice(0, -1), { ...last, content: last.content + (res.content ?? '') }]
        }
        // 첫 TOKEN: assistant 메시지 신규 생성
        return [...prev, { id: crypto.randomUUID(), role: 'assistant', content: res.content ?? '' }]
      })
    } else if (res.type === 'DONE') {
      setResponsePhase('idle')
      setQueueMessage(null)
      setIsStreaming(false)
    } else if (res.type === 'ERROR') {
      setResponsePhase('idle')
      setQueueMessage(null)
      setMessages((prev) => [
        ...prev,
        {
          id: crypto.randomUUID(),
          role: 'assistant',
          content: `⚠ ${res.message ?? '오류가 발생했습니다.'}`,
          isError: true,
          retryContent: lastSentContentRef.current ?? undefined,  // [신규] 재전송 버튼용
        },
      ])
      setIsStreaming(false)
    }
  }, [])

  const handleOpen = useCallback(() => {
    setIsConnected(true)
    setReconnectInfo(null)  // [신규] 재연결 성공 → 재연결 안내 해제
    // [설계] 재연결 성공 시 오류 메시지 제거 (React StrictMode 이중 연결 오류 bubble 정리)
    setMessages(prev => prev.filter(m => !m.isError))
    // WS 연결 완료 시 대기 중 메시지 자동 전송 (initialMessage 자동 생성 흐름)
    if (pendingRef.current) {
      const msg = pendingRef.current
      pendingRef.current = null
      setIsStreaming(true)
      setResponsePhase('queued')
      setElapsedSeconds(0)
      lastSentContentRef.current = msg
      sendMsgRef.current(msg)
    }
  }, [])

  const handleClose = useCallback(() => setIsConnected(false), [])
  // [신규] 지수 백오프 재연결 진행 상황 → 헤더 배지에 표시 (개선안 #5)
  const handleReconnecting = useCallback((attempt: number, max: number) => {
    setReconnectInfo({ attempt, max })
  }, [])

  const { sendMessage } = useWebSocket({
    conversationId: room.conversationId,
    provider,   // [신규] 사이드바 선택값 → WS URL 파라미터로 전달
    model,      // [신규]
    onMessage: handleWsMessage,
    onOpen: handleOpen,
    onClose: handleClose,
    onReconnecting: handleReconnecting,
  })

  // 렌더 중 ref 변경을 피하면서 handleOpen이 최신 sendMessage를 참조하도록 동기화한다.
  useEffect(() => {
    sendMsgRef.current = sendMessage
  }, [sendMessage])

  // 모델 응답이 진행되는 동안 사용자에게 실제 경과 시간을 보여준다.
  useEffect(() => {
    if (!isStreaming) return
    const timer = setInterval(() => setElapsedSeconds((seconds) => seconds + 1), 1000)
    return () => clearInterval(timer)
  }, [isStreaming])

  // 새 메시지 추가 시 하단 자동 스크롤
  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: 'smooth' })
  }, [messages])

  const handleSend = (content: string) => {
    if (!content.trim() || isStreaming) return
    setMessages((prev) => [...prev, { id: crypto.randomUUID(), role: 'user', content }])
    setIsStreaming(true)
    setResponsePhase('queued')
    setQueueMessage(null)
    setElapsedSeconds(0)
    lastSentContentRef.current = content  // [신규] 재전송 버튼용
    sendMessage(content)  // [설계] 연결 전이어도 훅 내부 큐에 담겼다가 open 시 자동 전송됨 (개선안 #7)
    setInput('')
  }

  // [신규] 에러 말풍선의 "다시 시도" — 직전 사용자 메시지를 그대로 재전송 (개선안 #6)
  const handleRetry = (content: string) => {
    setMessages((prev) => prev.filter((m) => !m.isError))
    handleSend(content)
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

      {/* 헤더 — 방 제목 + 현재 모델 배지 + 연결 상태 */}
      <div className="px-6 py-4 border-b border-gray-800 flex items-center gap-3 shrink-0">
        <span className="font-semibold text-gray-200 truncate">{room.title}</span>
        {/* [신규] 현재 사용 중인 모델 배지 */}
        <span className="text-xs text-gray-600 bg-gray-800 px-2 py-0.5 rounded-full shrink-0">
          {provider === 'claude' ? '🤖' : provider === 'gemini' ? '✨' : provider === 'openai' ? '🧠' : '🦙'} {model}
        </span>
        <span className={`ml-auto shrink-0 text-xs px-2 py-0.5 rounded-full ${
          isConnected ? 'bg-green-950 text-green-400'
            : reconnectInfo ? 'bg-amber-950 text-amber-400'
            : 'bg-gray-800 text-gray-500'
        }`}>
          {/* [신규] 재연결 시도 중이면 시도 횟수 표시 (개선안 #5) */}
          {isConnected ? '연결됨' : reconnectInfo ? `재연결 중... (${reconnectInfo.attempt}/${reconnectInfo.max})` : '연결 중...'}
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
              className={`break-words ${
                msg.role === 'user'
                  ? 'max-w-[80%] whitespace-pre-wrap rounded-2xl rounded-br-sm bg-violet-800 px-4 py-3 text-sm leading-relaxed text-white'
                  : 'w-full max-w-3xl rounded-2xl rounded-bl-sm border border-gray-800 bg-gray-900/70 px-5 py-4 text-gray-100 shadow-sm'
              }`}
            >
              {msg.role === 'user' ? (
                // 사용자 메시지: 마크다운 불필요, 입력 그대로 출력
                msg.content
              ) : (
                // assistant 메시지: 마크다운 렌더링
                <MarkdownMessage content={msg.content} />
              )}
              {/* 스트리밍 커서: 마지막 assistant 메시지에만 표시 */}
              {isStreaming && msg.role === 'assistant' && i === messages.length - 1 && (
                <span className="inline-block w-0.5 h-4 bg-violet-400 ml-0.5 animate-pulse align-middle" />
              )}
              {/* [신규] 에러 말풍선 — 직전 사용자 메시지 재전송 버튼 (개선안 #6) */}
              {msg.isError && msg.retryContent && (
                <button
                  onClick={() => handleRetry(msg.retryContent!)}
                  disabled={isStreaming}
                  className="mt-2 block text-xs text-violet-400 hover:text-violet-300
                             disabled:opacity-40 underline underline-offset-2 transition"
                >
                  다시 시도
                </button>
              )}
            </div>
          </div>
        ))}
        {/* 응답 대기 스피너: 전송 후 첫 토큰 도착 전 */}
        {responsePhase === 'queued' && (
          <TypingIndicator elapsedSeconds={elapsedSeconds} message={queueMessage} />
        )}
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
            placeholder={isStreaming
              ? responsePhase === 'generating' ? '답변 생성 중...' : '답변 준비 중...'
              : '메시지 입력 (Shift+Enter: 줄바꿈)'}
            value={input}
            onChange={(e) => setInput(e.target.value)}
            onKeyDown={handleKeyDown}
            rows={1}
            disabled={isStreaming}
          />
          {/* [설계] !isConnected 조건 제거 — 재연결 중이어도 전송 허용, 훅 내부 큐에 담겼다가
                     연결 복구 시 자동 전송됨 (개선안 #7). 완전히 끊긴 상태여도 사용자가
                     타이핑을 막을 이유는 없다 */}
          <button
            type="submit"
            disabled={!input.trim() || isStreaming}
            className="absolute right-3 bottom-3 bg-violet-700 hover:bg-violet-600
                       disabled:opacity-30 text-white rounded-xl px-3 py-1.5
                       text-sm transition font-medium"
          >전송</button>
        </form>
      </div>
    </div>
  )
}
