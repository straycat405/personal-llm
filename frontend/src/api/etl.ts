import api from './axios'

// [역할] RAG ETL 파이프라인 API — 비동기 인덱싱 트리거 + SSE 진행률 구독 + 지식베이스 관리

// POST → 202 Accepted + { jobId } 반환
export const ingestUrl = (url: string) =>
  api.post<{ data: { jobId: string } }>('/admin/etl/url', { url })

// [설계] /etl/file → TikaDocumentReader: PDF·DOCX·XLSX·PPTX·TXT 등 멀티포맷 처리
export const ingestFile = (file: File) => {
  const form = new FormData()
  form.append('file', file)
  return api.post<{ data: { jobId: string } }>('/admin/etl/file', form, {
    headers: { 'Content-Type': 'multipart/form-data' },
  })
}

/**
 * SSE 진행률 구독
 *
 * [설계] EventSource는 커스텀 헤더 미지원 → JWT 인증 불가
 *   백엔드에서 이 엔드포인트를 permitAll 처리, UUID jobId로 접근 제어 대체
 */
export const subscribeProgress = (
  jobId: string,
  onProgress: (progress: number, message: string) => void,
  onDone: () => void,
  onError: (error: string) => void,
): EventSource => {
  const es = new EventSource(`/api/v1/admin/etl/${jobId}/progress`)

  es.addEventListener('progress', (e) => {
    const data = JSON.parse(e.data) as {
      progress: number
      message: string
      done: boolean
      error: string
    }
    onProgress(data.progress, data.message)
    if (data.done) {
      es.close()
      if (data.error) {
        onError(data.error)
      } else {
        onDone()
      }
    }
  })

  es.onerror = () => {
    es.close()
    onError('SSE 연결 오류')
  }

  return es
}

// ── 지식베이스 관리 API ───────────────────────────────────────

// [역할] 벡터 DB에 저장된 소스 목록 응답 타입
export interface EtlSourceResponse {
  source: string               // 파일명 또는 URL
  type: 'pdf' | 'web' | 'file'
  chunkCount: number           // 해당 source의 청크 수
}

// 인덱싱된 소스 목록 (source 기준 중복 제거, 청크 수 포함)
export const listSources = () =>
  api.get<{ data: EtlSourceResponse[] }>('/admin/etl/sources')

// source에 속한 모든 청크 삭제
export const deleteSource = (source: string) =>
  api.delete<{ data: { deleted: number } }>('/admin/etl/sources', {
    params: { source },  // [설계] URL 슬래시 포함 가능 → query param 전달
  })
