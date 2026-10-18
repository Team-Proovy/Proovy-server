package com.proovy.domain.run.coordinator;

import com.proovy.domain.run.entity.AiRun;
import com.proovy.domain.run.snapshot.RunAssetAccess;

/**
 * 전달 하나에만 쓰는 Asset Access Grant·read URL 발급(run-snapshot-v1 R7). 값은 저장하지 않고 로그에 남기지 않는다.
 * <p>
 * S09/S10 전에는 발급처가 없어 기본 구현이 {@link RunAssetAccess#NONE}을 준다. 접수(1.78)가 현재 turn 첨부를 거부하므로
 * 복원이 실패하지 않고, Viewer Focus {@code read_url}·corpus grant는 wire에서 optional이다. resolver·signed URL이 생기면
 * S09/S10이 이 bean을 교체하고, 전달마다 새로 발급한다(재claim·재전달 때 이전 값을 재사용하지 않는다).
 */
@FunctionalInterface
public interface RunAssetAccessProvider {

    RunAssetAccess accessFor(AiRun run, ClaimedRun claim);
}
