// [무엇] 내 앱 목록 + 앱 등록 화면 (/apps) — 로그인 후 기본 화면
// [역할] ① GET /api/v1/apps 로 내 앱 목록(이름, 등록일)을 보여 준다. 없으면 "등록된 앱이 없습니다"
//        ② 앱 등록 폼(이름 필수, 설명 선택) → POST /api/v1/apps
//           - 성공: 목록을 다시 불러오고 입력칸을 비움
//           - 409 → "이미 같은 이름의 앱이 있습니다", 400 → 서버 검증 메시지
//        ③ 앱 이름을 누르면 /apps/:appId 로 이동
//        401이면 RequireAuth의 handleAuthError가 토큰을 지우고 /login 으로 보낸다
// [누가 쓰나] App.jsx (RequireAuth 안)

import { useCallback, useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { createApp, listApps } from '../api.js'
import { useAuth } from '../components/RequireAuth.jsx'
import { commonErrorMessage, formatDateTime } from '../format.js'

export default function AppsPage() {
  const { token, handleAuthError } = useAuth()

  const [apps, setApps] = useState(null) //        null = 아직 불러오는 중
  const [loadError, setLoadError] = useState('')
  const [reloadCount, setReloadCount] = useState(0)

  const [name, setName] = useState('')
  const [description, setDescription] = useState('')
  const [formError, setFormError] = useState('')
  const [notice, setNotice] = useState('')
  const [submitting, setSubmitting] = useState(false)

  const reload = useCallback(() => setReloadCount((n) => n + 1), [])

  // 목록 불러오기 (처음 + reload() 할 때마다)
  useEffect(() => {
    let ignore = false
    setLoadError('')
    listApps(token)
      .then((data) => {
        if (!ignore) setApps(data)
      })
      .catch((e) => {
        if (ignore || handleAuthError(e)) return
        setLoadError(commonErrorMessage(e))
      })
    return () => {
      ignore = true
    }
  }, [token, handleAuthError, reloadCount])

  async function handleSubmit(event) {
    event.preventDefault()
    if (submitting) return
    setSubmitting(true)
    setFormError('')
    setNotice('')
    try {
      const created = await createApp(token, { name, description })
      setName('')
      setDescription('')
      setNotice(`"${created.name}" 앱을 등록했습니다`)
      reload()
    } catch (e) {
      if (handleAuthError(e)) return
      if (e.status === 409) setFormError('이미 같은 이름의 앱이 있습니다')
      else if (e.status === 400) setFormError(e.message)
      else setFormError(commonErrorMessage(e))
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <>
      <section className="card">
        <h2>내 앱</h2>
        {loadError && (
          <>
            <p className="error" role="alert">{loadError}</p>
            <button type="button" onClick={reload}>다시 시도</button>
          </>
        )}
        {!loadError && apps === null && <p className="hint">불러오는 중…</p>}
        {apps && apps.length === 0 && <p className="hint">등록된 앱이 없습니다</p>}
        {apps && apps.length > 0 && (
          <ul className="item-list">
            {apps.map((app) => (
              <li key={app.id}>
                <Link to={`/apps/${app.id}`} className="item-title">{app.name}</Link>
                <span className="item-meta">등록일 {formatDateTime(app.createdAt)}</span>
                {app.description && <span className="item-desc">{app.description}</span>}
              </li>
            ))}
          </ul>
        )}
      </section>

      <section className="card">
        <h2>앱 등록</h2>
        {notice && !formError && <p className="notice" role="status">{notice}</p>}
        <form onSubmit={handleSubmit} noValidate>
          <label>
            앱 이름 <small>(필수, 50자 이하)</small>
            <input type="text" value={name} onChange={(e) => setName(e.target.value)} />
          </label>
          <label>
            설명 <small>(선택, 200자 이하)</small>
            <input type="text" value={description} onChange={(e) => setDescription(e.target.value)} />
          </label>
          {formError && <p className="error" role="alert">{formError}</p>}
          <button type="submit" disabled={submitting}>{submitting ? '등록 중…' : '앱 등록'}</button>
        </form>
      </section>
    </>
  )
}
