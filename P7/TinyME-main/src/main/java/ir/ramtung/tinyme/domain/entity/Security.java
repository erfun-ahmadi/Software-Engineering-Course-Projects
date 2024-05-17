package ir.ramtung.tinyme.domain.entity;

import ir.ramtung.tinyme.domain.service.AuctionMatcher;
import ir.ramtung.tinyme.messaging.exception.InvalidRequestException;
import ir.ramtung.tinyme.messaging.request.ChangeMatchingStateRq;
import ir.ramtung.tinyme.messaging.request.DeleteOrderRq;
import ir.ramtung.tinyme.messaging.request.EnterOrderRq;
import ir.ramtung.tinyme.domain.service.ContinuousMatcher;
import ir.ramtung.tinyme.messaging.Message;
import ir.ramtung.tinyme.messaging.request.MatchingState;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

import java.util.LinkedList;
import java.util.List;

@Getter
@Builder
public class Security {
    private String isin;
    @Builder.Default
    private int tickSize = 1;
    @Builder.Default
    private int lotSize = 1;
    @Builder.Default
    private OrderBook orderBook = new OrderBook();
    @Setter
    private int lastTradePrice;
    @Builder.Default
    @Setter
    private MatchingState matchingState = MatchingState.CONTINUOUS;

    public LinkedList<Trade> changeState(ChangeMatchingStateRq changeMatchingStateRq, AuctionMatcher auctionMatcher) {
        LinkedList<Trade> trades = new LinkedList<>();
        if (matchingState == MatchingState.AUCTION)
            trades = auctionMatcher.execute(this);
        matchingState = changeMatchingStateRq.getTargetState();
        return trades;
    }

    public LinkedList<MatchResult> newOrder(EnterOrderRq enterOrderRq, Broker broker, Shareholder shareholder, ContinuousMatcher continuousMatcher, AuctionMatcher auctionMatcher) {
        if (enterOrderRq.getSide() == Side.SELL &&
                !shareholder.hasEnoughPositionsOn(this,
                        orderBook.totalSellQuantityByShareholder(shareholder) + enterOrderRq.getQuantity()))
            return new LinkedList<>(List.of(MatchResult.notEnoughPositions()));
        if (matchingState == MatchingState.CONTINUOUS)
            return newContinuousOrder(enterOrderRq, broker, shareholder, continuousMatcher);
        else
            return new LinkedList<>(List.of(newAuctionOrder(enterOrderRq, broker, shareholder, auctionMatcher)));
    }

    private MatchResult newAuctionOrder(EnterOrderRq enterOrderRq, Broker broker, Shareholder shareholder, AuctionMatcher auctionMatcher) {
        if (enterOrderRq.getStopPrice() != 0 || enterOrderRq.getMinimumExecutionQuantity() != 0)
            return MatchResult.invalidOrderInAuctionState();
        if (enterOrderRq.getSide() == Side.BUY && !broker.hasEnoughCredit(enterOrderRq.getValue()))
            return MatchResult.notEnoughCredit();
        Order order = new Order(enterOrderRq.getOrderId(), this, enterOrderRq.getSide(),
                enterOrderRq.getQuantity(), enterOrderRq.getPrice(), enterOrderRq.getMinimumExecutionQuantity(), broker, shareholder, enterOrderRq.getEntryTime(), OrderStatus.NEW, enterOrderRq.getStopPrice());
        if (order.getSide() == Side.BUY)
            order.getBroker().decreaseCreditBy(order.getValue());
        return auctionMatcher.updateOpeningPriceWithNewOrder(order);
    }

