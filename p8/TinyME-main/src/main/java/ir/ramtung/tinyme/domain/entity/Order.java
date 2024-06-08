package ir.ramtung.tinyme.domain.entity;

import ir.ramtung.tinyme.messaging.request.EnterOrderRq;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;


@EqualsAndHashCode
@ToString
@Getter
@SuperBuilder
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

    public boolean shouldActivate() {
        return (stopPrice != 0 && side == Side.BUY && stopPrice <= security.getLastTradePrice()) ||
                (stopPrice != 0 && side== Side.SELL && stopPrice >= security.getLastTradePrice());
    }

    public Order snapshot() {
        return Order.builder().orderId(orderId).security(security).side(side).quantity(quantity).price(price).
                minimumExecutionQuantity(minimumExecutionQuantity).broker(broker).
                shareholder(shareholder).entryTime(entryTime).status(OrderStatus.SNAPSHOT).stopPrice(stopPrice).build();
    }

    public Order snapshotWithQuantity(int newQuantity) {
        return Order.builder().orderId(orderId).security(security).side(side).quantity(newQuantity).price(price).
                minimumExecutionQuantity(minimumExecutionQuantity).broker(broker).
                shareholder(shareholder).entryTime(entryTime).status(OrderStatus.SNAPSHOT).stopPrice(stopPrice).build();
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

    public boolean isUpdateStopPriceInvalid(EnterOrderRq updateOrderRq) {
        return (stopPrice == 0 && updateOrderRq.getStopPrice() != 0) || (stopPrice != updateOrderRq.getStopPrice() && !inactive);
    }

    public boolean isProposedPriceGood(int proposedPrice){
        if (side == Side.BUY)
            return this.price >= proposedPrice;
        else
            return this.price <= proposedPrice;
    }
}
