package com.proovy.domain.run.brain;

import static com.proovy.domain.run.brain.InternalRunContract.require;
import static com.proovy.domain.run.brain.InternalRunContract.violation;

/** page-selection-v1 정규화 좌표(표시 기준 좌상단 원점). left·top ∈ [0,1), right·bottom ∈ (0,1]. */
public record ViewerSelection(Double left, Double top, Double right, Double bottom) {

    public ViewerSelection {
        require(left, "selection.left");
        require(top, "selection.top");
        require(right, "selection.right");
        require(bottom, "selection.bottom");
        if (!(left >= 0.0 && left < 1.0) || !(top >= 0.0 && top < 1.0)
                || !(right > 0.0 && right <= 1.0) || !(bottom > 0.0 && bottom <= 1.0)) {
            throw violation("selection", "is out of range");
        }
        if (!(left < right) || !(top < bottom)) {
            throw violation("selection", "requires left < right and top < bottom");
        }
    }
}
