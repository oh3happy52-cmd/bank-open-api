// [무엇] 내 정보 화면 (/me)
// [역할] GET /api/v1/developers/me 로 개발자 정보(번호, 이메일, 이름, 가입일)를 보여 준다
//        - 토큰 확인·401 처리·로그아웃은 RequireAuth(보호 컴포넌트와 위쪽 메뉴)가 맡는다
//        - 서버 꺼짐 → "서버에 연결할 수 없습니다" (+ 다시 시도)
// [누가 쓰나] App.jsx (RequireAuth 안)
//
// ── React 기초: useEffect ──
//   화면이 처음 그려진 "뒤에" 할 일(서버에서 내 정보 가져오기)을 적는 곳.
//   [reloadCount] 처럼 뒤에 적은 값이 바뀔 때마다 다시 실행된다 (다시 시도 버튼이 이 값을 1 올림)
//   개발 서버의 StrictMode에서는 일부러 두 번 실행되므로, 먼저 실행된 쪽의 결과는 ignore로 버린다

import { useEffect, useState } from 'react'
import { fetchMe } from '../api.js'
import { useAuth } from '../components/RequireAuth.jsx'
import { commonErrorMessage, formatDateTime } from '../format.js'

export default function MePage() {
  const { token, handleAuthError } = useAuth()
  const [me, setMe] = useState(null)
  const [error, setError] = useState('')
  const [reloadCount, setReloadCount] = useState(0)

  useEffect(() => {
    let ignore = false
    setError('')
    fetchMe(token)
      .then((data) => {
        if (!ignore) setMe(data)
      })
      .catch((e) => {
        if (ignore || handleAuthError(e)) return
        setError(e.status === 0 ? commonErrorMessage(e) : '정보를 불러오지 못했습니다. 잠시 후 다시 시도해 주세요')
      })
    return () => {
      ignore = true
    }
  }, [token, handleAuthError, reloadCount])

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
    </section>
  )
}
