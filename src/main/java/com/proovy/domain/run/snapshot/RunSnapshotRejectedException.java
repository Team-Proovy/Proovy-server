package com.proovy.domain.run.snapshot;

import com.proovy.global.exception.BusinessException;
import com.proovy.global.response.ErrorCode;

/**
 * snapshot을 만들 수 없는 요청. RUN4041(Note 없음·타인·삭제 중) 또는 RUN4221(첨부·참조 자료를 쓸 수 없음)이다.
 * message는 code 고정 문구만 쓴다(대상 존재 여부를 드러내지 않는다).
 */
public class RunSnapshotRejectedException extends BusinessException {

    RunSnapshotRejectedException(ErrorCode errorCode) {
        super(errorCode);
    }

    static RunSnapshotRejectedException noteUnavailable() {
        return new RunSnapshotRejectedException(ErrorCode.RUN4041);
    }

    static RunSnapshotRejectedException referenceUnavailable() {
        return new RunSnapshotRejectedException(ErrorCode.RUN4221);
    }
}
