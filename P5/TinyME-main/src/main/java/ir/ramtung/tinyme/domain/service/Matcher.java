package ir.ramtung.tinyme.domain.service;

import ir.ramtung.tinyme.domain.entity.*;
import org.springframework.stereotype.Service;

import java.util.LinkedList;
import java.util.ListIterator;

@Service
public class Matcher {
    public MatchResult match(Order newOrder) {
        OrderBook orderBook = newOrder.getSecurity().getOrderBook();
        LinkedList<Trade> trades = new LinkedList<>();

        while (orderBook.hasOrderOfType(newOrder.getSide().opposite()) && newOrder.getQuantity() > 0) {
            Order matchingOrder = orderBook.matchWithFirst(newOrder);
            if (matchingOrder == null)
                break;

            Trade trade = new Trade(newOrder.getSecurity(), matchingOrder.getPrice(), Math.min(newOrder.getQuantity(), matchingOrder.getQuantity()), newOrder, matchingOrder);
            if (newOrder.getSide() == Side.BUY) {
                if (trade.buyerHasEnoughCredit())
                    trade.decreaseBuyersCredit();
                else {
                    buyerRollbackTrades(newOrder, trades);
                    return MatchResult.notEnoughCredit();
                }
            }
            trade.increaseSellersCredit();
            trades.add(trade);

            if (newOrder.getQuantity() >= matchingOrder.getQuantity()) {
                newOrder.decreaseQuantity(matchingOrder.getQuantity());
                orderBook.removeFirst(matchingOrder.getSide());
                if (matchingOrder instanceof IcebergOrder icebergOrder) {
                    icebergOrder.decreaseQuantity(matchingOrder.getQuantity());
                    icebergOrder.replenish();
                    if (icebergOrder.getQuantity() > 0)
                        orderBook.enqueue(icebergOrder);
                }
            } else {
                matchingOrder.decreaseQuantity(newOrder.getQuantity());
                newOrder.makeQuantityZero();
            }
        }
        int SumOfTradesQuantities = getSumOfTradesQuantities(trades);
        return validateMinimumExecutionQuantity(SumOfTradesQuantities, newOrder, trades);
    }

    private void rollbackTrades(Order newOrder, LinkedList<Trade> trades) {
        if (newOrder.getSide() == Side.BUY) {
            buyerRollbackTrades(newOrder, trades);
        } else {
            sellerRollbackTrades(newOrder, trades);
        }
    }

    private void buyerRollbackTrades(Order newOrder, LinkedList<Trade> trades) {
        assert newOrder.getSide() == Side.BUY;
        newOrder.getBroker().increaseCreditBy(trades.stream().mapToLong(Trade::getTradedValue).sum());
        trades.forEach(trade -> trade.getSell().getBroker().decreaseCreditBy(trade.getTradedValue()));

        ListIterator<Trade> it = trades.listIterator(trades.size());
        while (it.hasPrevious()) {
            newOrder.getSecurity().getOrderBook().restoreSellOrder(it.previous().getSell());
        }
    }

    private void sellerRollbackTrades(Order newOrder, LinkedList<Trade> trades) {
        assert newOrder.getSide() == Side.SELL;
        newOrder.getBroker().decreaseCreditBy(trades.stream().mapToLong(Trade::getTradedValue).sum());
        trades.forEach(trade -> trade.getBuy().getBroker().increaseCreditBy(trade.getTradedValue()));

        ListIterator<Trade> it = trades.listIterator(trades.size());
        while (it.hasPrevious()) {
            newOrder.getSecurity().getOrderBook().restoreBuyOrder(it.previous().getBuy());
        }
    }

    public LinkedList<MatchResult> matchActivatedStopLimitOrder(Order order) {
        for (Order activatedOrder : Order.getSecurity().getOrderBook().getActiveQueue()) {
//hhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhh
        }
    }

    public void activateStopLimitOrders(Order order) {
        for (Order inactiveOrder : Order.getSecurity().getOrderBook().getInactiveBuyQueue()) {
            if (inactiveOrder.getStopPrice() <= order.getSecurity().getLastTradePrice()) {
                inactiveOrder.activate();
                order.getSecurity().getOrderBook().enqueue(inactiveOrder);
            }
        }
        for (Order inactiveOrder : Order.getSecurity().getOrderBook().getInactiveSellQueue()) {
            if (inactiveOrder.getStopPrice() >= order.getSecurity().getLastTradePrice()) {
                inactiveOrder.activate();
                order.getSecurity().getOrderBook().enqueue(inactiveOrder);
            }
        }
    }

    public LinkedList<MatchResult> execute(Order order) {
        MatchResult result;
        LinkedList <MatchResult> matchResults;
        if (order.getStopPrice != 0 && order.getSide == Side.BUY && order.getStopPrice() >= order.getSecurity().getLastTradePrice()) {
            order.activate();
            result = match(order);
            matchResults.add(result);
        }
        else if (order.getStopPrice != 0 && order.getSide == Side.SELL && order.getStopPrice() <= order.getSecurity().getLastTradePrice()) {
            order.activate();
            result = match(order);
            matchResults.add(result);
        }
//        result = match(order);
        if (result.outcome() == MatchingOutcome.NOT_ENOUGH_CREDIT) {
            result = match(order);
            matchResults.add(result);
        }

        if (result.remainder().getQuantity() > 0) {
            if (order.getSide() == Side.BUY) {
                if (!order.getBroker().hasEnoughCredit(order.getValue())) {
                    buyerRollbackTrades(order, result.trades());
                    return MatchResult.notEnoughCredit();
                }
                order.getBroker().decreaseCreditBy(order.getValue());
            }
            order.getSecurity().getOrderBook().enqueue(result.remainder());
        }
        if (!result.trades().isEmpty()) {
            for (Trade trade : result.trades()) {
                trade.getBuy().getShareholder().incPosition(trade.getSecurity(), trade.getQuantity());
                trade.getSell().getShareholder().decPosition(trade.getSecurity(), trade.getQuantity());
                order.getSecurity().updateLastTradePrice(trade.getPrice());
            }
        }
        return result;
    }

    private int getSumOfTradesQuantities(LinkedList<Trade> trades) {
        return trades.stream()
                .mapToInt(Trade::getQuantity)
                .sum();
    }

    private MatchResult validateMinimumExecutionQuantity(int SumOfTradesQuantities, Order newOrder, LinkedList<Trade> trades) {
        if (newOrder.getMinimumExecutionQuantity() > SumOfTradesQuantities) {
            rollbackTrades(newOrder, trades);
            return MatchResult.notEnoughQuantitiesTraded();
        } else {
            return MatchResult.executed(newOrder, trades);
        }
    }
}
