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
public class SecurityStateChangedEvent extends Event {
    @JsonDeserialize(using = LocalDateTimeDeserializer.class)
    private LocalDateTime time;
    private String securityIsin;
    private MatchingState state;

    public SecurityStateChangedEvent(String securityIsin, MatchingState state) {
        this.time = LocalDateTime.now();
        this.securityIsin = securityIsin;
        this.state = state;
    }
}
