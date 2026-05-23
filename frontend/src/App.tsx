import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom'
import LoginPage from '@/pages/LoginPage'
import SignupPage from '@/pages/SignupPage'
import ChatPage from '@/pages/ChatPage'
import { useAuthStore } from '@/stores/authStore'

// [설계] react-router-dom v6 선택: Next.js는 SSR 기능이 불필요한 채팅 SPA에 과도함
// "/" 접근 시 인증 여부에 따라 분기: 미인증 → "/login", 인증됨 → "/chat"
// useEffect 방식은 렌더 후 체크라 채팅 화면이 찰나 노출되는 문제 있음
export default function App() {
  const { isAuthenticated } = useAuthStore()

  return (
    <BrowserRouter>
      <Routes>
        <Route path="/" element={<Navigate to={isAuthenticated() ? '/chat' : '/login'} replace />} />
        <Route path="/login" element={<LoginPage />} />
        <Route path="/signup" element={<SignupPage />} />
        <Route path="/chat" element={<ChatPage />} />
      </Routes>
    </BrowserRouter>
  )
}
