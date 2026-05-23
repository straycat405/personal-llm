import { create } from 'zustand'
import type { ChatRoomResponse } from '@/types'

interface ChatState {
  rooms: ChatRoomResponse[]
  selectedRoom: ChatRoomResponse | null
  setRooms: (rooms: ChatRoomResponse[]) => void
  addRoom: (room: ChatRoomResponse) => void
  removeRoom: (roomId: number) => void
  selectRoom: (room: ChatRoomResponse | null) => void
}

export const useChatStore = create<ChatState>((set) => ({
  rooms: [],
  selectedRoom: null,

  setRooms: (rooms) => set({ rooms }),

  // [설계] 신규 방을 목록 앞에 추가: 최근 생성 순 정렬
  addRoom: (room) => set((s) => ({ rooms: [room, ...s.rooms] })),

  removeRoom: (roomId) =>
    set((s) => ({
      rooms: s.rooms.filter((r) => r.id !== roomId),
      // 삭제된 방이 선택 중이면 선택 해제
      selectedRoom: s.selectedRoom?.id === roomId ? null : s.selectedRoom,
    })),

  selectRoom: (room) => set({ selectedRoom: room }),
}))
