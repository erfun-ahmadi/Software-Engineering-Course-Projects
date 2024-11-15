package ir.ramtung.tinyme.domain.service;

import ir.ramtung.tinyme.domain.entity.*;
import org.springframework.stereotype.Service;

import java.util.LinkedList;
import java.util.ListIterator;

@Service
public class Matcher {
    private LinkedList <MatchResult> matchResults=new LinkedList<>();

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

    public LinkedList<MatchResult> execute(Order order) {
        MatchResult result = match(order);

        if (result.outcome() == MatchingOutcome.NOT_ENOUGH_CREDIT) {
            matchResults.add(result);
            return matchResults;
        }

        if (result.remainder().getQuantity() > 0) {
            if (order.getSide() == Side.BUY) {
                if (!order.getBroker().hasEnoughCredit(order.getValue())) {
                    buyerRollbackTrades(order, result.trades());
                    matchResults.add(MatchResult.notEnoughCredit());
                    return matchResults;
                }
                order.getBroker().decreaseCreditBy(order.getValue());
            }
            order.getSecurity().getOrderBook().matcherEnqueue(result.remainder());
        }
        if (!result.trades().isEmpty()) {
            for (Trade trade : result.trades()) {
                trade.getBuy().getShareholder().incPosition(trade.getSecurity(), trade.getQuantity());
                trade.getSell().getShareholder().decPosition(trade.getSecurity(), trade.getQuantity());
            }
        }
        matchResults.add(result);
        activator(result);
        executeActivates(result);
        return matchResults;
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

    public void clearMatchResults(){
        matchResults.clear();
    }

    private void activator(MatchResult lastResult){
        if(!lastResult.trades().isEmpty()) {
            int lastPrice = lastResult.trades().getLast().getPrice();
            lastResult.remainder().getSecurity().setLastTradePrice(lastPrice);
            lastResult.remainder().getSecurity().getOrderBook().activateOrder();
        }
    }

    private void executeActivates(MatchResult result){
        var it = result.remainder().getSecurity().getOrderBook().getActiveQueue().listIterator();
        if (it.hasNext()) {
            Order order = it.next();
            result.remainder().getSecurity().getOrderBook().removeByOrderId(order.getSide(), order.getOrderId(), order.getStopPrice(), order.isInactive());
            execute(order);
        }
    }
}
