package ir.ramtung.tinyme.domain.entity;

import java.util.LinkedList;
import java.util.List;
import java.util.Objects;

public final class MatchResult {
    private final MatchingOutcome outcome;
    private final Order remainder;
    private final LinkedList<Trade> trades;
    private final String securityIsin;
    private final int openingPrice;
    private final int tradableQuantity;

    public static MatchResult executed(Order remainder, List<Trade> trades) {
        return new MatchResult(MatchingOutcome.EXECUTED, remainder, new LinkedList<>(trades));
    }

    public static MatchResult notEnoughCredit() {
        return new MatchResult(MatchingOutcome.NOT_ENOUGH_CREDIT, null, new LinkedList<>());
    }

    public static MatchResult notEnoughPositions() {
        return new MatchResult(MatchingOutcome.NOT_ENOUGH_POSITIONS, null, new LinkedList<>());
    }

    public static MatchResult noAuctionOrderMatch() {
        return new MatchResult(MatchingOutcome.NO_AUCTION_ORDERS_IN_OPPOSITE_SIDE, null, new LinkedList<>());
    }

    public static MatchResult notEnoughQuantitiesTraded() {
        return new MatchResult(MatchingOutcome.NOT_ENOUGH_QUANTITIES_TRADED, null, new LinkedList<>());
    }

    public static MatchResult invalidOrderInAuctionState() {
        return new MatchResult(MatchingOutcome.INVALID_ORDER_IN_AUCTION_STATE, null, new LinkedList<>());
    }

    public static MatchResult stopLimitOrderAccepted() {
        return new MatchResult(MatchingOutcome.STOP_LIMIT_ORDER_ACCEPTED, null, new LinkedList<>());
    }

    public static MatchResult stopLimitOrderActivated(Order activated) {
        return new MatchResult(MatchingOutcome.STOP_LIMIT_ORDER_ACTIVATED, activated, new LinkedList<>());
    }

    public static MatchResult openingPriceHasBeenSet(String securityIsin, int openingPrice, int tradableQuantity) {
        return new MatchResult(MatchingOutcome.OPENING_PRICE_BEEN_SET, null, new LinkedList<>(), securityIsin, openingPrice, tradableQuantity);
    }

    private MatchResult(MatchingOutcome outcome, Order remainder, LinkedList<Trade> trades) {
        this.outcome = outcome;
        this.remainder = remainder;
        this.trades = trades;
        this.securityIsin = "";
        this.openingPrice = 0;
        this.tradableQuantity = 0;
    }

    private MatchResult(MatchingOutcome outcome, Order remainder, LinkedList<Trade> trades, String securityIsin, int openingPrice, int tradableQuantity) {
        this.outcome = outcome;
        this.remainder = remainder;
        this.trades = trades;
        this.securityIsin = securityIsin;
        this.openingPrice = openingPrice;
        this.tradableQuantity = tradableQuantity;
    }

    public MatchingOutcome outcome() { return outcome; }

    public Order remainder() {
        return remainder;
    }

    public LinkedList<Trade> trades() {
        return trades;
    }

    public String securityIsin() { return securityIsin; }

    public int openingPrice() { return openingPrice; }

    public int tradableQuantity() { return tradableQuantity; }

    @Override
    public boolean equals(Object obj) {
        if (obj == this) return true;
        if (obj == null || obj.getClass() != this.getClass()) return false;
        var that = (MatchResult) obj;
        return Objects.equals(this.remainder, that.remainder) &&
                Objects.equals(this.trades, that.trades);
    }

    @Override
    public int hashCode() {
        return Objects.hash(remainder, trades);
    }

    @Override
    public String toString() {
        return "MatchResult[" +
                "remainder=" + remainder + ", " +
                "trades=" + trades + ']';
    }


}
