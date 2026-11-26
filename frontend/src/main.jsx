// [무엇] React 앱의 시작점
// [역할] index.html의 <div id="root">에 <App />을 그린다
//        - BrowserRouter: 주소창의 경로(/login, /me ...)를 읽어 맞는 화면을 고를 수 있게 해 준다
//        - StrictMode: 개발 중에만 실수를 찾아 주는 검사 모드 (개발 서버에서는 화면 로딩 효과가 두 번 실행될 수 있음)
// [누가 쓰나] index.html이 <script src="/src/main.jsx">로 불러온다

import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { BrowserRouter } from 'react-router-dom'
import App from './App.jsx'
import './index.css'

createRoot(document.getElementById('root')).render(
  <StrictMode>
    <BrowserRouter>
      <App />
    </BrowserRouter>
  </StrictMode>,
)
