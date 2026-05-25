import api from './axios'
import type { ApiResponse, ProviderInfo } from '@/types'

// [역할] /api/v1/models 엔드포인트 호출 — provider·모델 목록 + 가용 여부 조회
export const getModels = () =>
  api.get<ApiResponse<ProviderInfo[]>>('/models')
