// [무엇] Vite(개발 서버·빌드 도구) 설정 파일
// [역할] ① React 문법(JSX)을 브라우저가 읽을 수 있게 바꾸는 플러그인을 켠다
//        ② 개발 서버(5173)에 온 /api 요청을 백엔드(127.0.0.1:8081)로 대신 전달한다 (프록시)
// [누가 쓰나] npm run dev, npm run build 를 실행하면 Vite가 읽는다
//
// ── 프록시가 왜 필요한가? ──
//   브라우저는 화면을 받은 주소(localhost:5173)와 다른 주소(8081)로 요청하는 것을 막는다(CORS).
//   그래서 화면 코드는 같은 주소의 /api/... 로만 요청하고, Vite 개발 서버가 뒤에서 8081로 전달한다.
//   브라우저 입장에서는 같은 주소로 보냈으므로 막히지 않고, 백엔드에 CORS 설정을 넣을 필요도 없다.
//
// ── localhost 대신 127.0.0.1 ──
//   localhost는 IPv6(::1)로 먼저 갈 수도 있다. 1주차에 8080에서 IPv4/IPv6가 다른 프로그램으로 갈렸던
//   일이 있어서, 백엔드가 듣는 IPv4 주소를 정확히 적는다.

import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    strictPort: true, // 5173을 다른 프로그램이 쓰고 있으면 다른 포트로 몰래 바꾸지 않고 멈춘다
    proxy: {
      '/api': {
        target: 'http://127.0.0.1:8081',
      },
    },
  },
})
