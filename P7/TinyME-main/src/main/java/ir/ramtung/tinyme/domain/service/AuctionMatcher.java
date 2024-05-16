package ir.ramtung.tinyme.domain.service;

import ir.ramtung.tinyme.domain.entity.*;
import lombok.Getter;
import org.springframework.stereotype.Service;

import java.util.LinkedList;

@Getter
@Service
public class AuctionMatcher {
    private int openPrice;
    private int tradableQuantity;

    public MatchResult match(Order order) {
        OrderBook orderBook = order.getSecurity().getOrderBook();
        LinkedList<Trade> trades = new LinkedList<>();
        if (order.isPriceGood(openPrice)) {
            addDifferenceBetweenPriceAndOpenPriceToBrokerCredit(order);
            while (orderBook.hasOrderOfType(order.getSide().opposite()) && order.getQuantity() > 0) {
                Order matchingOrder = orderBook.auctionMatchWithFirst(order, openPrice);
                if (matchingOrder == null)
                    break;
                trades.add(applyTrade(order, matchingOrder));
                compareOrderAndMatchingOrderQuantity(order, matchingOrder, orderBook);
            }
        }
        return MatchResult.executed(order, trades);
    }

    private void addDifferenceBetweenPriceAndOpenPriceToBrokerCredit(Order order) {
        if (order.getSide() == Side.BUY)
            order.getBroker().increaseCreditBy((long) Math.abs(order.getPrice() - openPrice) * order.getQuantity());
    }

    private LinkedList<Order> chooseSide(OrderBook orderBook) {
        if (findSumBuyQuantitiesInOrderList(orderBook.getBuyQueue(), openPrice) > findSumSellQuantitiesInOrderList(orderBook.getSellQueue(), openPrice))
            return orderBook.getBuyQueue();
        else
            return orderBook.getSellQueue();
    }

    private Trade applyTrade(Order order, Order matchingOrder) {
        Trade trade = new Trade(order.getSecurity(), openPrice, Math.min(order.getQuantity(), matchingOrder.getQuantity()),
                order, matchingOrder);
        trade.decreaseBuyersCredit();
        trade.increaseSellersCredit();
        return trade;
    }

    private void compareOrderAndMatchingOrderQuantity(Order order, Order matchingOrder, OrderBook orderBook) {
        if (order.getQuantity() >= matchingOrder.getQuantity()) {
            order.decreaseQuantity(matchingOrder.getQuantity());
            orderBook.removeFirst(matchingOrder.getSide());
            if (matchingOrder instanceof IcebergOrder icebergOrder) {
                icebergOrder.decreaseQuantity(matchingOrder.getQuantity());
                icebergOrder.replenish();
                if (icebergOrder.getQuantity() > 0)
                    orderBook.enqueue(icebergOrder);
            }
        } else {
            matchingOrder.decreaseQuantity(order.getQuantity());
            order.makeQuantityZero();
        }
    }

    public LinkedList<MatchResult> execute(Security security) {
        LinkedList<MatchResult> matchResults = new LinkedList<>();
        openPrice = findOpenPrice(security);
        LinkedList<Order> chosenSide = chooseSide(security.getOrderBook());
        for (Order order : chosenSide) {
            MatchResult result = match(order);
            if (!result.trades().isEmpty()) {
                for (Trade trade : result.trades()) {
                    trade.getBuy().getShareholder().incPosition(trade.getSecurity(), trade.getQuantity());
                    trade.getSell().getShareholder().decPosition(trade.getSecurity(), trade.getQuantity());
                }
            }
            matchResults.add(result);
        }
        return matchResults;
    }

    public MatchResult updateOpenPriceWithNewOrder(Order order) {
        order.getSecurity().getOrderBook().enqueue(order);
        openPrice = findOpenPrice(order.getSecurity());
        return MatchResult.openingPriceHasBeenSet(order.getSecurity().getIsin(), openPrice, tradableQuantity);
    }

