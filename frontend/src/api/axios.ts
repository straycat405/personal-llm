import axios from 'axios'

// [설계] axios 인스턴스 분리: baseURL·interceptor를 한 곳에서 관리
// 토큰 갱신·에러 처리 로직 추가 시 이 파일만 수정하면 됨
const api = axios.create({
  baseURL: '/api/v1',
  headers: { 'Content-Type': 'application/json' },
})

// [설계] 요청 interceptor로 토큰 자동 주입:
// 각 API 함수에서 헤더를 직접 설정하는 것보다 단일 지점에서 관리해 누락 방지
api.interceptors.request.use((config) => {
  const token = localStorage.getItem('accessToken')
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

export default api
