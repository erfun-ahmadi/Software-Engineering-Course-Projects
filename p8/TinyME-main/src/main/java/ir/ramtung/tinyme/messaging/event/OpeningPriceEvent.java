package ir.ramtung.tinyme.messaging.event;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateTimeDeserializer;
import ir.ramtung.tinyme.domain.entity.Side;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = false)
@AllArgsConstructor
@NoArgsConstructor
public class OpeningPriceEvent extends Event {
    @JsonDeserialize(using = LocalDateTimeDeserializer.class)
    private LocalDateTime time;
    private String securityIsin;
    private int openingPrice;
    private int tradableQuantity;

    public OpeningPriceEvent(String securityIsin, int openingPrice, int tradableQuantity) {
        this.time = LocalDateTime.now();
        this.securityIsin = securityIsin;
        this.openingPrice = openingPrice;
        this.tradableQuantity = tradableQuantity;
    }
}