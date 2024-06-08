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

    public void handleIcebergOrder(Order matchingOrder, OrderBook orderBook) {
        if (matchingOrder instanceof IcebergOrder icebergOrder) {
            icebergOrder.decreaseQuantity(matchingOrder.getQuantity());
            icebergOrder.replenish();
            if (icebergOrder.getQuantity() > 0)
                orderBook.enqueue(icebergOrder);
        }
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

    public void removeByOrderId(Side side, long orderId, boolean inactive) {
        var queue = getQueue(side, inactive);
        var it = queue.listIterator();
        while (it.hasNext()) {
            if (it.next().getOrderId() == orderId) {
                it.remove();
                return;
            }
        }
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
        activateOrders(inactiveBuyQueue, activatedOrders);
        activateOrders(inactiveSellQueue, activatedOrders);
        return activatedOrders;
    }

    private void activateOrders(LinkedList<Order> queue, List<Order> activatedOrders) {
        var it = queue.listIterator();
        while (it.hasNext()) {
            Order order = it.next();
            if (order.shouldActivate()) {
                removeByOrderId(order.getSide(), order.getOrderId(), order.isInactive());
                if (order.getSide() == Side.BUY) {
                    order.getBroker().increaseCreditBy(order.getValue());
                }
                enqueueInActiveQueue(order);
                order.activate();
                it = queue.listIterator();
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
