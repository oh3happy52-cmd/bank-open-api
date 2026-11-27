// [무엇] 주소 → 화면 연결표 (라우팅)
// [역할] 주소창 경로에 따라 어떤 화면(페이지 컴포넌트)을 보여 줄지 정한다
//          /signup → 회원가입,  /login → 로그인        (로그인 없이)
//          /apps → 내 앱,  /apps/:appId → 앱 상세·API 키,  /me → 내 정보   (RequireAuth 안 = 로그인 필요)
//          / 또는 모르는 주소 → /apps 로 보냄 (토큰이 없으면 RequireAuth가 다시 /login 으로 보낸다)
//        주소가 바뀌어도 서버에서 새 HTML을 받지 않고, React가 화면만 바꿔 그린다
// [누가 쓰나] main.jsx
//
// RequireAuth를 "감싸는 Route"로 두면 안쪽 화면들이 모두 같은 보호 규칙과 위쪽 메뉴를 공유한다

import { Navigate, Route, Routes } from 'react-router-dom'
import RequireAuth from './components/RequireAuth.jsx'
import SignupPage from './pages/SignupPage.jsx'
import LoginPage from './pages/LoginPage.jsx'
import MePage from './pages/MePage.jsx'
import AppsPage from './pages/AppsPage.jsx'
import AppDetailPage from './pages/AppDetailPage.jsx'

export default function App() {
  return (
    <main className="container">
      <h1 className="brand">Bank Open API 개발자 포털</h1>
      <Routes>
        <Route path="/signup" element={<SignupPage />} />
        <Route path="/login" element={<LoginPage />} />
        <Route element={<RequireAuth />}>
          <Route path="/apps" element={<AppsPage />} />
          <Route path="/apps/:appId" element={<AppDetailPage />} />
          <Route path="/me" element={<MePage />} />
        </Route>
        {/* replace: 뒤로 가기를 눌렀을 때 이 중간 주소로 되돌아오지 않게 기록을 바꿔치기 */}
        <Route path="*" element={<Navigate to="/apps" replace />} />
      </Routes>
    </main>
  )
}
