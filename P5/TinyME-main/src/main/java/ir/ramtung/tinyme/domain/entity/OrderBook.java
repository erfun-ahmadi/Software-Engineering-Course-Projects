package ir.ramtung.tinyme.domain.entity;

import ir.ramtung.tinyme.messaging.EventPublisher;
import ir.ramtung.tinyme.messaging.event.OrderActivatedEvent;
import lombok.Getter;

import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;

@Getter
public class OrderBook {
    private final LinkedList<Order> buyQueue;
    private final LinkedList<Order> sellQueue;
    private final LinkedList<Order> inactiveBuyQueue;
    private final LinkedList<Order> inactiveSellQueue;
    public final LinkedList<Order> activeQueue;

    EventPublisher eventPublisher;

    public OrderBook() {
        buyQueue = new LinkedList<>();
        sellQueue = new LinkedList<>();
        inactiveBuyQueue = new LinkedList<>();
        inactiveSellQueue = new LinkedList<>();
        activeQueue = new LinkedList<>();
    }

    public void enqueue(Order order) {
        List<Order> queue = getQueue(order.getSide(), order.getStopPrice(), order.isInactive());
        ListIterator<Order> it = queue.listIterator();
        if (order.getStopPrice() == 0) {
            while (it.hasNext()) {
                if (order.queuesBefore(it.next())) {
                    it.previous();
                    break;
                }
            }
        }
        else if (order.getStopPrice() != 0) {
            while (it.hasNext()) {
                if (order.queuesBeforeStopLimit(it.next())) {
                    it.previous();
                    break;
                }
            }
        }
        order.queue();
        it.add(order);
    }

    private LinkedList<Order> getQueue(Side side, int stopPrice, boolean inactive) {
        if (side == Side.BUY && stopPrice == 0) {
            return buyQueue;
        }
        else if (side == Side.SELL && stopPrice == 0) {
            return sellQueue;
        }
        else if (side == Side.BUY && stopPrice != 0 && inactive) {
            return inactiveBuyQueue;
        }
        else if (side == Side.SELL && stopPrice != 0 && inactive) {
            return inactiveSellQueue;
        }
        else if (stopPrice != 0 && !inactive) {
            return activeQueue;
        }
        return null;
    }

    public Order findByOrderId(Side side, long orderId, int stopPrice, boolean inactive) {
        var queue = getQueue(side, stopPrice, inactive);
        for (Order order : queue) {
            if (order.getOrderId() == orderId)
                return order;
        }
        return null;
    }

    public boolean removeByOrderId(Side side, long orderId, int stopPrice, boolean inactive) {
        var queue = getQueue(side, stopPrice, inactive);
        var it = queue.listIterator();
        while (it.hasNext()) {
            if (it.next().getOrderId() == orderId) {
                it.remove();
                return true;
            }
        }
        return false;
    }

    public Order matchWithFirst(Order newOrder) {
        var queue = getQueue(newOrder.getSide().opposite(), newOrder.getStopPrice(), newOrder.isInactive());
        if (newOrder.matches(queue.getFirst()))
            return queue.getFirst();
        else
            return null;
    }

    public void putBack(Order order) {
        LinkedList<Order> queue = getQueue(order.getSide(), order.getStopPrice(), order.isInactive());
        order.queue();
        queue.addFirst(order);
    }

    public void restoreSellOrder(Order sellOrder) {
        removeByOrderId(Side.SELL, sellOrder.getOrderId(), sellOrder.getStopPrice(), sellOrder.isInactive());
        putBack(sellOrder);
    }

    public void restoreBuyOrder(Order buyOrder) {
        removeByOrderId(Side.BUY, buyOrder.getOrderId(), buyOrder.getStopPrice(), buyOrder.isInactive());
        putBack(buyOrder);
    }

    public boolean hasOrderOfType(Side side) {
        return (side == Side.BUY ? buyQueue : sellQueue).isEmpty();
    }

    public void removeFirst(Side side) {
        (side == Side.BUY ? buyQueue : sellQueue).removeFirst();
    }

    public int totalSellQuantityByShareholder(Shareholder shareholder) {
        return sellQueue.stream()
                .filter(order -> order.getShareholder().equals(shareholder))
                .mapToInt(Order::getTotalQuantity)
                .sum();
    }

    public void activateOrder(){
        var it = inactiveBuyQueue.listIterator();
        while (it.hasNext()) {
            Order order = it.next();
            if ((order.getStopPrice() != 0 && order.getSide() == Side.BUY && order.getStopPrice() >= order.getSecurity().getLastTradePrice())){
                order.activate();
                removeByOrderId(order.getSide() , order.getOrderId(), order.getStopPrice(), order.isInactive());
                order.getBroker().increaseCreditBy(order.getValue());
                enqueue(order);
                eventPublisher.publish(new OrderActivatedEvent(order.getOrderId()));
            }
        }
        it = inactiveSellQueue.listIterator();
        while (it.hasNext()) {
            Order order = it.next();
            if ((order.getStopPrice() != 0 && order.getSide() == Side.SELL && order.getStopPrice() <= order.getSecurity().getLastTradePrice())){
                order.activate();
                removeByOrderId(order.getSide() , order.getOrderId(), order.getStopPrice(), order.isInactive());
                enqueue(order);
                eventPublisher.publish(new OrderActivatedEvent(order.getOrderId()));
            }
        }
    }
}