    private LinkedList<MatchResult> newContinuousOrder(EnterOrderRq enterOrderRq, Broker broker, Shareholder shareholder, ContinuousMatcher continuousMatcher) {
        LinkedList<MatchResult> matchResults = new LinkedList<>();
        Order order = null;
        if (enterOrderRq.getPeakSize() == 0) {
            order = new Order(enterOrderRq.getOrderId(), this, enterOrderRq.getSide(),
                    enterOrderRq.getQuantity(), enterOrderRq.getPrice(), enterOrderRq.getMinimumExecutionQuantity(), broker, shareholder, enterOrderRq.getEntryTime(), OrderStatus.NEW, enterOrderRq.getStopPrice());
            if (enterOrderRq.getStopPrice() != 0 && enterOrderRq.getSide() == Side.BUY) {
                if (broker.hasEnoughCredit(order.getValue())) {
                    order.getBroker().decreaseCreditBy(order.getValue());
                } else {
                    matchResults.add(MatchResult.notEnoughCredit());
                    return matchResults;
                }
            }
        } else {
            order = new IcebergOrder(enterOrderRq.getOrderId(), this, enterOrderRq.getSide(),
                    enterOrderRq.getQuantity(), enterOrderRq.getPrice(), enterOrderRq.getMinimumExecutionQuantity(), broker, shareholder,
                    enterOrderRq.getEntryTime(), enterOrderRq.getPeakSize(), enterOrderRq.getStopPrice());
        }
        if (order.shouldActivate()) {
            order.activate();
            matchResults.add(MatchResult.stopLimitOrderAccepted());
            matchResults.add(MatchResult.stopLimitOrderActivated(order));
            if (order.getSide() == Side.BUY)
                order.getBroker().increaseCreditBy(order.getValue());
        } else if (order.getStopPrice() != 0) {
            matchResults.add(MatchResult.stopLimitOrderAccepted());
            orderBook.enqueue(order);
            return matchResults;
        }
        continuousMatcher.clearMatchResults();
        matchResults.addAll(continuousMatcher.execute(order));
        return matchResults;
    }

    public void deleteOrder(DeleteOrderRq deleteOrderRq) throws InvalidRequestException {
        Order order = orderBook.findByOrderId(deleteOrderRq.getSide(), deleteOrderRq.getOrderId(), deleteOrderRq.isInactive());
        if (order == null)
            throw new InvalidRequestException(Message.ORDER_ID_NOT_FOUND);
        if (order.getSide() == Side.BUY)
            order.getBroker().increaseCreditBy(order.getValue());
        orderBook.removeByOrderId(deleteOrderRq.getSide(), deleteOrderRq.getOrderId(), deleteOrderRq.isInactive());
    }

    public LinkedList<MatchResult> updateOrder(EnterOrderRq updateOrderRq, ContinuousMatcher continuousMatcher, AuctionMatcher auctionMatcher) throws InvalidRequestException {
        Order order = orderBook.findByOrderId(updateOrderRq.getSide(), updateOrderRq.getOrderId(), updateOrderRq.isInactive());
        if (order == null)
            throw new InvalidRequestException(Message.ORDER_ID_NOT_FOUND);
        if (order.getMinimumExecutionQuantity() != updateOrderRq.getMinimumExecutionQuantity())
            throw new InvalidRequestException(Message.CANNOT_UPDATE_MINIMUM_EXECUTION_QUANTITY);
        if ((order instanceof IcebergOrder) && updateOrderRq.getPeakSize() == 0)
            throw new InvalidRequestException(Message.INVALID_PEAK_SIZE);
        if (!(order instanceof IcebergOrder) && updateOrderRq.getPeakSize() != 0)
            throw new InvalidRequestException(Message.CANNOT_SPECIFY_PEAK_SIZE_FOR_A_NON_ICEBERG_ORDER);
        if (order.isUpdateStopPriceInvalid(updateOrderRq))
            throw new InvalidRequestException(Message.INVALID_UPDATE_STOP_PRICE);
        if (updateOrderRq.getSide() == Side.SELL &&
                !order.getShareholder().hasEnoughPositionsOn(this,
                        orderBook.totalSellQuantityByShareholder(order.getShareholder()) - order.getQuantity() + updateOrderRq.getQuantity()))
            return new LinkedList<>(List.of(MatchResult.notEnoughPositions()));

        if (matchingState == MatchingState.CONTINUOUS)
            return updateOrderContinuous(updateOrderRq, order, continuousMatcher);
        else
            return new LinkedList<>(List.of(updateOrderAuction(updateOrderRq, order, auctionMatcher)));
    }

