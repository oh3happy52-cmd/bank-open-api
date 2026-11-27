// [무엇] 앱 상세 + API 키 관리 화면 (/apps/:appId)
// [역할] ① 앱 정보(이름, 설명, 등록일)와 키 목록(앞부분, 상태, 발급일, 폐기일)을 보여 준다
//        ② "키 발급" → POST .../keys → 발급된 키 원문을 화면 안 상자(모달)로 딱 한 번 보여 준다
//           - 복사 버튼, "이 창을 닫으면 다시 볼 수 없습니다" 경고
//           - 상자를 닫으면 state에서 원문을 지운다 (그 뒤로는 이 화면 어디에도 원문이 없음)
//           - 409(ACTIVE 키 2개) → "사용 중인 키는 앱당 2개까지입니다. 먼저 하나를 폐기하세요"
//        ③ "폐기" → 확인 상자 → 확인하면 DELETE .../keys/{keyId} 후 목록 새로고침
//        ④ 남의 앱·없는 앱(404) → "앱을 찾을 수 없습니다" + 목록으로 가는 링크
// [누가 쓰나] App.jsx (RequireAuth 안)
//
// ── 키 원문을 다루는 규칙 ──
//   - state(메모리)에만 잠깐 둔다. sessionStorage·localStorage·console·주소(URL)에 절대 넣지 않는다
//   - 상자를 닫거나 이 화면을 떠나면 사라진다 (React가 state를 버림)
//   - 화면에는 읽기 전용 입력칸으로 보여 줘서, 복사 버튼이 안 되는 브라우저에서도 직접 선택해 복사할 수 있다

