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

// 백엔드 ChatHistoryResponse와 1:1 대응
export interface ChatHistoryResponse {
  id: number
  role: 'USER' | 'ASSISTANT'  // Java enum → 대문자 그대로 직렬화
  content: string
  createdAt: string
}

// ── 모델 선택 관련 타입 ─────────────────────────────────────
// 백엔드 /api/v1/models 응답 구조와 1:1 대응

export interface ModelInfo {
  id: string          // 모델 ID (예: "claude-sonnet-4-6", "qwen3:8b")
  name: string        // 표시 이름 (예: "Claude Sonnet 4.6")
  description: string // 짧은 설명 (예: "성능·속도 균형")
}

export interface ProviderInfo {
  provider: string      // provider 키 (예: "ollama", "claude")
  providerName: string  // 표시 이름 (예: "Claude (Anthropic)")
  available: boolean    // API key 설정 여부 → false면 선택 불가
  models: ModelInfo[]
}
