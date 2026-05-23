import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom'
import LoginPage from '@/pages/LoginPage'
import SignupPage from '@/pages/SignupPage'
import ChatPage from '@/pages/ChatPage'

// [설계] react-router-dom v6 선택: Next.js는 SSR 기능이 불필요한 채팅 SPA에 과도함
// "/" 접근 시 "/chat"으로 리다이렉트, 인증 가드는 각 Page 컴포넌트 내부에서 처리
export default function App() {
  return (
    <BrowserRouter>
      <Routes>
        <Route path="/" element={<Navigate to="/chat" replace />} />
        <Route path="/login" element={<LoginPage />} />
        <Route path="/signup" element={<SignupPage />} />
        <Route path="/chat" element={<ChatPage />} />
      </Routes>
    </BrowserRouter>
  )
}
