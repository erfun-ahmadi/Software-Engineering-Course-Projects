package ir.ramtung.tinyme.domain.entity;

import ir.ramtung.tinyme.messaging.request.EnterOrderRq;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

import java.time.LocalDateTime;

@Getter
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class StopLimitOrder extends Order {
    int stopPrice;

    public StopLimitOrder(long orderId, Security security, Side side, int quantity, int price, int minimumExecutionQuantity, Broker broker, Shareholder shareholder, LocalDateTime entryTime, OrderStatus status, int stopPrice) {
        super(orderId, security, side, quantity, price, minimumExecutionQuantity, broker, shareholder, entryTime, status);
        this.stopPrice = stopPrice;
    }

    public StopLimitOrder(long orderId, Security security, Side side, int quantity, int price, int minimumExecutionQuantity, Broker broker, Shareholder shareholder, LocalDateTime entryTime, int stopPrice) {
        this(orderId, security, side, quantity, price, minimumExecutionQuantity, broker, shareholder, entryTime, OrderStatus.NEW, stopPrice);
    }

    public StopLimitOrder(long orderId, Security security, Side side, int quantity, int price, int minimumExecutionQuantity, Broker broker, Shareholder shareholder, int stopPrice) {
        super(orderId, security, side, quantity, price, minimumExecutionQuantity, broker, shareholder);
        this.stopPrice = stopPrice;
    }

    @Override
    public Order snapshot() {
        return new StopLimitOrder(orderId, security, side, quantity, price, minimumExecutionQuantity, broker, shareholder, entryTime, OrderStatus.SNAPSHOT, stopPrice);
    }

    @Override
    public Order snapshotWithQuantity(int newQuantity) {
        return new StopLimitOrder(orderId, security, side, newQuantity, price, minimumExecutionQuantity, broker, shareholder, entryTime, OrderStatus.SNAPSHOT, stopPrice);
    }

    @Override
    public void updateFromRequest(EnterOrderRq updateOrderRq) {
        super.updateFromRequest(updateOrderRq);
        stopPrice = updateOrderRq.getStopPrice();
    }
}
