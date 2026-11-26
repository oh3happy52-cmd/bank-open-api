// [무엇] 로그인 화면 (/login)
// [역할] 이메일·비밀번호로 로그인 API를 부른다
//        - 성공 → 받은 accessToken을 sessionStorage에 저장하고 /me 로 이동
//        - 401 → "이메일 또는 비밀번호가 올바르지 않습니다" (어느 쪽이 틀렸는지 알려 주지 않음 — 서버와 같은 원칙)
//        - 400 → 서버가 준 메시지 (예: 빈 칸)
//        - 서버 꺼짐 → "서버에 연결할 수 없습니다"
//        - 회원가입 화면에서 넘어왔으면 "가입 완료" 안내를 보여 주고 이메일을 미리 채워 둔다
// [누가 쓰나] App.jsx의 라우팅 표

import { useState } from 'react'
import { Link, useLocation, useNavigate } from 'react-router-dom'
import { login, NETWORK_ERROR } from '../api.js'
import { saveToken } from '../token.js'

export default function LoginPage() {
  const navigate = useNavigate()
  // 이전 화면이 navigate('/login', { state })로 넘겨준 값 (없으면 null)
  const location = useLocation()
  const notice = location.state?.notice ?? ''

  const [email, setEmail] = useState(location.state?.email ?? '')
  const [password, setPassword] = useState('')
  const [error, setError] = useState('')
  const [submitting, setSubmitting] = useState(false)

  async function handleSubmit(event) {
    event.preventDefault()
    if (submitting) return

    setSubmitting(true)
    setError('')
    try {
      const result = await login({ email, password })
      saveToken(result.accessToken) // 토큰은 저장만 하고 화면·콘솔에 출력하지 않는다
      navigate('/me', { replace: true }) // 뒤로 가기로 로그인 화면에 돌아오지 않게
    } catch (e) {
      if (e.status === 401) {
        setError('이메일 또는 비밀번호가 올바르지 않습니다')
      } else if (e.status === 400) {
        setError(e.message)
      } else if (e.status === NETWORK_ERROR) {
        setError('서버에 연결할 수 없습니다')
      } else {
        setError('잠시 후 다시 시도해 주세요')
      }
      setSubmitting(false)
    }
  }

  return (
    <section className="card">
      <h2>로그인</h2>
      {notice && !error && <p className="notice" role="status">{notice}</p>}
      <form onSubmit={handleSubmit} noValidate>
        <label>
          이메일
          <input type="email" value={email} onChange={(e) => setEmail(e.target.value)}
                 autoComplete="username" />
        </label>
        <label>
          비밀번호
          <input type="password" value={password} onChange={(e) => setPassword(e.target.value)}
                 autoComplete="current-password" />
        </label>

        {error && <p className="error" role="alert">{error}</p>}

        <button type="submit" disabled={submitting}>
          {submitting ? '로그인 중…' : '로그인'}
        </button>
      </form>
      <p className="hint">
        계정이 없나요? <Link to="/signup">회원가입</Link>
      </p>
    </section>
  )
}
