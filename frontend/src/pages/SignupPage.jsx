// [무엇] 회원가입 화면 (/signup)
// [역할] 이메일·비밀번호·이름을 입력받아 회원가입 API를 부른다
//        - 201 성공 → "가입 완료" 안내와 함께 /login 으로 이동 (로그인 화면이 안내 문구를 보여 줌)
//        - 409 → "이미 가입된 이메일입니다"
//        - 400 → 서버가 준 검증 메시지 그대로 (예: "요청 값이 올바르지 않습니다 (email: 이메일 형식이 올바르지 않습니다)")
//        - 서버 꺼짐 → "서버에 연결할 수 없습니다"
//        - 요청 중에는 버튼을 비활성화해 두 번 눌러도 한 번만 보낸다
// [누가 쓰나] App.jsx의 라우팅 표
//
// ── React 기초: useState ──
//   const [email, setEmail] = useState('')  →  email은 지금 값, setEmail(새 값)을 부르면 값이 바뀌고 화면이 다시 그려진다
//   입력칸의 value={email}, onChange={e => setEmail(e.target.value)} 로 "입력칸 ↔ 변수"를 연결한다
//
// noValidate: 브라우저 자체 입력 검사(이메일 모양 등)를 끄고, 검사 규칙은 서버 한곳에서만 정한다
//             (규칙이 화면과 서버에 따로 있으면 서로 달라지기 쉽다)

import { useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { signup, NETWORK_ERROR } from '../api.js'

export default function SignupPage() {
  const navigate = useNavigate() // 코드로 다른 주소로 이동할 때 쓰는 함수

  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [name, setName] = useState('')
  const [error, setError] = useState('') //            화면에 보여 줄 오류 문구
  const [submitting, setSubmitting] = useState(false) // 요청 중인가 (버튼 비활성화용)

  async function handleSubmit(event) {
    event.preventDefault() // form의 기본 동작(페이지 새로고침)을 막는다
    if (submitting) return // 혹시 두 번 실행돼도 요청은 한 번만

    setSubmitting(true)
    setError('')
    try {
      await signup({ email, password, name })
      // 성공: 로그인 화면으로 이동하면서 "가입 완료" 안내와 이메일을 넘겨준다 (비밀번호는 넘기지 않음)
      navigate('/login', { state: { notice: '가입 완료! 로그인해 주세요.', email } })
    } catch (e) {
      if (e.status === 409) {
        setError('이미 가입된 이메일입니다')
      } else if (e.status === 400) {
        setError(e.message) // 서버 검증 메시지
      } else if (e.status === NETWORK_ERROR) {
        setError('서버에 연결할 수 없습니다')
      } else {
        setError('잠시 후 다시 시도해 주세요')
      }
      setSubmitting(false) // 실패했을 때만 다시 누를 수 있게 (성공하면 화면이 바뀌므로 그대로 둠)
    }
  }

  return (
    <section className="card">
      <h2>회원가입</h2>
      <form onSubmit={handleSubmit} noValidate>
        <label>
          이메일
          <input type="email" value={email} onChange={(e) => setEmail(e.target.value)}
                 autoComplete="email" />
        </label>
        <label>
          비밀번호 <small>(8자 이상)</small>
          <input type="password" value={password} onChange={(e) => setPassword(e.target.value)}
                 autoComplete="new-password" />
        </label>
        <label>
          이름
          <input type="text" value={name} onChange={(e) => setName(e.target.value)}
                 autoComplete="name" />
        </label>

        {/* error가 비어 있지 않을 때만 그린다. role="alert": 화면 낭독기가 바로 읽어 줌 */}
        {error && <p className="error" role="alert">{error}</p>}

        <button type="submit" disabled={submitting}>
          {submitting ? '가입 중…' : '가입하기'}
        </button>
      </form>
      <p className="hint">
        이미 계정이 있나요? <Link to="/login">로그인</Link>
      </p>
    </section>
  )
}
