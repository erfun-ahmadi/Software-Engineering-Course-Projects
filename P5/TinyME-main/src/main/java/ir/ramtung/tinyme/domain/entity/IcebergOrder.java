package ir.ramtung.tinyme.domain.entity;

import ir.ramtung.tinyme.messaging.request.EnterOrderRq;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

import java.time.LocalDateTime;

@Getter
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class IcebergOrder extends Order {
    int peakSize;
    int displayedQuantity;

    public IcebergOrder(long orderId, Security security, Side side, int quantity, int price, int minimumExecutionQuantity, Broker broker, Shareholder shareholder, LocalDateTime entryTime, int peakSize, int displayedQuantity, OrderStatus status, int stopPrice) {
        super(orderId, security, side, quantity, price, minimumExecutionQuantity, broker, shareholder, entryTime, status, stopPrice);
        this.peakSize = peakSize;
        this.displayedQuantity = displayedQuantity;
    }

    public IcebergOrder(long orderId, Security security, Side side, int quantity, int price, int minimumExecutionQuantity, Broker broker, Shareholder shareholder, LocalDateTime entryTime, int peakSize, OrderStatus status, int stopPrice) {
        this(orderId, security, side, quantity, price, minimumExecutionQuantity, broker, shareholder, entryTime, peakSize, Math.min(peakSize, quantity), status, stopPrice);
    }

    public IcebergOrder(long orderId, Security security, Side side, int quantity, int price, int minimumExecutionQuantity, Broker broker, Shareholder shareholder, LocalDateTime entryTime, int peakSize, int stopPrice) {
        this(orderId, security, side, quantity, price, minimumExecutionQuantity, broker, shareholder, entryTime, peakSize, OrderStatus.NEW, stopPrice);
    }

    public IcebergOrder(long orderId, Security security, Side side, int quantity, int price, int minimumExecutionQuantity, Broker broker, Shareholder shareholder, int peakSize, int stopPrice) {
        super(orderId, security, side, quantity, price, minimumExecutionQuantity, broker, shareholder, stopPrice);
        this.peakSize = peakSize;
        this.displayedQuantity = Math.min(peakSize, quantity);
    }

    @Override
    public Order snapshot() {
        return new IcebergOrder(orderId, security, side, quantity, price, minimumExecutionQuantity, broker, shareholder, entryTime, peakSize, OrderStatus.SNAPSHOT, stopPrice);
    }

    @Override
    public Order snapshotWithQuantity(int newQuantity) {
        return new IcebergOrder(orderId, security, side, newQuantity, price, minimumExecutionQuantity, broker, shareholder, entryTime, peakSize, OrderStatus.SNAPSHOT, stopPrice);
    }

    @Override
    public int getQuantity() {
        if (status == OrderStatus.NEW)
            return super.getQuantity();
        return displayedQuantity;
    }

    @Override
    public void decreaseQuantity(int amount) {
        if (status == OrderStatus.NEW) {
            super.decreaseQuantity(amount);
            return;
        }
        if (amount > displayedQuantity)
            throw new IllegalArgumentException();
        quantity -= amount;
        displayedQuantity -= amount;
    }

    public void replenish() {
        displayedQuantity = Math.min(quantity, peakSize);
    }

    @Override
    public void queue() {
        displayedQuantity = Math.min(quantity, peakSize);
        super.queue();
    }

    @Override
    public void updateFromRequest(EnterOrderRq updateOrderRq) {
        super.updateFromRequest(updateOrderRq);
        if (peakSize < updateOrderRq.getPeakSize()) {
            displayedQuantity = Math.min(quantity, updateOrderRq.getPeakSize());
        }
        else if (peakSize > updateOrderRq.getPeakSize()) {
            displayedQuantity = Math.min(displayedQuantity, updateOrderRq.getPeakSize());
        }
        peakSize = updateOrderRq.getPeakSize();
    }
}