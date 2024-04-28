package ir.ramtung.tinyme.domain.entity;

import ir.ramtung.tinyme.messaging.exception.InvalidRequestException;
import ir.ramtung.tinyme.messaging.request.DeleteOrderRq;
import ir.ramtung.tinyme.messaging.request.EnterOrderRq;
import ir.ramtung.tinyme.domain.service.Matcher;
import ir.ramtung.tinyme.messaging.Message;
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

    public LinkedList<MatchResult> newOrder(EnterOrderRq enterOrderRq, Broker broker, Shareholder shareholder, Matcher matcher) {
        LinkedList<MatchResult> matchResults = new LinkedList<>();
        if (enterOrderRq.getSide() == Side.SELL &&
                !shareholder.hasEnoughPositionsOn(this,
                        orderBook.totalSellQuantityByShareholder(shareholder) + enterOrderRq.getQuantity())) {
            matchResults.add(MatchResult.notEnoughPositions());
            return matchResults;
        }
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
        matcher.clearMatchResults();
        matchResults.addAll(matcher.execute(order));
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

    public LinkedList<MatchResult> updateOrder(EnterOrderRq updateOrderRq, Matcher matcher) throws InvalidRequestException {
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
                    matcher.clearMatchResults();
                    matchResults.addAll(matcher.execute(order));
                    return matchResults;
                } else {
                    orderBook.enqueue(order);
                    return new LinkedList<>();
                }
            }
        }
        if (updateOrderRq.getSide() == Side.SELL &&
                !order.getShareholder().hasEnoughPositionsOn(this,
                        orderBook.totalSellQuantityByShareholder(order.getShareholder()) - order.getQuantity() + updateOrderRq.getQuantity())) {
            matchResults.add(MatchResult.notEnoughPositions());
            return matchResults;
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
        matcher.clearMatchResults();
        matchResults.addAll(matcher.execute(order));
        if (matchResults.getFirst().outcome() != MatchingOutcome.EXECUTED) {
            orderBook.enqueue(originalOrder);
            if (updateOrderRq.getSide() == Side.BUY) {
                originalOrder.getBroker().decreaseCreditBy(originalOrder.getValue());
            }
        }
        return matchResults;
    }
}
