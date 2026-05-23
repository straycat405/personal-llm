import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useAuthStore } from '@/stores/authStore'
import { useChatStore } from '@/stores/chatStore'
import { getChatRooms, createChatRoom, deleteChatRoom } from '@/api/chatRoom'

// [Phase 2] 이 컴포넌트의 메인 영역(ChatArea)을 WebSocket 스트리밍 UI로 교체
// Phase 1에서는 채팅방 CRUD + 레이아웃 뼈대만 구현
export default function ChatPage() {
  const navigate = useNavigate()
  const { logout, isAuthenticated } = useAuthStore()
  const { rooms, selectedRoom, setRooms, addRoom, removeRoom, selectRoom } = useChatStore()
  const [newTitle, setNewTitle] = useState('')
  const [creating, setCreating] = useState(false)

  // 인증 가드 + 채팅방 목록 로드
  useEffect(() => {
    if (!isAuthenticated()) {
      navigate('/login')
      return
    }
    getChatRooms().then((res) => setRooms(res.data.data ?? []))
  }, [])

  const handleCreate = async (e: React.FormEvent) => {
    e.preventDefault()
    const title = newTitle.trim()
    if (!title) return
    setCreating(true)
    try {
      const res = await createChatRoom(title)
      addRoom(res.data.data!)
      setNewTitle('')
    } finally {
      setCreating(false)
    }
  }

  const handleDelete = async (id: number, e: React.MouseEvent) => {
    // [주의] 이벤트 버블링 차단: 삭제 버튼 클릭이 채팅방 선택으로 전파되지 않도록
    e.stopPropagation()
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

        {/* 헤더 */}
        <div className="px-4 py-3 border-b border-gray-800 font-bold text-violet-400 text-lg">
          BTLLM
        </div>

        {/* 새 채팅방 생성 폼 */}
        <form onSubmit={handleCreate} className="p-3 border-b border-gray-800 flex gap-2">
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
          >
            +
          </button>
        </form>

        {/* 채팅방 목록 */}
        <ul className="flex-1 overflow-y-auto p-2 flex flex-col gap-1">
          {rooms.length === 0 && (
            <li className="text-gray-600 text-xs text-center py-4">
              채팅방을 만들어보세요
            </li>
          )}
          {rooms.map((room) => (
            <li
              key={room.id}
              onClick={() => selectRoom(room)}
              className={`group flex justify-between items-center px-3 py-2 rounded-lg
                          cursor-pointer text-sm transition select-none
                          ${selectedRoom?.id === room.id
                            ? 'bg-gray-700 text-white'
                            : 'text-gray-400 hover:bg-gray-800 hover:text-white'}`}
            >
              <span className="truncate">{room.title}</span>
              <button
                onClick={(e) => handleDelete(room.id, e)}
                className="opacity-0 group-hover:opacity-100 text-gray-500
                           hover:text-red-400 ml-2 text-xs transition"
                title="삭제"
              >
                ✕
              </button>
            </li>
          ))}
        </ul>

        {/* 로그아웃 */}
        <div className="p-3 border-t border-gray-800">
          <button
            onClick={handleLogout}
            className="w-full text-gray-500 hover:text-white text-sm py-1.5 transition rounded-lg hover:bg-gray-800"
          >
            로그아웃
          </button>
        </div>
      </aside>

      {/* ── 메인 채팅 영역 ── */}
      <main className="flex-1 flex flex-col">
        {selectedRoom ? (
          <ChatAreaPlaceholder title={selectedRoom.title} />
        ) : (
          <EmptyState />
        )}
      </main>
    </div>
  )
}

// [Phase 2] 이 컴포넌트를 WebSocket 스트리밍 채팅 UI로 완전 교체
function ChatAreaPlaceholder({ title }: { title: string }) {
  return (
    <div className="flex-1 flex flex-col">
      <div className="px-6 py-4 border-b border-gray-800 font-semibold text-gray-200">
        {title}
      </div>
      <div className="flex-1 flex items-center justify-center text-gray-600 text-sm">
        <div className="text-center">
          <p className="text-2xl mb-2">💬</p>
          <p>Phase 2에서 LLM 스트리밍 채팅이 구현됩니다</p>
          <p className="text-xs mt-1 text-gray-700">WebSocket + Spring AI Advisor 체인</p>
        </div>
      </div>
    </div>
  )
}

function EmptyState() {
  return (
    <div className="flex-1 flex items-center justify-center text-gray-600 text-sm">
      <div className="text-center">
        <p className="text-3xl mb-3">✦</p>
        <p>왼쪽에서 채팅방을 선택하거나 새로 만들어주세요</p>
      </div>
    </div>
  )
}