import { useCallback, useEffect, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { getApp, issueKey, listKeys, revokeKey } from '../api.js'
import Modal from '../components/Modal.jsx'
import { useAuth } from '../components/RequireAuth.jsx'
import { commonErrorMessage, formatDateTime } from '../format.js'

export default function AppDetailPage() {
  const { appId } = useParams() // 주소의 :appId 자리 값 (문자열)
  const { token, handleAuthError } = useAuth()

  const [app, setApp] = useState(null)
  const [keys, setKeys] = useState(null)
  const [notFound, setNotFound] = useState(false)
  const [loadError, setLoadError] = useState('')
  const [reloadCount, setReloadCount] = useState(0)

  const [actionError, setActionError] = useState('')
  const [busy, setBusy] = useState(false) //            발급·폐기 요청 중 (버튼 비활성화)
  const [issued, setIssued] = useState(null) //         {keyPrefix, apiKey} — 모달이 떠 있는 동안만
  const [copied, setCopied] = useState(false)
  const [revoking, setRevoking] = useState(null) //     폐기 확인 상자에 띄울 키 {id, keyPrefix}

  const reload = useCallback(() => setReloadCount((n) => n + 1), [])

  // 앱 정보와 키 목록을 함께 불러온다
  useEffect(() => {
    let ignore = false
    setLoadError('')
    Promise.all([getApp(token, appId), listKeys(token, appId)])
      .then(([appData, keyData]) => {
        if (ignore) return
        setApp(appData)
        setKeys(keyData)
      })
      .catch((e) => {
        if (ignore || handleAuthError(e)) return
        if (e.status === 404 || e.status === 400) setNotFound(true) // 400: 주소의 appId가 숫자가 아님
        else setLoadError(commonErrorMessage(e))
      })
    return () => {
      ignore = true
    }
  }, [token, appId, handleAuthError, reloadCount])

  async function handleIssue() {
    if (busy) return
    setBusy(true)
    setActionError('')
    try {
      const result = await issueKey(token, appId)
      setCopied(false)
      setIssued({ keyPrefix: result.keyPrefix, apiKey: result.apiKey })
      reload() // 목록에 새 키(앞부분만)를 보여 준다
    } catch (e) {
      if (handleAuthError(e)) return
      if (e.status === 409) setActionError('사용 중인 키는 앱당 2개까지입니다. 먼저 하나를 폐기하세요')
      else if (e.status === 404) setNotFound(true)
      else setActionError(commonErrorMessage(e))
    } finally {
      setBusy(false)
    }
  }

  // 원문 상자 닫기: state에서 원문을 지운다
  const closeIssued = useCallback(() => {
    setIssued(null)
    setCopied(false)
  }, [])

  async function handleCopy() {
    if (!issued) return
    try {
      await navigator.clipboard.writeText(issued.apiKey)
      setCopied(true)
    } catch {
      setCopied(false) // 복사가 막힌 브라우저: 입력칸을 직접 선택해 복사하면 된다
    }
  }

  async function handleRevokeConfirm() {
    if (!revoking || busy) return
    setBusy(true)
    setActionError('')
    try {
      await revokeKey(token, appId, revoking.id)
      setRevoking(null)
      reload()
    } catch (e) {
      if (handleAuthError(e)) return
      setRevoking(null)
      if (e.status === 404) setActionError('키를 찾을 수 없습니다. 목록을 새로고침합니다')
      else setActionError(commonErrorMessage(e))
      reload()
    } finally {
      setBusy(false)
    }
  }

  const closeRevoke = useCallback(() => setRevoking(null), [])

  if (notFound) {
    return (
      <section className="card">
        <h2>앱을 찾을 수 없습니다</h2>
        <p className="hint">없는 앱이거나 다른 개발자의 앱입니다.</p>
        <Link to="/apps">← 내 앱 목록으로</Link>
      </section>
    )
  }

  if (loadError) {
    return (
      <section className="card">
        <p className="error" role="alert">{loadError}</p>
        <button type="button" onClick={reload}>다시 시도</button>
        <p className="hint"><Link to="/apps">← 내 앱 목록으로</Link></p>
      </section>
    )
  }

  if (!app || !keys) {
    return <section className="card"><p className="hint">불러오는 중…</p></section>
  }

  const activeCount = keys.filter((k) => k.status === 'ACTIVE').length

  return (
    <>
      <p className="breadcrumb"><Link to="/apps">← 내 앱 목록</Link></p>

      <section className="card">
        <h2>{app.name}</h2>
        <dl className="info">
          <dt>앱 번호</dt>
          <dd>{app.id}</dd>
          <dt>설명</dt>
          <dd>{app.description || '-'}</dd>
          <dt>등록일</dt>
          <dd>{formatDateTime(app.createdAt)}</dd>
        </dl>
      </section>

      <section className="card">
        <div className="section-head">
          <h2>API 키</h2>
          <button type="button" onClick={handleIssue} disabled={busy}>
            {busy && !revoking ? '처리 중…' : '키 발급'}
          </button>
        </div>
        <p className="hint">사용 중인 키 {activeCount} / 2개. 키 원문은 발급할 때 한 번만 볼 수 있습니다.</p>
        {actionError && <p className="error" role="alert">{actionError}</p>}

        {keys.length === 0 ? (
          <p className="hint">발급한 키가 없습니다</p>
        ) : (
          <div className="table-wrap">
            <table className="key-table">
              <thead>
                <tr>
                  <th>키 앞부분</th>
                  <th>상태</th>
                  <th>발급일</th>
                  <th>폐기일</th>
                  <th aria-label="동작" />
                </tr>
              </thead>
              <tbody>
                {keys.map((key) => (
                  <tr key={key.id}>
                    <td><code>{key.keyPrefix}…</code></td>
                    <td><span className={`badge ${key.status === 'ACTIVE' ? 'badge-active' : 'badge-revoked'}`}>{key.status}</span></td>
                    <td>{formatDateTime(key.createdAt)}</td>
                    <td>{formatDateTime(key.revokedAt)}</td>
                    <td>
                      {key.status === 'ACTIVE' && (
                        <button type="button" className="danger small" disabled={busy}
                                onClick={() => setRevoking({ id: key.id, keyPrefix: key.keyPrefix })}>
                          폐기
                        </button>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>

      {issued && (
        // 실수로 배경을 눌러 원문을 잃지 않게, 이 상자는 배경 클릭으로 닫히지 않는다 (버튼·Esc로만)
        <Modal title="새 API 키가 발급되었습니다" onClose={closeIssued} closeOnBackdrop={false}>
          <p className="warning">⚠ 이 창을 닫으면 다시 볼 수 없습니다. 지금 안전한 곳(서버의 환경 변수 등)에 저장하세요.</p>
          <input className="secret-box" type="text" readOnly value={issued.apiKey}
                 aria-label="발급된 API 키 원문" onFocus={(e) => e.target.select()} />
          <div className="modal-actions">
            <button type="button" onClick={handleCopy}>{copied ? '복사됨 ✓' : '복사'}</button>
            <button type="button" className="secondary-inline" onClick={closeIssued}>닫기</button>
          </div>
        </Modal>
      )}

      {revoking && (
        <Modal title="이 키를 폐기할까요?" onClose={closeRevoke}>
          <p><code>{revoking.keyPrefix}…</code></p>
          <p className="warning">폐기하면 이 키로 오는 요청은 바로 401로 거절됩니다. 되돌릴 수 없습니다.</p>
          <div className="modal-actions">
            <button type="button" className="danger" onClick={handleRevokeConfirm} disabled={busy}>
              {busy ? '폐기 중…' : '폐기'}
            </button>
            <button type="button" className="secondary-inline" onClick={closeRevoke} disabled={busy}>취소</button>
          </div>
        </Modal>
      )}
    </>
  )
}
