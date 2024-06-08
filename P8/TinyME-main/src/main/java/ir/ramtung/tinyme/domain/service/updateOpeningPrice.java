package ir.ramtung.tinyme.domain.service;

import ir.ramtung.tinyme.domain.entity.*;
import lombok.Getter;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;


@Getter
@Service
public class updateOpeningPrice {

    public List<Integer> findOpeningPrice(Security security) {
        int maxLimit = security.getOrderBook().findMaxSellQueuePrice();
        int minLimit = security.getOrderBook().findMinBuyQueuePrice();
        if (maxLimit < minLimit) {
            int temp = maxLimit;
            maxLimit = minLimit;
            minLimit = temp;
        }
        return findOpeningPriceInLimit(minLimit, maxLimit, security);
    }

    private List<Integer> findOpeningPriceInLimit(int minLimit, int maxLimit, Security security) {
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
        int tradableQuantity = maxQuantityTraded;
        List<Integer> tradableQuantityOpeningPrice = new ArrayList<>();
        tradableQuantityOpeningPrice.add(tradableQuantity);
        tradableQuantityOpeningPrice.add(findClosestToLastTradePrice(openingPricesWithHighestQuantityTraded, security));
        return tradableQuantityOpeningPrice;
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
