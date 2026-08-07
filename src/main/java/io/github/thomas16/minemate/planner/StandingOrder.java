package io.github.thomas16.minemate.planner;

/**
 * The owner order a companion is still working on, and how much planning budget it has left.
 *
 * <p>This is what makes replanning bounded. A plan finishing arms a replan trigger, and without a
 * standing order to charge the call against there would be nothing to stop the companion planning
 * about its own last plan indefinitely. Server thread only, like everything else the executor
 * touches.
 */
public final class StandingOrder {

    private final String requestText;
    private final long openedAtGameTime;
    private final long epoch;
    private int replansUsed;
    private OrderContract contract;
    private OrderLedger ledger;
    private boolean repairUsed;
    private boolean awaitingCoverage;

    public StandingOrder(String requestText, long openedAtGameTime, long epoch) {
        this.requestText = requestText == null ? "" : requestText;
        this.openedAtGameTime = openedAtGameTime;
        this.epoch = epoch;
    }

    public String requestText() {
        return requestText;
    }

    public long openedAtGameTime() {
        return openedAtGameTime;
    }

    /**
     * Monotonic per-companion identity, assigned when the order opens. A model response is only
     * applicable to the order that was open when its request began; the epoch is how a completion
     * proves that, since object references cannot cross the wire and game time can collide.
     */
    public long epoch() {
        return epoch;
    }

    /** Replans spent so far; the owner's own request is not charged against the budget. */
    public int replansUsed() {
        return replansUsed;
    }

    public boolean canReplan(int max) {
        return replansUsed < max;
    }

    public void consumeReplan() {
        replansUsed++;
    }

    /** The contract the accepted first action reply declared, or null before one was accepted. */
    public OrderContract contract() {
        return contract;
    }

    /**
     * Stored only when the declaring reply is accepted. A rejected reply's contract dies with the
     * reply, so a repair reply must redeclare it and is validated as a whole, self-contained.
     */
    public void declareContract(OrderContract contract) {
        this.contract = contract;
        this.ledger = OrderLedger.seed(contract);
    }

    /** Evidence per deliverable, seeded with the contract; null before a contract is declared. */
    public OrderLedger ledger() {
        return ledger;
    }

    /** One repair per order, charged separately from the replan budget. */
    public boolean repairAvailable() {
        return !repairUsed;
    }

    public void consumeRepair() {
        repairUsed = true;
    }

    /**
     * True while the stored contract came from a contract-only reply and no covered plan has been
     * accepted yet. Nothing has executed in that window, so the full contract is still judgeable
     * and the next action reply must satisfy coverage as a whole; replans after an accepted
     * covered plan are exempt as usual.
     */
    public boolean awaitingCoverage() {
        return awaitingCoverage;
    }

    public void markAwaitingCoverage() {
        awaitingCoverage = true;
    }

    public void clearAwaitingCoverage() {
        awaitingCoverage = false;
    }

    @Override
    public String toString() {
        return "\"" + requestText + "\" (" + replansUsed + " replans)";
    }
}