    public MatchResult updateOrderAuction(EnterOrderRq updateOrderRq, Order order, AuctionMatcher auctionMatcher) {
        if (order.getSide() == Side.BUY) {
            order.getBroker().increaseCreditBy(order.getValue());
            if (!order.getBroker().hasEnoughCredit(updateOrderRq.getValue()))
                return MatchResult.notEnoughCredit();
            order.getBroker().decreaseCreditBy(updateOrderRq.getValue());
        }
        order.updateFromRequest(updateOrderRq);
        orderBook.removeByOrderId(order.getSide(), order.getOrderId(), false);
        return auctionMatcher.updateOpeningPriceWithNewOrder(order);
    }

    public LinkedList<MatchResult> updateOrderContinuous(EnterOrderRq updateOrderRq, Order order, ContinuousMatcher continuousMatcher) {
        LinkedList<MatchResult> matchResults = new LinkedList<>();
        if (updateOrderRq.getStopPrice() != 0) {
            if ((updateOrderRq.getStopPrice() != order.getStopPrice()) || (updateOrderRq.getPrice() != order.getPrice()) || (updateOrderRq.getQuantity() != order.getQuantity())) {
                orderBook.removeByOrderId(order.getSide(), order.getOrderId(), order.isInactive());
                if (order.getSide() == Side.BUY) {
                    order.getBroker().increaseCreditBy(order.getValue());
                    if (!order.getBroker().hasEnoughCredit(updateOrderRq.getValue())) {
                        matchResults.add(MatchResult.notEnoughCredit());
                        return matchResults;
                    }
                    order.getBroker().decreaseCreditBy(updateOrderRq.getValue());
                }
                order.updateInactiveOrder(updateOrderRq);
                if (order.shouldActivate()) {
                    order.activate();
                    matchResults.add(MatchResult.stopLimitOrderActivated(order));
                    continuousMatcher.clearMatchResults();
                    matchResults.addAll(continuousMatcher.execute(order));
                    return matchResults;
                } else {
                    orderBook.enqueue(order);
                    return new LinkedList<>();
                }
            }
        }
        boolean losesPriority = order.isQuantityIncreased(updateOrderRq.getQuantity())
                || updateOrderRq.getPrice() != order.getPrice()
                || ((order instanceof IcebergOrder icebergOrder) && (icebergOrder.getPeakSize() < updateOrderRq.getPeakSize()));

        if (updateOrderRq.getSide() == Side.BUY) {
            order.getBroker().increaseCreditBy(order.getValue());
        }
        Order originalOrder = order.snapshot();
        order.updateFromRequest(updateOrderRq);
        if (!losesPriority) {
            if (updateOrderRq.getSide() == Side.BUY) {
                order.getBroker().decreaseCreditBy(order.getValue());
            }
            matchResults.add(MatchResult.executed(null, List.of()));
            return matchResults;
        } else
            order.markAsNew();

        orderBook.removeByOrderId(updateOrderRq.getSide(), updateOrderRq.getOrderId(), updateOrderRq.isInactive());
        continuousMatcher.clearMatchResults();
        matchResults.addAll(continuousMatcher.execute(order));
        if (matchResults.getFirst().outcome() != MatchingOutcome.EXECUTED) {
            orderBook.enqueue(originalOrder);
            if (updateOrderRq.getSide() == Side.BUY) {
                originalOrder.getBroker().decreaseCreditBy(originalOrder.getValue());
            }
        }
        return matchResults;
    }
}
