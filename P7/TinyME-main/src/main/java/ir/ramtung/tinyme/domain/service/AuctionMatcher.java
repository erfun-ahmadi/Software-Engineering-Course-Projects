package ir.ramtung.tinyme.domain.service;

import ir.ramtung.tinyme.domain.entity.*;

import java.util.LinkedList;

public class AuctionMatcher {
    private LinkedList<MatchResult> matchResults = new LinkedList<>();
    private int openPrice;
    private int tradableQuantity;

    public MatchResult match(Order curOrder) {
        OrderBook orderBook = curOrder.getSecurity().getOrderBook();
        LinkedList<Trade> trades = new LinkedList<>();
        if ((curOrder.getSide() == Side.BUY && curOrder.getPrice() >= openPrice) || (curOrder.getSide() == Side.SELL &&
                curOrder.getPrice() <= openPrice)) {
            addDifferenceBetweenPriceAndOpenPriceToBrokerCredit(curOrder);
            if (orderBook.hasOrderOfType(curOrder.getSide().opposite()) && curOrder.getQuantity() > 0) {
                while (orderBook.hasOrderOfType(curOrder.getSide().opposite()) && curOrder.getQuantity() > 0) {
                    Order matchingOrder = orderBook.matchWithFirst(curOrder);
                    if (matchingOrder == null) {
                        break;
                    }
                    if ((matchingOrder.getSide() == Side.BUY && matchingOrder.getPrice() >= openPrice) ||
                    (matchingOrder.getSide() == Side.SELL && matchingOrder.getPrice() <= openPrice)) {
                        applyTrade(curOrder, matchingOrder, trades);
                        compareCurOrderAndMatchingOrderQuantity(curOrder, matchingOrder, orderBook);
                    }
                }
            }
            else {
                return  MatchResult.noAuctionOrderMatch();
            }
        }
        return MatchResult.executed(curOrder, trades);
    }

    private void addDifferenceBetweenPriceAndOpenPriceToBrokerCredit(Order curOrder) {
        if (curOrder.getSide() == Side.BUY) {
            curOrder.getBroker().increaseCreditBy((long) Math.abs(curOrder.getPrice() - openPrice) * curOrder.getQuantity());
        }
    }
    
    private LinkedList<Order> chooseSide(OrderBook orderBook) {
        LinkedList<Order> chosenSide;
        if (findSumBuyQantitiesInOrderList(orderBook.getBuyQueue(), openPrice) > findSumSellQantitiesInOrderList(orderBook.getSellQueue(), openPrice)) {
            chosenSide = orderBook.getBuyQueue();
        }
        else {
            chosenSide = orderBook.getSellQueue();
        }
        return chosenSide;
    }

    private void applyTrade(Order curOrder, Order matchingOrder, LinkedList<Trade> trades) {
        Trade trade = new Trade(curOrder.getSecurity(), openPrice, Math.min(curOrder.getQuantity(), matchingOrder.getQuantity()),
                curOrder, matchingOrder);
        trade.decreaseBuyersCredit();
        trade.increaseSellersCredit();
        trades.add(trade);
    }

    private void compareCurOrderAndMatchingOrderQuantity(Order curOrder, Order matchingOrder, OrderBook orderBook) {
        if (curOrder.getQuantity() >= matchingOrder.getQuantity()) {
            curOrder.decreaseQuantity(matchingOrder.getQuantity());
            orderBook.removeFirst(matchingOrder.getSide());
            if (matchingOrder instanceof IcebergOrder icebergOrder) {
                icebergOrder.decreaseQuantity(matchingOrder.getQuantity());
                icebergOrder.replenish();
                if (icebergOrder.getQuantity() > 0)
                    orderBook.enqueue(icebergOrder);
            }
        } else {
            matchingOrder.decreaseQuantity(curOrder.getQuantity());
            curOrder.makeQuantityZero();
        }
    }

