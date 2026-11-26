// [무엇] 백엔드 API 호출 모음 (이 앱에서 fetch를 부르는 유일한 파일)
// [역할] 화면 코드는 signup(), login(), fetchMe()만 부르고, 주소·헤더·JSON 변환·오류 처리는 여기서 한다
//        모든 주소는 /api/... (같은 주소) → Vite 프록시가 127.0.0.1:8081로 전달한다 (vite.config.js)
// [누가 쓰나] SignupPage, LoginPage, MePage
//
// 요청·응답 모양은 백엔드 코드에 맞췄다
//   회원가입  POST /api/v1/developers/signup  요청 {email, password, name}  → 201 {id, email, name, createdAt}
//                                              (SignupRequest, DeveloperResponse)
//   로그인    POST /api/v1/auth/login         요청 {email, password}        → 200 {accessToken, tokenType, expiresIn}
//                                              (AuthController.LoginRequest / LoginResponse)
//   내 정보   GET  /api/v1/developers/me      헤더 Authorization: Bearer 토큰 → 200 {id, email, name, createdAt}
//   오류는 항상 {code, message} (ErrorResponse)
//
// 실패하면 ApiError를 던진다. 화면은 status(상태 코드)와 message로 무엇을 보여 줄지 정한다.

// 서버에 연결하지 못했을 때 쓰는 표시 (HTTP 상태 코드가 아님)
export const NETWORK_ERROR = 0
const UNREACHABLE_MESSAGE = '서버에 연결할 수 없습니다'

export class ApiError extends Error {
  constructor(status, code, message) {
    super(message)
    this.status = status // 401, 409 ... 서버에 못 닿았으면 NETWORK_ERROR(0)
    this.code = code //     INVALID_CREDENTIALS 등 백엔드 ErrorCode 이름
  }
}

// 응답 본문을 JSON으로 읽는다. 본문이 없거나 JSON이 아니면 null
async function readJson(response) {
  const text = await response.text()
  if (!text) return null
  try {
    return JSON.parse(text)
  } catch {
    return null
  }
}

// 모든 요청이 거치는 공통 함수
async function request(path, { method = 'GET', body, token } = {}) {
  const headers = { Accept: 'application/json' }
  if (body !== undefined) headers['Content-Type'] = 'application/json'
  if (token) headers.Authorization = `Bearer ${token}`

  let response
  try {
    response = await fetch(path, {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
    })
  } catch {
    // fetch 자체가 실패: 개발 서버(5173)까지도 닿지 못함 (네트워크 끊김, 개발 서버 꺼짐)
    throw new ApiError(NETWORK_ERROR, 'NETWORK_ERROR', UNREACHABLE_MESSAGE)
  }

  const data = await readJson(response)
  if (response.ok) return data

  // 백엔드(8081)가 꺼져 있으면 Vite 프록시가 대신 5xx를 돌려주는데, 본문에 우리 형식({code, message})이 없다.
  // 백엔드가 직접 준 오류에는 항상 code가 있으므로, code 없는 5xx는 "서버에 연결 못 함"으로 본다
  if (!data?.code && response.status >= 500) {
    throw new ApiError(NETWORK_ERROR, 'NETWORK_ERROR', UNREACHABLE_MESSAGE)
  }
  throw new ApiError(
    response.status,
    data?.code ?? 'UNKNOWN',
    data?.message ?? '알 수 없는 오류가 발생했습니다',
  )
}

// 회원가입 → 성공 시 {id, email, name, createdAt}
export function signup({ email, password, name }) {
  return request('/api/v1/developers/signup', {
    method: 'POST',
    body: { email, password, name },
  })
}

// 로그인 → 성공 시 {accessToken, tokenType, expiresIn}
export function login({ email, password }) {
  return request('/api/v1/auth/login', {
    method: 'POST',
    body: { email, password },
  })
}

// 내 정보 → 성공 시 {id, email, name, createdAt}
export function fetchMe(token) {
  return request('/api/v1/developers/me', { token })
}
