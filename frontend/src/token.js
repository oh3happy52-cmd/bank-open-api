// [무엇] 로그인 토큰(JWT) 보관 도우미
// [역할] 로그인 때 받은 accessToken을 브라우저의 sessionStorage에 저장·조회·삭제한다
//        - sessionStorage: 이 탭을 닫으면 지워진다 (localStorage보다 오래 남지 않음)
//        - 토큰 원문은 절대 console.log로 찍지 않는다 (개발자 도구·로그 수집에 남지 않게)
// [누가 쓰나] LoginPage(저장), MePage(조회·삭제)
//
// 주의: sessionStorage는 같은 페이지의 자바스크립트라면 읽을 수 있다.
//       그래서 외부 스크립트를 함부로 넣지 않는 것이 중요하다. (더 안전한 방법은 HttpOnly 쿠키 — 지금 범위 밖)

const KEY = 'accessToken'

export function saveToken(token) {
  try {
    sessionStorage.setItem(KEY, token)
  } catch {
    // 사생활 보호 모드 등에서 저장이 막힐 수 있다. 그때는 저장하지 않고 넘어간다(다음 화면에서 다시 로그인)
  }
}

export function getToken() {
  try {
    return sessionStorage.getItem(KEY)
  } catch {
    return null
  }
}

export function clearToken() {
  try {
    sessionStorage.removeItem(KEY)
  } catch {
    // 지울 수 없으면 할 수 있는 일이 없다
  }
}
