package ir.ramtung.tinyme.messaging.event;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateTimeDeserializer;
import ir.ramtung.tinyme.messaging.request.MatchingState;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = false)
@AllArgsConstructor
@NoArgsConstructor
public class TradeEvent extends Event{
    @JsonDeserialize(using = LocalDateTimeDeserializer.class)
    private LocalDateTime time;
    private String securityIsin;
    private int price;
    private int quantity;
    private long buyId;
    private long sellId;

    public TradeEvent(String securityIsin, int price, int quantity, int buyId, int sellId) {
        this.time = LocalDateTime.now();
        this.securityIsin = securityIsin;
        this.price = price;
        this.quantity = quantity;
        this.buyId = buyId;
        this.sellId = sellId;
    }
}
