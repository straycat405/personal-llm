import { useState } from 'react'
import { useNavigate, Link } from 'react-router-dom'
import { login } from '@/api/auth'
import { useAuthStore } from '@/stores/authStore'

export default function LoginPage() {
  const navigate = useNavigate()
  const { login: storeLogin } = useAuthStore()
  const [form, setForm] = useState({ username: '', password: '' })
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(false)

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    setError('')
    setLoading(true)
    try {
      const res = await login(form.username, form.password)
      // 백엔드 ApiResponse<LoginResponse>.data.accessToken 추출
      storeLogin(res.data.data!.accessToken)
      navigate('/chat')
    } catch (err: unknown) {
      const msg = (err as { response?: { data?: { message?: string } } })
        .response?.data?.message
      setError(msg ?? '로그인에 실패했습니다.')
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="min-h-screen flex items-center justify-center bg-gray-950">
      <div className="w-full max-w-sm bg-gray-900 rounded-xl p-8 border border-gray-800">
        <h1 className="text-2xl font-bold text-white mb-2">BTLLM</h1>
        <p className="text-gray-500 text-sm mb-6">AI 어시스턴트에 로그인하세요</p>
        <form onSubmit={handleSubmit} className="flex flex-col gap-4">
          <input
            className="bg-gray-800 text-white rounded-lg px-4 py-2.5 outline-none
                       focus:ring-2 focus:ring-violet-500 placeholder-gray-600"
            placeholder="아이디"
            autoComplete="username"
            value={form.username}
            onChange={(e) => setForm({ ...form, username: e.target.value })}
          />
          <input
            type="password"
            className="bg-gray-800 text-white rounded-lg px-4 py-2.5 outline-none
                       focus:ring-2 focus:ring-violet-500 placeholder-gray-600"
            placeholder="비밀번호"
            autoComplete="current-password"
            value={form.password}
            onChange={(e) => setForm({ ...form, password: e.target.value })}
          />
          {error && <p className="text-red-400 text-sm">{error}</p>}
          <button
            type="submit"
            disabled={loading}
            className="bg-violet-600 hover:bg-violet-500 disabled:opacity-50
                       text-white rounded-lg py-2.5 font-semibold transition"
          >
            {loading ? '로그인 중...' : '로그인'}
          </button>
        </form>
        <p className="text-gray-500 text-sm mt-4 text-center">
          계정이 없으신가요?{' '}
          <Link to="/signup" className="text-violet-400 hover:underline">회원가입</Link>
        </p>
      </div>
    </div>
  )
}
