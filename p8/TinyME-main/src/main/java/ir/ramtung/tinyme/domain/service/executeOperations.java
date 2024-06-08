package ir.ramtung.tinyme.domain.service;

import ir.ramtung.tinyme.domain.entity.*;
import lombok.Getter;
import org.springframework.stereotype.Service;

import java.util.LinkedList;

@Getter
@Service
public class executeOperations {

    public LinkedList<Trade> match(Order order , int openingPrice) {
        OrderBook orderBook = order.getSecurity().getOrderBook();
        LinkedList<Trade> trades = new LinkedList<>();
        if (order.isProposedPriceGood(openingPrice)) {
            increaseBuyerBrokersCredit(order , openingPrice);
            while (orderBook.hasOrderOfType(order.getSide().opposite()) && order.getQuantity() > 0) {
                Order matchingOrder = orderBook.auctionMatchWithFirst(order, openingPrice);
                if (matchingOrder == null)
                    break;
                trades.add(applyTrade(order, matchingOrder , openingPrice));
                dequeueEmptyOrder(order, matchingOrder, orderBook);
            }
        }
        return trades;
    }

    private void increaseBuyerBrokersCredit(Order order , int openingPrice) {
        if (order.getSide() == Side.BUY)
            order.getBroker().increaseCreditBy((long) Math.abs(order.getPrice() - openingPrice) * order.getQuantity());
    }

    public LinkedList<Order> chooseSide(OrderBook orderBook , int openingPrice) {
        if (findSumBuyQuantitiesThatCanBeTradedWithPrice(openingPrice, orderBook) > findSumSellQuantitiesThatCanBeTradedWithPrice(openingPrice, orderBook))
            return orderBook.getBuyQueue();
        else
            return orderBook.getSellQueue();
    }

    private Trade applyTrade(Order order, Order matchingOrder , int openingPrice) {
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
}


