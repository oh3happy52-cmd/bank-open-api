// [무엇] 화면 표시용 작은 도우미 모음
// [역할] ① 서버가 준 날짜 문자열을 보기 좋게 바꾼다
//        ② 오류(ApiError)를 사람이 읽을 문구로 바꾼다 (서버 꺼짐, 그 밖의 오류)
// [누가 쓰나] MePage, AppsPage, AppDetailPage

import { NETWORK_ERROR } from './api.js'

// "2026-11-26T15:11:38.123456" → "2026-11-26 15:11"  (없으면 "-")
export function formatDateTime(value) {
  if (!value) return '-'
  return value.slice(0, 16).replace('T', ' ')
}

// 화면별로 따로 처리하지 않은 오류의 기본 문구
export function commonErrorMessage(error) {
  if (error?.status === NETWORK_ERROR) return '서버에 연결할 수 없습니다'
  return '잠시 후 다시 시도해 주세요'
}
