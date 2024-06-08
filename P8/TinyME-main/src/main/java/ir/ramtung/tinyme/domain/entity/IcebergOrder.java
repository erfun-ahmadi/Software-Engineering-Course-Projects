package ir.ramtung.tinyme.domain.entity;

import ir.ramtung.tinyme.messaging.request.EnterOrderRq;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;

@Getter
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@SuperBuilder
public class IcebergOrder extends Order {
    int peakSize;
    int displayedQuantity;

    @Override
    public Order snapshot() {
        return IcebergOrder.builder().orderId(orderId).security(security).side(side).quantity(quantity).price(price).
                minimumExecutionQuantity(minimumExecutionQuantity).broker(broker).
                shareholder(shareholder).entryTime(entryTime).peakSize(peakSize).status(OrderStatus.SNAPSHOT).stopPrice(stopPrice).build();
   }

    @Override
    public Order snapshotWithQuantity(int newQuantity) {
        return IcebergOrder.builder().orderId(orderId).security(security).side(side).quantity(newQuantity).price(price).
                minimumExecutionQuantity(minimumExecutionQuantity).broker(broker).
                shareholder(shareholder).entryTime(entryTime).peakSize(peakSize).status(OrderStatus.SNAPSHOT).stopPrice(stopPrice).build();
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
