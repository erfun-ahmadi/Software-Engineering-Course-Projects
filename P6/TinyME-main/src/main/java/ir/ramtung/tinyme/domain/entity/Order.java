package ir.ramtung.tinyme.domain.entity;

import ir.ramtung.tinyme.messaging.request.EnterOrderRq;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

import java.time.LocalDateTime;

@Builder
@EqualsAndHashCode
@ToString
@Getter
public class Order {
    protected long orderId;
    protected Security security;
    protected Side side;
    protected int quantity;
    protected int price;
    protected int minimumExecutionQuantity;
    protected Broker broker;
    protected Shareholder shareholder;
    @Builder.Default
    protected LocalDateTime entryTime = LocalDateTime.now();
    @Builder.Default
    protected OrderStatus status = OrderStatus.NEW;
    protected int stopPrice;
    protected boolean inactive;

    public Order(long orderId, Security security, Side side, int quantity, int price, int minimumExecutionQuantity, Broker broker, Shareholder shareholder, LocalDateTime entryTime, OrderStatus status, int stopPrice, boolean inactive) {
        this.orderId = orderId;
        this.security = security;
        this.side = side;
        this.quantity = quantity;
        this.price = price;
        this.minimumExecutionQuantity = minimumExecutionQuantity;
        this.entryTime = entryTime;
        this.broker = broker;
        this.shareholder = shareholder;
        this.status = status;
        this.stopPrice = stopPrice;
        this.inactive = inactive;
    }

    public Order(long orderId, Security security, Side side, int quantity, int price, int minimumExecutionQuantity, Broker broker, Shareholder shareholder, LocalDateTime entryTime, OrderStatus status, int stopPrice) {
        this.orderId = orderId;
        this.security = security;
        this.side = side;
        this.quantity = quantity;
        this.price = price;
        this.minimumExecutionQuantity = minimumExecutionQuantity;
        this.entryTime = entryTime;
        this.broker = broker;
        this.shareholder = shareholder;
        this.status = status;
        this.stopPrice = stopPrice;
        this.inactive = false;
    }

    public Order(long orderId, Security security, Side side, int quantity, int price, int minimumExecutionQuantity, Broker broker, Shareholder shareholder, LocalDateTime entryTime, int stopPrice) {
        this.orderId = orderId;
        this.security = security;
        this.side = side;
        this.quantity = quantity;
        this.price = price;
        this.minimumExecutionQuantity = minimumExecutionQuantity;
        this.entryTime = entryTime;
        this.broker = broker;
        this.shareholder = shareholder;
        this.status = OrderStatus.NEW;
        this.stopPrice = stopPrice;
        this.inactive = false;
    }

    public Order(long orderId, Security security, Side side, int quantity, int price, int minimumExecutionQuantity, Broker broker, Shareholder shareholder, int stopPrice) {
        this(orderId, security, side, quantity, price, minimumExecutionQuantity, broker, shareholder, LocalDateTime.now(), stopPrice);
    }

    public Order snapshot() {
        return new Order(orderId, security, side, quantity, price, minimumExecutionQuantity, broker, shareholder, entryTime, OrderStatus.SNAPSHOT, stopPrice);
    }

    public Order snapshotWithQuantity(int newQuantity) {
        return new Order(orderId, security, side, newQuantity, price, minimumExecutionQuantity, broker, shareholder, entryTime, OrderStatus.SNAPSHOT, stopPrice);
    }

    public boolean matches(Order other) {
        if (side == Side.BUY)
            return price >= other.price;
        else
            return price <= other.price;
    }

    public void decreaseQuantity(int amount) {
        if (amount > quantity)
            throw new IllegalArgumentException();
        quantity -= amount;
    }

    public void makeQuantityZero() {
        quantity = 0;
    }

    public boolean queuesBefore(Order order) {
        if (!inactive) {
            if (order.getSide() == Side.BUY) {
                return price > order.getPrice();
            } else {
                return price < order.getPrice();
            }
        } else {
            if (order.getSide() == Side.BUY) {
                return stopPrice > order.getStopPrice();
            } else {
                return stopPrice < order.getStopPrice();
            }
        }
    }

    public void queue() {
        status = OrderStatus.QUEUED;
    }

    public void markAsNew(){
        status = OrderStatus.NEW;
    }
    public boolean isQuantityIncreased(int newQuantity) {
        return newQuantity > quantity;
    }

    public void updateFromRequest(EnterOrderRq updateOrderRq) {
        quantity = updateOrderRq.getQuantity();
        price = updateOrderRq.getPrice();
    }

    public void activate() {
        this.inactive = false;
    }

    public long getValue() {
        return (long)price * quantity;
    }

    public int getTotalQuantity() { return quantity; }

    public void updateInactiveOrder(EnterOrderRq enterOrderRq){
        this.price = enterOrderRq.getPrice();
        this.quantity = enterOrderRq.getQuantity();
        this.stopPrice = enterOrderRq.getStopPrice();
    }
}
