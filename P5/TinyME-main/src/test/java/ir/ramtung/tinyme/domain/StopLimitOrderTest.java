package ir.ramtung.tinyme.domain;

import ir.ramtung.tinyme.config.MockedJMSTestConfig;
import ir.ramtung.tinyme.domain.entity.*;
import ir.ramtung.tinyme.domain.service.Matcher;
import ir.ramtung.tinyme.domain.service.OrderHandler;
import ir.ramtung.tinyme.messaging.EventPublisher;
import ir.ramtung.tinyme.messaging.event.OrderAcceptedEvent;
import ir.ramtung.tinyme.messaging.event.OrderActivatedEvent;
import ir.ramtung.tinyme.messaging.request.EnterOrderRq;
import ir.ramtung.tinyme.repository.BrokerRepository;
import ir.ramtung.tinyme.repository.SecurityRepository;
import ir.ramtung.tinyme.repository.ShareholderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;

import static ir.ramtung.tinyme.domain.entity.Side.BUY;
import static ir.ramtung.tinyme.domain.entity.Side.SELL;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.verify;

@SpringBootTest
@Import(MockedJMSTestConfig.class)
@DirtiesContext
class StopLimitOrderTest {
    @Autowired
    OrderHandler orderHandler;
    @Autowired
    EventPublisher eventPublisher;
    @Autowired
    SecurityRepository securityRepository;
    @Autowired
    BrokerRepository brokerRepository;
    @Autowired
    ShareholderRepository shareholderRepository;
    private Security security;
    private Broker broker;
    private Shareholder shareholder;
    private List<Order> orders;
    @Autowired
    Matcher matcher;

    @BeforeEach
    void setupOrderBook() {
        securityRepository.clear();
        brokerRepository.clear();
        shareholderRepository.clear();

        security = Security.builder().isin("ABC").build();
        broker = Broker.builder().brokerId(0).credit(10_000_000L).build();
        shareholder = Shareholder.builder().shareholderId(0).build();
        shareholder.incPosition(security, 100_000);
        orders = Arrays.asList(
                new Order(1, security, BUY, 304, 15700, 0, broker, shareholder, 0),
                new Order(2, security, BUY, 43, 15500, 0, broker, shareholder, 0),
                new Order(3, security, BUY, 445, 15450, 0, broker, shareholder, 0),
                new Order(4, security, BUY, 526, 15450, 0, broker, shareholder, 0),
                new Order(5, security, BUY, 1000, 15400, 0, broker, shareholder, 0),
                new Order(6, security, Side.SELL, 350, 15800, 0, broker, shareholder, 0),
                new Order(7, security, Side.SELL, 285, 15810, 0, broker, shareholder, 0),
                new Order(8, security, Side.SELL, 800, 15810, 0, broker, shareholder, 0),
                new Order(9, security, Side.SELL, 340, 15820, 0, broker, shareholder, 0),
                new Order(10, security, Side.SELL, 65, 15820, 0, broker, shareholder, 0),
                new Order(11, security, Side.SELL, 400, 15600, 100, broker, shareholder, 0)
        );
        orders.forEach(order -> security.getOrderBook().enqueue(order));
        securityRepository.addSecurity(security);
        brokerRepository.addBroker(broker);
        shareholderRepository.addShareholder(shareholder);
    }

    @Test
    void stop_limit_buy_order_inactive_enter() {
        EnterOrderRq enterOrderRq = EnterOrderRq.createNewOrderRq(1, security.getIsin(), 12, LocalDateTime.now(), BUY, 440, 15450, 0, 0, 0, 0, 15400);
        orderHandler.handleEnterOrder(enterOrderRq);
        verify(eventPublisher).publish((new OrderActivatedEvent(1)));
//        assertThatNoException().isThrownBy(() -> security.newOrder(enterOrderRq, matcher));
//        assertThat(security.getOrderBook().getBuyQueue().get(2).getQuantity()).isEqualTo(440);
//        assertThat(security.getOrderBook().getBuyQueue().get(2).getOrderId()).isEqualTo(3);
    }
}