    private int findOpenPrice(Security security) {
        int maxLimit = security.getOrderBook().findMaxSellQueuePrice();
        int minLimit = security.getOrderBook().findMinBuyQueuePrice();
        if (maxLimit < minLimit) {
            int temp = maxLimit;
            maxLimit = minLimit;
            minLimit = temp;
        }
        return findOptimalOpenPrice(minLimit, maxLimit, security);
    }

    private int findOptimalOpenPrice(int minLimit, int maxLimit, Security security) {
        int maxQuantityTraded = Integer.MIN_VALUE;
        LinkedList<Integer> openPricesWithHighestQuantityTraded = new LinkedList<>();
        for (int i = minLimit; i <= maxLimit; i++) {
            int overallQuantityTraded = findOverallQuantityTraded(i, security.getOrderBook());
            if (overallQuantityTraded > maxQuantityTraded) {
                maxQuantityTraded = overallQuantityTraded;
                openPricesWithHighestQuantityTraded.clear();
                openPricesWithHighestQuantityTraded.add(i);
            } else if (overallQuantityTraded == maxQuantityTraded)
                openPricesWithHighestQuantityTraded.add(i);
        }
        tradableQuantity = maxQuantityTraded;
        return findClosestToLastTradePrice(openPricesWithHighestQuantityTraded, security);
    }

    private int findOverallQuantityTraded(int selectedOpenPrice, OrderBook orderBook) {
        LinkedList<Order> selectedBuyQueue = findSelectedBuyQueue(selectedOpenPrice, orderBook);
        LinkedList<Order> selectedSellQueue = findSelectedSellQueue(selectedOpenPrice, orderBook);
        if (selectedBuyQueue.isEmpty() || selectedSellQueue.isEmpty())
            return Integer.MIN_VALUE;
        return Math.min(findSumQuantitiesInOrderList(selectedSellQueue), findSumQuantitiesInOrderList(selectedBuyQueue));
    }

    private LinkedList<Order> findSelectedBuyQueue(int selectedOpenPrice, OrderBook orderBook) {
        LinkedList<Order> selectedBuyQueue = new LinkedList<>();
        for (Order order : orderBook.getBuyQueue()) {
            if (order.getPrice() >= selectedOpenPrice)
                selectedBuyQueue.add(order);
        }
        return selectedBuyQueue;
    }

    private LinkedList<Order> findSelectedSellQueue(int selectedOpenPrice, OrderBook orderBook) {
        LinkedList<Order> selectedSellQueue = new LinkedList<>();
        for (Order order : orderBook.getSellQueue()) {
            if (order.getPrice() <= selectedOpenPrice)
                selectedSellQueue.add(order);
        }
        return selectedSellQueue;
    }

    private int findSumBuyQuantitiesInOrderList(LinkedList<Order> orders, int openPrice) {
        int sumBuyQuantity = 0;
        for (Order order : orders) {
            if (order.getPrice() >= openPrice)
                sumBuyQuantity += order.getTotalQuantity();
        }
        return sumBuyQuantity;
    }

    private int findSumSellQuantitiesInOrderList(LinkedList<Order> orders, int openPrice) {
        int sumSellQuantity = 0;
        for (Order order : orders) {
            if (order.getPrice() <= openPrice)
                sumSellQuantity += order.getTotalQuantity();
        }
        return sumSellQuantity;
    }

    private int findSumQuantitiesInOrderList(LinkedList<Order> orders) {
        int sumQuantity = 0;
        for (Order order : orders)
            sumQuantity += order.getTotalQuantity();
        return sumQuantity;
    }

    private int findClosestToLastTradePrice(LinkedList<Integer> openPricesWithHighestQuantityTraded, Security security) {
        int minDistance = Integer.MAX_VALUE;
        int minElement = Integer.MAX_VALUE;
        int lastTradePrice = security.getLastTradePrice();
        for (int price : openPricesWithHighestQuantityTraded) {
            int distance = Math.abs(price - lastTradePrice);
            if (distance < minDistance) {
                minDistance = distance;
                minElement = price;
            } else if (distance == minDistance && price < minElement)
                minElement = price;
        }
        return minElement;
    }
}