    public LinkedList<MatchResult> execute(Order order) {
        openPrice = findOpenPrice(order);
        LinkedList<Order> chosenSide = chooseSide(order.getSecurity().getOrderBook());
        for (Order curOrder : chosenSide) {
            MatchResult result = match(curOrder);
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
        openPrice = findOpenPrice(order);
        return MatchResult.openingPriceBeenSet(openPrice , tradableQuantity);
    }

    private int findOpenPrice(Order order){
        int maxLimit = order.getSecurity().getOrderBook().findMaxSellQueuePrice();
        int minLimit = order.getSecurity().getOrderBook().findMinBuyQueuePrice();
        return findOptimalOpenPrice(minLimit , maxLimit , order);
    }

    private int findOptimalOpenPrice(int minLimit , int maxLimit , Order order){
        int maxQuantityTraded = Integer.MIN_VALUE;
        LinkedList<Integer> openPricesWithHighestQuantityTraded = new LinkedList<>();
        for (int i = minLimit; i <= maxLimit; i++) {
            int overallQuantityTraded = findOverallQuantityTraded(i, order.getSecurity().getOrderBook());
            if (overallQuantityTraded > maxQuantityTraded) {
                maxQuantityTraded = overallQuantityTraded;
                openPricesWithHighestQuantityTraded.clear();
                openPricesWithHighestQuantityTraded.add(i);
            } else if (overallQuantityTraded == maxQuantityTraded) {
                openPricesWithHighestQuantityTraded.add(i);
            }
        }
        tradableQuantity = maxQuantityTraded;
        return findClosestToLastTradePrice(openPricesWithHighestQuantityTraded , order);
    }

    private int findOverallQuantityTraded(int selectedOpenPrice, OrderBook orderBook){
        LinkedList<Order> selectedBuyQueue = findSelectedBuyQueue(selectedOpenPrice , orderBook);
        LinkedList<Order> selectedSellQueue = findSelectedSellQueue(selectedOpenPrice , orderBook);
        int sumQuantityinSellQueue = findSumQantitiesInOrderList(selectedSellQueue);
        int sumQuantityinBuyQueue = findSumQantitiesInOrderList(selectedBuyQueue);
        if (sumQuantityinSellQueue > sumQuantityinBuyQueue) {
            return sumQuantityinBuyQueue;
        }
        else{
            return sumQuantityinSellQueue;
        }
    }

    private LinkedList<Order> findSelectedBuyQueue(int selectedOpenPrice, OrderBook orderBook){
        LinkedList<Order> selectedBuyQueue = new LinkedList<>();
        for (Order order : orderBook.getBuyQueue()) {
            if(order.getPrice() >= selectedOpenPrice){
                selectedBuyQueue.add(order);
            }
        }
        return selectedBuyQueue;
    }

    private LinkedList<Order> findSelectedSellQueue(int selectedOpenPrice, OrderBook orderBook){
        LinkedList<Order> selectedSellQueue = new LinkedList<>();
        for (Order order : orderBook.getSellQueue()) {
            if(order.getPrice() <= selectedOpenPrice){
                selectedSellQueue.add(order);
            }
        }
        return selectedSellQueue;
    }

    private int findSumBuyQantitiesInOrderList(LinkedList<Order> orders, int openPrice){
        int sumBuyQuantity = 0;
        for (Order order : orders) {
            if (order.getPrice() >= openPrice) {
                sumBuyQuantity += order.getQuantity();
            }
        }
        return sumBuyQuantity;
    }

    private int findSumSellQantitiesInOrderList(LinkedList<Order> orders, int openPrice){
        int sumSellQuantity = 0;
        for (Order order : orders) {
            if (order.getPrice() <= openPrice) {
                sumSellQuantity += order.getQuantity();
            }
        }
        return sumSellQuantity;
    }

    private int findSumQantitiesInOrderList(LinkedList<Order> orders){
        int sumQuantity = 0;
        for (Order order : orders) {
            sumQuantity += order.getQuantity();
        }
        return sumQuantity;
    }

    private int findClosestToLastTradePrice(LinkedList<Integer> openPricesWithHighestQuantityTraded , Order order) {
        int minDistance = Integer.MAX_VALUE;
        int minElement = Integer.MAX_VALUE;
        int lastTradePrice = order.getSecurity().getLastTradePrice();
        for (int price : openPricesWithHighestQuantityTraded) {
            int distance = Math.abs(price - lastTradePrice);
            if (distance < minDistance) {
                minDistance = distance;
                minElement = price;
            } else if (distance == minDistance && price < minElement) {
                minElement = price;
            }
        }
        return minElement;
    }
}
