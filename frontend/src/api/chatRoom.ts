import api from './axios'
import type { ApiResponse, ChatRoomResponse } from '@/types'

export const createChatRoom = (title: string) =>
  api.post<ApiResponse<ChatRoomResponse>>('/chat-rooms', { title })

export const getChatRooms = () =>
  api.get<ApiResponse<ChatRoomResponse[]>>('/chat-rooms')

export const deleteChatRoom = (roomId: number) =>
  api.delete<void>(`/chat-rooms/${roomId}`)
