import api from './axios'
import type { ApiResponse, ChatRoomResponse, ChatHistoryResponse } from '@/types'

export const createChatRoom = (title: string) =>
  api.post<ApiResponse<ChatRoomResponse>>('/chat-rooms', { title })

export const getChatRooms = () =>
  api.get<ApiResponse<ChatRoomResponse[]>>('/chat-rooms')

export const deleteChatRoom = (roomId: number) =>
  api.delete<void>(`/chat-rooms/${roomId}`)

// 채팅방 대화 이력 조회 — 방 선택 시 마운트 시점에 호출
export const getChatHistories = (roomId: number) =>
  api.get<ApiResponse<ChatHistoryResponse[]>>(`/chat-rooms/${roomId}/histories`)
