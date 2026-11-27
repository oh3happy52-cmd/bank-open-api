// [무엇] 화면 안에 띄우는 상자(모달)
// [역할] 배경을 어둡게 덮고 가운데에 제목·내용·버튼을 보여 준다. Esc 키나 배경 클릭으로 닫힌다(onClose)
//        브라우저 기본 alert/confirm 대신 쓰는 이유: 모양을 맞출 수 있고, 키 원문 같은 내용을 안전하게 보여 줄 수 있음
// [누가 쓰나] AppDetailPage (발급한 키 보여 주기, 폐기 확인)
//
// 접근성: role="dialog", aria-modal, aria-labelledby(제목) → 화면 낭독기가 "대화 상자"로 읽는다

import { useEffect, useId } from 'react'

export default function Modal({ title, onClose, children, closeOnBackdrop = true }) {
  const titleId = useId()

  // Esc 키로 닫기 (모달이 떠 있는 동안만 키 입력을 듣는다)
  useEffect(() => {
    function onKeyDown(event) {
      if (event.key === 'Escape') onClose()
    }
    window.addEventListener('keydown', onKeyDown)
    return () => window.removeEventListener('keydown', onKeyDown)
  }, [onClose])

  return (
    <div className="modal-backdrop" onClick={closeOnBackdrop ? onClose : undefined}>
      {/* 상자 안을 눌렀을 때는 배경 클릭으로 번지지 않게 막는다 */}
      <div className="modal" role="dialog" aria-modal="true" aria-labelledby={titleId}
           onClick={(e) => e.stopPropagation()}>
        <h3 id={titleId}>{title}</h3>
        {children}
      </div>
    </div>
  )
}
