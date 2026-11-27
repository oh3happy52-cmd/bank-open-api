// [무엇] 로그인이 필요한 화면을 감싸는 보호 컴포넌트 (+ 위쪽 메뉴)
// [역할] ① 토큰이 없으면 화면을 그리지 않고 바로 /login 으로 보낸다
//        ② 위쪽 메뉴(내 앱 / 내 정보 / 로그아웃)를 그리고, 그 아래에 현재 주소의 화면(<Outlet />)을 그린다
//        ③ 안쪽 화면들이 쓸 도구를 useAuth()로 나눠 준다
//           - token: 지금 토큰
//           - handleAuthError(e): 서버가 401(토큰 만료·위조)을 주면 토큰을 지우고 /login 으로 보냄 → true를 돌려줌
//           - logout(): 토큰 삭제 후 /login
// [누가 쓰나] App.jsx — /apps, /apps/:appId, /me 를 이 컴포넌트 안에 넣는다
//
// ── React 기초: Context ──
//   props로 한 단계씩 넘기지 않고, 안쪽 어디서든 useAuth()로 같은 값을 꺼내 쓰게 해 주는 통로

import { createContext, useCallback, useContext, useMemo } from 'react'
import { Navigate, NavLink, Outlet, useNavigate } from 'react-router-dom'
import { clearToken, getToken } from '../token.js'

const AuthContext = createContext(null)

// 안쪽 화면에서 부르는 함수
export function useAuth() {
  const value = useContext(AuthContext)
  if (!value) throw new Error('useAuth는 RequireAuth 안에서만 쓸 수 있습니다')
  return value
}

export default function RequireAuth() {
  const navigate = useNavigate()
  const token = getToken()

  const logout = useCallback(() => {
    clearToken()
    navigate('/login', { replace: true })
  }, [navigate])

  const handleAuthError = useCallback((error) => {
    if (error?.status === 401) {
      clearToken()
      navigate('/login', { replace: true, state: { notice: '로그인이 필요합니다. 다시 로그인해 주세요.' } })
      return true
    }
    return false
  }, [navigate])

  const value = useMemo(() => ({ token, logout, handleAuthError }), [token, logout, handleAuthError])

  if (!token) {
    return <Navigate to="/login" replace />
  }

  return (
    <AuthContext.Provider value={value}>
      <nav className="topnav">
        {/* NavLink: 지금 주소와 같으면 class="active"가 붙어 굵게 보인다 */}
        <NavLink to="/apps">내 앱</NavLink>
        <NavLink to="/me">내 정보</NavLink>
        <button type="button" className="link-button" onClick={logout}>로그아웃</button>
      </nav>
      <Outlet />
    </AuthContext.Provider>
  )
}
