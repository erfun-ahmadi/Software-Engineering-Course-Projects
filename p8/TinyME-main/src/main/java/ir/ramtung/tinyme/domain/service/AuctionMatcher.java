package ir.ramtung.tinyme.domain.service;

import ir.ramtung.tinyme.domain.entity.*;
import lombok.Getter;
import org.springframework.stereotype.Service;

import java.util.LinkedList;

@Getter
@Service
public class AuctionMatcher {
    private int openingPrice;
    private int tradableQuantity;

    public LinkedList<Trade> match(Order order) {
        OrderBook orderBook = order.getSecurity().getOrderBook();
        LinkedList<Trade> trades = new LinkedList<>();
        if (order.isProposedPriceGood(openingPrice)) {
            increaseBuyerBrokersCredit(order);
            while (orderBook.hasOrderOfType(order.getSide().opposite()) && order.getQuantity() > 0) {
                Order matchingOrder = orderBook.auctionMatchWithFirst(order, openingPrice);
                if (matchingOrder == null)
                    break;
                trades.add(applyTrade(order, matchingOrder));
                dequeueEmptyOrder(order, matchingOrder, orderBook);
            }
        }
        return trades;
    }

    private void increaseBuyerBrokersCredit(Order order) {
        if (order.getSide() == Side.BUY)
            order.getBroker().increaseCreditBy((long) Math.abs(order.getPrice() - openingPrice) * order.getQuantity());
    }

    private LinkedList<Order> chooseSide(OrderBook orderBook) {
        if (findSumBuyQuantitiesThatCanBeTradedWithPrice(openingPrice, orderBook) > findSumSellQuantitiesThatCanBeTradedWithPrice(openingPrice, orderBook))
            return orderBook.getBuyQueue();
        else
            return orderBook.getSellQueue();
    }

    private Trade applyTrade(Order order, Order matchingOrder) {
        Trade trade = new Trade(order.getSecurity(), openingPrice, Math.min(order.getQuantity(), matchingOrder.getQuantity()),
                order, matchingOrder);
        trade.increaseSellersCredit();
        trade.getBuy().getShareholder().incPosition(trade.getSecurity(), trade.getQuantity());
        trade.getSell().getShareholder().decPosition(trade.getSecurity(), trade.getQuantity());
        return trade;
    }

    private void dequeueEmptyOrder(Order order, Order matchingOrder, OrderBook orderBook) {
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

    public LinkedList<Trade> execute(Security security) {
        LinkedList<Trade> trades = new LinkedList<>();
        openingPrice = findOpeningPrice(security);
        LinkedList<Order> chosenSide = chooseSide(security.getOrderBook());
        for (Order order : chosenSide)
            trades.addAll(match(order));
        return trades;
    }

    public MatchResult updateOpeningPriceWithNewOrder(Order order) {
        order.getSecurity().getOrderBook().enqueue(order);
        openingPrice = findOpeningPrice(order.getSecurity());
        return MatchResult.openingPriceHasBeenSet(order.getSecurity().getIsin(), openingPrice, tradableQuantity);
    }

    public int findOpeningPrice(Security security) {
        int maxLimit = security.getOrderBook().findMaxSellQueuePrice();
        int minLimit = security.getOrderBook().findMinBuyQueuePrice();
        if (maxLimit < minLimit) {
            int temp = maxLimit;
            maxLimit = minLimit;
            minLimit = temp;
        }
        return findOpeningPriceInLimit(minLimit, maxLimit, security);
    }

    private int findOpeningPriceInLimit(int minLimit, int maxLimit, Security security) {
        int maxQuantityTraded = Integer.MIN_VALUE;
        LinkedList<Integer> openingPricesWithHighestQuantityTraded = new LinkedList<>();
        for (int i = minLimit; i <= maxLimit; i++) {
            int overallQuantityTraded = findSumQuantityThatCanBeTradedWithPrice(i, security.getOrderBook());
            if (overallQuantityTraded > maxQuantityTraded) {
                maxQuantityTraded = overallQuantityTraded;
                openingPricesWithHighestQuantityTraded.clear();
                openingPricesWithHighestQuantityTraded.add(i);
            } else if (overallQuantityTraded == maxQuantityTraded)
                openingPricesWithHighestQuantityTraded.add(i);
        }
        tradableQuantity = maxQuantityTraded;
        return findClosestToLastTradePrice(openingPricesWithHighestQuantityTraded, security);
    }

    private int findSumQuantityThatCanBeTradedWithPrice(int openingPrice, OrderBook orderBook) {
        LinkedList<Order> selectedBuyQueue = getBuyOrdersMatchingWithPrice(openingPrice, orderBook);
        LinkedList<Order> selectedSellQueue = getSellOrdersMatchingWithPrice(openingPrice, orderBook);
        if (selectedBuyQueue.isEmpty() || selectedSellQueue.isEmpty())
            return Integer.MIN_VALUE;
        return Math.min(getSumQuantities(selectedSellQueue), getSumQuantities(selectedBuyQueue));
    }

    private LinkedList<Order> getBuyOrdersMatchingWithPrice(int openingPrice, OrderBook orderBook) {
        LinkedList<Order> selectedBuyQueue = new LinkedList<>();
        for (Order order : orderBook.getBuyQueue()) {
            if (order.getPrice() >= openingPrice)
                selectedBuyQueue.add(order);
        }
        return selectedBuyQueue;
    }

    private LinkedList<Order> getSellOrdersMatchingWithPrice(int openingPrice, OrderBook orderBook) {
        LinkedList<Order> selectedSellQueue = new LinkedList<>();
        for (Order order : orderBook.getSellQueue()) {
            if (order.getPrice() <= openingPrice)
                selectedSellQueue.add(order);
        }
        return selectedSellQueue;
    }

    private int findSumBuyQuantitiesThatCanBeTradedWithPrice(int openingPrice, OrderBook orderBook) {
        int sumBuyQuantity = 0;
        for (Order order : orderBook.getBuyQueue()) {
            if (order.getPrice() >= openingPrice)
                sumBuyQuantity += order.getTotalQuantity();
        }
        return sumBuyQuantity;
    }

    private int findSumSellQuantitiesThatCanBeTradedWithPrice(int openingPrice, OrderBook orderBook) {
        int sumSellQuantity = 0;
        for (Order order : orderBook.getSellQueue()) {
            if (order.getPrice() <= openingPrice)
                sumSellQuantity += order.getTotalQuantity();
        }
        return sumSellQuantity;
    }

    private int getSumQuantities(LinkedList<Order> orders) {
        return orders.stream().mapToInt(Order::getTotalQuantity).sum();
    }

    public int findClosestToLastTradePrice(LinkedList<Integer> prices, Security security) {
        int minDistance = Integer.MAX_VALUE;
        int minElement = Integer.MAX_VALUE;
        int lastTradePrice = security.getLastTradePrice();
        for (int price : prices) {
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
