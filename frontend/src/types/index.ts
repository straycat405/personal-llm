// 백엔드 ApiResponse<T> 래퍼와 1:1 대응
// 모든 REST 응답은 이 구조를 통해 파싱
export interface ApiResponse<T> {
  success: boolean
  code: string
  message: string
  timestamp: string
  data?: T
}

export interface LoginResponse {
  accessToken: string
}

export interface ChatRoomResponse {
  id: number
  title: string
  // [설계] conversationId: WebSocket 연결 시 백엔드에 전달하는 Spring AI 대화 맥락 키
  // 채팅방 PK(id) 대신 이 값으로 대화 이력 구분
  conversationId: string
  createdAt: string
  updatedAt: string
}
