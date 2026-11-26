// [무엇] 내 정보 화면 (/me)
// [역할] 저장된 토큰으로 GET /api/v1/developers/me 를 불러 개발자 정보(번호, 이메일, 이름, 가입일)를 보여 준다
//        - 토큰이 없거나, 서버가 401(만료·위조)을 주면 → 토큰을 지우고 /login 으로 이동
//        - 서버 꺼짐 → "서버에 연결할 수 없습니다" (+ 다시 시도 버튼). 이때는 토큰을 지우지 않는다
//        - 로그아웃 버튼 → 토큰 삭제 후 /login
// [누가 쓰나] App.jsx의 라우팅 표
//
// ── React 기초: useEffect ──
//   화면이 처음 그려진 "뒤에" 할 일(여기서는 서버에서 내 정보 가져오기)을 적는 곳.
//   [reloadCount] 처럼 뒤에 적은 값이 바뀔 때마다 다시 실행된다 (다시 시도 버튼이 이 값을 1 올림)
//   개발 서버의 StrictMode에서는 일부러 두 번 실행되므로, 먼저 실행된 쪽의 결과는 ignore로 버린다

import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { fetchMe, NETWORK_ERROR } from '../api.js'
import { clearToken, getToken } from '../token.js'

// "2026-11-26T15:11:38.123456" → "2026-11-26 15:11"
function formatDateTime(value) {
  if (!value) return '-'
  return value.slice(0, 16).replace('T', ' ')
}

export default function MePage() {
  const navigate = useNavigate()
  const [me, setMe] = useState(null) //        서버에서 받은 내 정보
  const [error, setError] = useState('')
  const [reloadCount, setReloadCount] = useState(0)

  useEffect(() => {
    let ignore = false // 이 화면을 떠났거나 다시 실행되면 true → 늦게 온 응답으로 화면을 바꾸지 않음

    const token = getToken()
    if (!token) {
      navigate('/login', { replace: true })
      return
    }

    setError('')
    fetchMe(token)
      .then((data) => {
        if (!ignore) setMe(data)
      })
      .catch((e) => {
        if (ignore) return
        if (e.status === 401) {
          // 토큰이 만료됐거나 올바르지 않음 → 지우고 다시 로그인
          clearToken()
          navigate('/login', { replace: true })
        } else if (e.status === NETWORK_ERROR) {
          setError('서버에 연결할 수 없습니다')
        } else {
          setError('정보를 불러오지 못했습니다. 잠시 후 다시 시도해 주세요')
        }
      })

    return () => {
      ignore = true
    }
  }, [navigate, reloadCount])

  function handleLogout() {
    clearToken()
    navigate('/login', { replace: true })
  }

  return (
    <section className="card">
      <h2>내 정보</h2>

      {error && (
        <>
          <p className="error" role="alert">{error}</p>
          <button type="button" onClick={() => setReloadCount((n) => n + 1)}>다시 시도</button>
        </>
      )}

      {!error && !me && <p className="hint">불러오는 중…</p>}

      {me && (
        <dl className="info">
          <dt>개발자 번호</dt>
          <dd>{me.id}</dd>
          <dt>이메일</dt>
          <dd>{me.email}</dd>
          <dt>이름</dt>
          <dd>{me.name}</dd>
          <dt>가입일</dt>
          <dd>{formatDateTime(me.createdAt)}</dd>
        </dl>
      )}

      <button type="button" className="secondary" onClick={handleLogout}>로그아웃</button>
    </section>
  )
}
