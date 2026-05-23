import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'
import path from 'path'

// [설계] @tailwindcss/vite 플러그인: postcss 설정 파일 불필요, Vite 빌드 파이프라인과 통합
// [설계] /api, /ws 프록시: 개발 시 CORS 우회 목적
//   운영 환경에서는 Nginx reverse proxy 또는 Spring Boot CORS 설정으로 대체
export default defineConfig({
  plugins: [
    react(),
    tailwindcss(),
  ],
  resolve: {
    alias: {
      // [설계] '@/' alias: 컴포넌트 이동 시 상대 경로 ('../../../') 깨짐 방지
      '@': path.resolve(__dirname, './src'),
    },
  },
  server: {
    proxy: {
      '/api': 'http://localhost:8080',
      '/ws': {
        target: 'ws://localhost:8080',
        ws: true,
      },
    },
  },
})
