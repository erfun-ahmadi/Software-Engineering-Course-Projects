package ir.ramtung.tinyme.domain.entity;

import lombok.Getter;

import java.util.*;

@Getter
public class OrderBook {
    private final LinkedList<Order> buyQueue;
    private final LinkedList<Order> sellQueue;
    private final LinkedList<Order> inactiveBuyQueue;
    private final LinkedList<Order> inactiveSellQueue;
    public final LinkedList<Order> activeQueue;

    private final Map<Side, Map<Boolean, LinkedList<Order>>> queues;

    public OrderBook() {
        buyQueue = new LinkedList<>();
        sellQueue = new LinkedList<>();
        inactiveBuyQueue = new LinkedList<>();
        inactiveSellQueue = new LinkedList<>();
        activeQueue = new LinkedList<>();

        queues = new HashMap<>();

        Map<Boolean, LinkedList<Order>> buyMap = new HashMap<>();
        buyMap.put(Boolean.FALSE, buyQueue);
        buyMap.put(Boolean.TRUE, inactiveBuyQueue);

        Map<Boolean, LinkedList<Order>> sellMap = new HashMap<>();
        sellMap.put(Boolean.FALSE, sellQueue);
        sellMap.put(Boolean.TRUE, inactiveSellQueue);

        queues.put(Side.BUY, buyMap);
        queues.put(Side.SELL, sellMap);
    }

    public void enqueue(Order order) {
        List<Order> queue = getQueue(order.getSide(), order.isInactive());
        ListIterator<Order> it = queue.listIterator();
        while (it.hasNext()) {
            if (order.queuesBefore(it.next())) {
                it.previous();
                break;
            }
        }
        order.queue();
        it.add(order);
    }

    private LinkedList<Order> getQueue(Side side, boolean inactive) {
        return queues.get(side).get(inactive);
    }

    public void enqueueInActiveQueue(Order order) {
        ListIterator<Order> it = getActiveQueue().listIterator();
        while (it.hasNext()) {
            if (order.queuesBefore(it.next())) {
                it.previous();
                break;
            }
        }
        it.add(order);
    }

    public void dequeueFromActiveQueue(Order order) {
        var it = activeQueue.listIterator();
        while (it.hasNext()) {
            if (it.next().getOrderId() == order.getOrderId()) {
                it.remove();
                return;
            }
        }
    }

    public Order findByOrderId(Side side, long orderId, boolean inactive) {
        var queue = getQueue(side, inactive);
        for (Order order : queue) {
            if (order.getOrderId() == orderId)
                return order;
        }
        return null;
    }

    public boolean removeByOrderId(Side side, long orderId, boolean inactive) {
        var queue = getQueue(side, inactive);
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
        var queue = (newOrder.getSide() == Side.BUY ? sellQueue : buyQueue);
        if (newOrder.matches(queue.getFirst()))
            return queue.getFirst();
        else
            return null;
    }

    public Order auctionMatchWithFirst(Order newOrder, int openPrice) {
        var queue = (newOrder.getSide() == Side.BUY ? sellQueue : buyQueue);
        if (queue.getFirst().isProposedPriceGood(openPrice))
            return queue.getFirst();
        else
            return null;
    }

    public void putBack(Order order) {
        LinkedList<Order> queue = getQueue(order.getSide(), order.isInactive());
        order.queue();
        queue.addFirst(order);
    }

    public void restoreSellOrder(Order sellOrder) {
        removeByOrderId(Side.SELL, sellOrder.getOrderId(), sellOrder.isInactive());
        putBack(sellOrder);
    }

    public void restoreBuyOrder(Order buyOrder) {
        removeByOrderId(Side.BUY, buyOrder.getOrderId(), buyOrder.isInactive());
        putBack(buyOrder);
    }

    public boolean hasOrderOfType(Side side) {
        return !(side == Side.BUY ? buyQueue : sellQueue).isEmpty();
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

    public List<Order> activateOrder() {
        List<Order> activatedOrders = new LinkedList<>();
        activateBuyOrder(activatedOrders);
        activateSellOrder(activatedOrders);
        return activatedOrders;
    }

    private void activateSellOrder(List<Order> activatedOrders) {
        ListIterator<Order> it;
        it = inactiveSellQueue.listIterator();
        while (it.hasNext()) {
            Order order = it.next();
            if (order.getSide() == Side.SELL && order.shouldActivate()) {
                removeByOrderId(order.getSide(), order.getOrderId(), order.isInactive());
                enqueueInActiveQueue(order);
                order.activate();
                it = inactiveSellQueue.listIterator();
                activatedOrders.add(order);
            }
        }
    }

    private void activateBuyOrder(List<Order> activatedOrders) {
        var it = inactiveBuyQueue.listIterator();
        while (it.hasNext()) {
            Order order = it.next();
            if (order.getSide() == Side.BUY && order.shouldActivate()) {
                removeByOrderId(order.getSide(), order.getOrderId(), order.isInactive());
                order.getBroker().increaseCreditBy(order.getValue());
                enqueueInActiveQueue(order);
                order.activate();
                it = inactiveBuyQueue.listIterator();
                activatedOrders.add(order);
            }
        }
    }

    public int findMaxSellQueuePrice() {
        return sellQueue.stream().mapToInt(Order::getPrice).max().orElse(Integer.MIN_VALUE);
    }

    public int findMinBuyQueuePrice() {
        return buyQueue.stream().mapToInt(Order::getPrice).min().orElse(Integer.MAX_VALUE);
    }
}
