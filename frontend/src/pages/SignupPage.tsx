import { useState } from 'react'
import { useNavigate, Link } from 'react-router-dom'
import { signup } from '@/api/auth'

export default function SignupPage() {
  const navigate = useNavigate()
  const [form, setForm] = useState({ username: '', password: '', email: '' })
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(false)

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    setError('')
    setLoading(true)
    try {
      await signup(form.username, form.password, form.email)
      // 가입 성공 후 로그인 페이지로 이동 (자동 로그인 미적용: 명시적 로그인 유도)
      navigate('/login')
    } catch (err: unknown) {
      const msg = (err as { response?: { data?: { message?: string } } })
        .response?.data?.message
      setError(msg ?? '회원가입에 실패했습니다.')
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="min-h-screen flex items-center justify-center bg-gray-950">
      <div className="w-full max-w-sm bg-gray-900 rounded-xl p-8 border border-gray-800">
        <h1 className="text-2xl font-bold text-white mb-6">회원가입</h1>
        <form onSubmit={handleSubmit} className="flex flex-col gap-4">
          <div>
            <input
              className="w-full bg-gray-800 text-white rounded-lg px-4 py-2.5 outline-none
                         focus:ring-2 focus:ring-violet-500 placeholder-gray-600"
              placeholder="아이디 (3~50자)"
              autoComplete="username"
              value={form.username}
              onChange={(e) => setForm({ ...form, username: e.target.value })}
            />
          </div>
          <input
            type="password"
            className="bg-gray-800 text-white rounded-lg px-4 py-2.5 outline-none
                       focus:ring-2 focus:ring-violet-500 placeholder-gray-600"
            placeholder="비밀번호 (8자 이상)"
            autoComplete="new-password"
            value={form.password}
            onChange={(e) => setForm({ ...form, password: e.target.value })}
          />
          <input
            type="email"
            className="bg-gray-800 text-white rounded-lg px-4 py-2.5 outline-none
                       focus:ring-2 focus:ring-violet-500 placeholder-gray-600"
            placeholder="이메일"
            autoComplete="email"
            value={form.email}
            onChange={(e) => setForm({ ...form, email: e.target.value })}
          />
          {error && <p className="text-red-400 text-sm">{error}</p>}
          <button
            type="submit"
            disabled={loading}
            className="bg-violet-600 hover:bg-violet-500 disabled:opacity-50
                       text-white rounded-lg py-2.5 font-semibold transition"
          >
            {loading ? '가입 중...' : '가입하기'}
          </button>
        </form>
        <p className="text-gray-500 text-sm mt-4 text-center">
          이미 계정이 있으신가요?{' '}
          <Link to="/login" className="text-violet-400 hover:underline">로그인</Link>
        </p>
      </div>
    </div>
  )
}
