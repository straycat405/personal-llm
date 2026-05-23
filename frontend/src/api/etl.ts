import api from './axios'

// [역할] RAG ETL 파이프라인 API — 문서 벡터 인덱싱 트리거
export const ingestUrl = (url: string) =>
  api.post('/admin/etl/url', { url })

export const ingestPdf = (file: File) => {
  const form = new FormData()
  form.append('file', file)  // 백엔드: @RequestParam("file") MultipartFile
  return api.post('/admin/etl/pdf', form, {
    headers: { 'Content-Type': 'multipart/form-data' },
  })
}
