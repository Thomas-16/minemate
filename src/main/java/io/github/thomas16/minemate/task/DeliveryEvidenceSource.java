package io.github.thomas16.minemate.task;

import java.util.List;

/**
 * A goal probe that measures delivery against the owner's inventory. The latched values remain
 * readable after the goal ends, because the {@link Goal} record keeps the probe alive; an executor
 * can therefore harvest evidence from any terminal path, including cancellation, where no fresh
 * outcome flows at all.
 */
public interface DeliveryEvidenceSource {

    /** One entry per deliverable this goal was asked to bring to the owner. */
    List<DeliveryEvidence> deliveryEvidence();
}
