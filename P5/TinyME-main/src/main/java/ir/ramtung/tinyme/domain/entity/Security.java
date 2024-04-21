package ir.ramtung.tinyme.domain.entity;

import ir.ramtung.tinyme.messaging.exception.InvalidRequestException;
import ir.ramtung.tinyme.messaging.request.DeleteOrderRq;
import ir.ramtung.tinyme.messaging.request.EnterOrderRq;
import ir.ramtung.tinyme.domain.service.Matcher;
import ir.ramtung.tinyme.messaging.Message;
import lombok.Builder;
import lombok.Getter;

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
    private int lastTradePrice;
    private LinkedList<MatchResult> matchResults;

    public LinkedList<MatchResult> newOrder(EnterOrderRq enterOrderRq, Broker broker, Shareholder shareholder, Matcher matcher) {
        if (enterOrderRq.getSide() == Side.SELL &&
                !shareholder.hasEnoughPositionsOn(this,
                        orderBook.totalSellQuantityByShareholder(shareholder) + enterOrderRq.getQuantity())) {
            matchResults.add(MatchResult.notEnoughPositions());
            return matchResults;
        }
        Order order;
        if (enterOrderRq.getPeakSize() == 0)
            if (enterOrderRq.getMinimumExecutionQuantity() != 0 && enterOrderRq.getStopPrice() != 0) {
                matchResults.add(MatchResult.notAbleToCreateStopLimitOrder());
                return matchResults;
            } else if(enterOrderRq.getMinimumExecutionQuantity() != 0 && enterOrderRq.getStopPrice() == 0){
                order = new Order(enterOrderRq.getOrderId(), this, enterOrderRq.getSide(),
                        enterOrderRq.getQuantity(), enterOrderRq.getPrice(), enterOrderRq.getMinimumExecutionQuantity(), broker, shareholder, enterOrderRq.getEntryTime(), enterOrderRq.getStopPrice());
            }
            else if(enterOrderRq.getMinimumExecutionQuantity() == 0 && enterOrderRq.getStopPrice() != 0) {
                if ((enterOrderRq.getSide() == Side.BUY) && (enterOrderRq.getBroker().hasEnoughCredit(enterOrderRq.getValue()))) {
                    order = new Order(enterOrderRq.getOrderId(), this, enterOrderRq.getSide(),
                            enterOrderRq.getQuantity(), enterOrderRq.getPrice(), enterOrderRq.getMinimumExecutionQuantity(), broker, shareholder, enterOrderRq.getEntryTime(), enterOrderRq.getStopPrice());
                    order.getBroker().decreaseCreditBy(order.getValue());
                } else if ((enterOrderRq.getSide() == Side.BUY) && (!enterOrderRq.getBroker().hasEnoughCredit(enterOrderRq.getValue()))) {
                    matchResults.add(MatchResult.notEnoughCredit());
                    return matchResults;
                }
                else if (enterOrderRq.getSide() == Side.SELL){
                    order = new Order(enterOrderRq.getOrderId(), this, enterOrderRq.getSide(),
                            enterOrderRq.getQuantity(), enterOrderRq.getPrice(), enterOrderRq.getMinimumExecutionQuantity(), broker, shareholder, enterOrderRq.getEntryTime(), enterOrderRq.getStopPrice());
                }
            }
        else if (enterOrderRq.getStopPrice() != 0) {
            matchResults.add(MatchResult.notAbleToCreateStopLimitOrder());
            return matchResults;
        } else {
            order = new IcebergOrder(enterOrderRq.getOrderId(), this, enterOrderRq.getSide(),
                    enterOrderRq.getQuantity(), enterOrderRq.getPrice(), enterOrderRq.getMinimumExecutionQuantity(), broker, shareholder,
                    enterOrderRq.getEntryTime(), enterOrderRq.getPeakSize(), enterOrderRq.getStopPrice());
        }
        if ((order.getStopPrice != 0 && order.getSide == Side.BUY && order.getStopPrice() >= order.getSecurity().getLastTradePrice()) ||
                (order.getStopPrice != 0 && order.getSide == Side.SELL && order.getStopPrice() <= order.getSecurity().getLastTradePrice())) {
            order.activate();
        } else if (order.getStopPrice != 0) {
            matchResults.add(MatchResult.stopLimitOrderAccepted());
            return matchResults;
        }
        LinkedList<MatchResult> results = matcher.execute(order);
        matcher.clearMatchResults();
        return results;
    }

    public void deleteOrder(DeleteOrderRq deleteOrderRq) throws InvalidRequestException {
        Order order = orderBook.findByOrderId(deleteOrderRq.getSide(), deleteOrderRq.getOrderId(), deleteOrderRq.getStopPrice(), deleteOrderRq.getInactive());
        if (order == null)
            throw new InvalidRequestException(Message.ORDER_ID_NOT_FOUND);
        if (order.getSide() == Side.BUY)
            order.getBroker().increaseCreditBy(order.getValue());
        orderBook.removeByOrderId(deleteOrderRq.getSide(), deleteOrderRq.getOrderId(), deleteOrderRq.getStopPrice(), deleteOrderRq.getInactive());
    }

    public LinkedList<MatchResult> updateOrder(EnterOrderRq updateOrderRq, Matcher matcher) throws InvalidRequestException {
        Order order = orderBook.findByOrderId(updateOrderRq.getSide(), updateOrderRq.getOrderId(), updateOrderRq.getStopPrice(), updateOrderRq.getInactive());
        if (order == null)
            throw new InvalidRequestException(Message.ORDER_ID_NOT_FOUND);
        if (order.getMinimumExecutionQuantity() != updateOrderRq.getMinimumExecutionQuantity())
            throw new InvalidRequestException(Message.CANNOT_UPDATE_MINIMUM_EXECUTION_QUANTITY);
        if ((order instanceof IcebergOrder) && updateOrderRq.getPeakSize() == 0)
            throw new InvalidRequestException(Message.INVALID_PEAK_SIZE);
        if (!(order instanceof IcebergOrder) && updateOrderRq.getPeakSize() != 0)
            throw new InvalidRequestException(Message.CANNOT_SPECIFY_PEAK_SIZE_FOR_A_NON_ICEBERG_ORDER);
        if (updateOrderRq.getStopPrice()!=0){
            if (order.getStopPrice==0 || !order.inactive){
                throw new InvalidRequestException(Message.INVALID_UPDATE_STOP_PRICE);
            }
            if(((updateOrderRq.getStopPrice() != order.getStopPrice()) || (updateOrderRq.getPrice() != order.getPrice()) || (updateOrderRq.getQuantity() != order.getQuantity())) && (order.getSide() == Side.SELL)){
                orderBook.removeByOrderId(order.getOrderId());
                order.updateInactiveOrder(updateOrderRq);
                orderBook.enqueue(order);
                return new LinkedList<>();
            }
            else if (((updateOrderRq.getStopPrice() != order.getStopPrice()) || (updateOrderRq.getPrice() != order.getPrice()) || (updateOrderRq.getQuantity() != order.getQuantity())) && (order.getSide() == Side.BUY)){
                orderBook.removeByOrderId(order.getOrderId());
                order.getBroker().increaseCreditBy(order.getValue());
                if(!order.getBroker().hasEnoughCredit(updateOrderRq.getValue())){
                    matchResults.add(MatchResult.notEnoughCredit());
                    return matchResults;
                }
                order.updateInactiveOrder(updateOrderRq);
                order.getBroker().decreaseCreditBy(order.getValue());
                orderBook.enqueue(order);
                return new LinkedList<>();
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

        orderBook.removeByOrderId(updateOrderRq.getSide(), updateOrderRq.getOrderId(), updateOrderRq.getStopPrice(), updateOrderRq.getInactive());
        LinkedList<MatchResult> matchResults = matcher.execute(order);
        matcher.clearMatchResults();
        if (matchResults.getFirst().outcome() != MatchingOutcome.EXECUTED) {
            orderBook.enqueue(originalOrder);
            if (updateOrderRq.getSide() == Side.BUY) {
                originalOrder.getBroker().decreaseCreditBy(originalOrder.getValue());
            }
        }
        return matchResults;
    }

    public void updateLastTradePrice(int lastTradePrice) {
        this.lastTradePrice = lastTradePrice;
    }

    public void setLastTradePrice(int lastPrice) {
        lastTradePrice = lastPrice;
    }
}
