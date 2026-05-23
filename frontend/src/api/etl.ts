import api from './axios'

// [역할] RAG ETL 파이프라인 API — 비동기 인덱싱 트리거 + SSE 진행률 구독

// POST → 202 Accepted + { jobId } 반환
export const ingestUrl = (url: string) =>
  api.post<{ data: { jobId: string } }>('/admin/etl/url', { url })

export const ingestPdf = (file: File) => {
  const form = new FormData()
  form.append('file', file)
  return api.post<{ data: { jobId: string } }>('/admin/etl/pdf', form, {
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
