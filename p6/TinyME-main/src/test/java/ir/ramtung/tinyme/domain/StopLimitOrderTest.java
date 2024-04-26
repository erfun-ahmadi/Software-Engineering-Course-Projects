package ir.ramtung.tinyme.domain;

import ir.ramtung.tinyme.config.MockedJMSTestConfig;
import ir.ramtung.tinyme.domain.entity.*;
import ir.ramtung.tinyme.domain.service.Matcher;
import ir.ramtung.tinyme.domain.service.OrderHandler;
import ir.ramtung.tinyme.messaging.EventPublisher;
import ir.ramtung.tinyme.messaging.Message;
import ir.ramtung.tinyme.messaging.event.*;
import ir.ramtung.tinyme.messaging.request.EnterOrderRq;
import ir.ramtung.tinyme.repository.BrokerRepository;
import ir.ramtung.tinyme.repository.SecurityRepository;
import ir.ramtung.tinyme.repository.ShareholderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;

import java.time.LocalDateTime;
import java.util.Arrays;
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
    long original_broker_credit;

    @BeforeEach
    void setupOrderBook() {
        securityRepository.clear();
        brokerRepository.clear();
        shareholderRepository.clear();

        security = Security.builder().lastTradePrice(15500).build();
        original_broker_credit = 10000000;
        broker = Broker.builder().brokerId(0).credit(original_broker_credit).build();
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
                new Order(10, security, Side.SELL, 65, 15820, 0, broker, shareholder, 0)
        );
        orders.forEach(order -> security.getOrderBook().enqueue(order));
        securityRepository.addSecurity(security);
        brokerRepository.addBroker(broker);
        shareholderRepository.addShareholder(shareholder);
    }

    @Test
    void stop_limit_buy_order_inactive_enter() {
        EnterOrderRq enterOrderRq = EnterOrderRq.createNewOrderRq(1, security.getIsin(), 12, LocalDateTime.now(), BUY, 440, 15550, 0, 0, 0, 0, 15550, true);
        orderHandler.handleEnterOrder(enterOrderRq);
        verify(eventPublisher).publish((new OrderAcceptedEvent(1, 12)));
        assertThat(security.getOrderBook().getInactiveBuyQueue().size()).isEqualTo(1);
    }

    @Test
    void stop_limit_sell_order_inactive_enter() {
        EnterOrderRq enterOrderRq = EnterOrderRq.createNewOrderRq(1, security.getIsin(), 12, LocalDateTime.now(), SELL, 440, 15450, 0, 0, 0, 0, 15450, true);
        orderHandler.handleEnterOrder(enterOrderRq);
        verify(eventPublisher).publish((new OrderAcceptedEvent(1, 12)));
        assertThat(security.getOrderBook().getInactiveSellQueue().size()).isEqualTo(1);
    }

    @Test
    void stop_limit_buy_order_active_enter() {
        EnterOrderRq enterOrderRq = EnterOrderRq.createNewOrderRq(1, security.getIsin(), 12, LocalDateTime.now(), BUY, 440, 15450, 0, 0, 0, 0, 15450, true);
        orderHandler.handleEnterOrder(enterOrderRq);
        verify(eventPublisher).publish((new OrderAcceptedEvent(1, 12)));
        assertThat(security.getOrderBook().getInactiveBuyQueue().size()).isEqualTo(0);
    }

    @Test
    void stop_limit_sell_order_active_enter() {
        EnterOrderRq enterOrderRq = EnterOrderRq.createNewOrderRq(1, security.getIsin(), 12, LocalDateTime.now(), SELL, 300, 15800, 0, 0, 0, 0, 15550, true);
        orderHandler.handleEnterOrder(enterOrderRq);
        verify(eventPublisher).publish((new OrderAcceptedEvent(1, 12)));
        assertThat(security.getOrderBook().getInactiveSellQueue().size()).isEqualTo(0);
    }

    @Test
    void last_trade_price_updates() {
        EnterOrderRq enterOrderRq = EnterOrderRq.createNewOrderRq(1, security.getIsin(), 12, LocalDateTime.now(), SELL, 400, 15500, 0, 0, 0, 0, 0, false);
        orderHandler.handleEnterOrder(enterOrderRq);
        assertThat(security.getLastTradePrice()).isEqualTo(15500);
    }

    @Test
    void create_order_with_stop_limit_and_minimum_execution_quantity_fails() {
        EnterOrderRq enterOrderRq = EnterOrderRq.createNewOrderRq(1, security.getIsin(), 12, LocalDateTime.now(), BUY, 440, 15550, 0, 0, 0, 100, 15550, true);
        orderHandler.handleEnterOrder(enterOrderRq);
        ArgumentCaptor<OrderRejectedEvent> orderRejectedCaptor = ArgumentCaptor.forClass(OrderRejectedEvent.class);
        verify(eventPublisher).publish(orderRejectedCaptor.capture());
        OrderRejectedEvent outputEvent = orderRejectedCaptor.getValue();
        assertThat(outputEvent.getOrderId()).isEqualTo(12);
        assertThat(outputEvent.getErrors()).containsOnly(Message.NOT_ABLE_TO_CREATE_STOP_LIMIT_ORDER);
    }

    @Test
    void create_iceberg_order_with_stop_limit_fails() {
        EnterOrderRq enterOrderRq = EnterOrderRq.createNewOrderRq(1, security.getIsin(), 12, LocalDateTime.now(), BUY, 440, 15550, 0, 0, 100, 0, 15550, true);
        orderHandler.handleEnterOrder(enterOrderRq);
        ArgumentCaptor<OrderRejectedEvent> orderRejectedCaptor = ArgumentCaptor.forClass(OrderRejectedEvent.class);
        verify(eventPublisher).publish(orderRejectedCaptor.capture());
        OrderRejectedEvent outputEvent = orderRejectedCaptor.getValue();
        assertThat(outputEvent.getOrderId()).isEqualTo(12);
        assertThat(outputEvent.getErrors()).containsOnly(Message.NOT_ABLE_TO_CREATE_STOP_LIMIT_ORDER);
    }

    @Test
    void update_peak_size_for_stop_limit_order_fails() {
        EnterOrderRq enterOrderRq = EnterOrderRq.createNewOrderRq(1, security.getIsin(), 12, LocalDateTime.now(), BUY, 440, 15550, 0, 0, 0, 0, 15550, true);
        orderHandler.handleEnterOrder(enterOrderRq);
        orderHandler.handleEnterOrder(EnterOrderRq.createUpdateOrderRq(1, security.getIsin(), 12, LocalDateTime.now(), BUY, 440, 15550, 0, 0, 100, 0, 15550, true));
        ArgumentCaptor<OrderRejectedEvent> orderRejectedCaptor = ArgumentCaptor.forClass(OrderRejectedEvent.class);
        verify(eventPublisher).publish(orderRejectedCaptor.capture());
        OrderRejectedEvent outputEvent = orderRejectedCaptor.getValue();
        assertThat(broker.getCredit()).isEqualTo(original_broker_credit - enterOrderRq.getQuantity() * enterOrderRq.getPrice());
        assertThat(outputEvent.getErrors()).containsOnly(Message.CANNOT_SPECIFY_PEAK_SIZE_FOR_A_NON_ICEBERG_ORDER);
    }

    @Test
    void update_minimum_execution_quantity_for_stop_limit_order_fails() {
        EnterOrderRq enterOrderRq = EnterOrderRq.createNewOrderRq(1, security.getIsin(), 12, LocalDateTime.now(), BUY, 440, 15550, 0, 0, 0, 0, 15550, true);
        orderHandler.handleEnterOrder(enterOrderRq);
        orderHandler.handleEnterOrder(EnterOrderRq.createUpdateOrderRq(1, security.getIsin(), 12, LocalDateTime.now(), BUY, 440, 15550, 0, 0, 100, 100, 15550, true));
        ArgumentCaptor<OrderRejectedEvent> orderRejectedCaptor = ArgumentCaptor.forClass(OrderRejectedEvent.class);
        verify(eventPublisher).publish(orderRejectedCaptor.capture());
        OrderRejectedEvent outputEvent = orderRejectedCaptor.getValue();
        assertThat(broker.getCredit()).isEqualTo(original_broker_credit - enterOrderRq.getQuantity() * enterOrderRq.getPrice());
        assertThat(outputEvent.getErrors()).containsOnly(Message.CANNOT_UPDATE_MINIMUM_EXECUTION_QUANTITY);
    }

    @Test
    void update_quantity_and_price_for_inactive_stop_limit_order_successful() {
        EnterOrderRq enterOrderRq = EnterOrderRq.createNewOrderRq(1, security.getIsin(), 12, LocalDateTime.now(), BUY, 440, 15550, 0, 0, 0, 0, 15550, true);
        orderHandler.handleEnterOrder(enterOrderRq);
        verify(eventPublisher).publish((new OrderAcceptedEvent(1, 12)));
        EnterOrderRq updateOrderRq = EnterOrderRq.createUpdateOrderRq(2, security.getIsin(), 12, LocalDateTime.now(), BUY, 430, 15500, 0, 0, 0, 0, 15550, true);
        orderHandler.handleEnterOrder(updateOrderRq);
        verify(eventPublisher).publish((new OrderUpdatedEvent(2, 12)));
        assertThat(broker.getCredit()).isEqualTo(original_broker_credit - updateOrderRq.getQuantity() * updateOrderRq.getPrice());
    }

    @Test
    void update_stop_price_for_inactive_stop_limit_order_successful() {
        EnterOrderRq enterOrderRq = EnterOrderRq.createNewOrderRq(1, security.getIsin(), 12, LocalDateTime.now(), BUY, 440, 15550, 0, 0, 0, 0, 15550, true);
        orderHandler.handleEnterOrder(enterOrderRq);
        verify(eventPublisher).publish((new OrderAcceptedEvent(1, 12)));
        EnterOrderRq updateOrderRq = EnterOrderRq.createUpdateOrderRq(2, security.getIsin(), 12, LocalDateTime.now(), BUY, 440, 15550, 0, 0, 0, 0, 15650, true);
        orderHandler.handleEnterOrder(updateOrderRq);
        verify(eventPublisher).publish((new OrderUpdatedEvent(2, 12)));
        assertThat(broker.getCredit()).isEqualTo(original_broker_credit - enterOrderRq.getQuantity() * enterOrderRq.getPrice());
        assertThat(security.getOrderBook().getInactiveBuyQueue().getFirst().getStopPrice()).isEqualTo(updateOrderRq.getStopPrice());
    }

    @Test
    void update_stop_price_for_active_stop_limit_order_fails() {
        EnterOrderRq enterOrderRq = EnterOrderRq.createNewOrderRq(1, security.getIsin(), 12, LocalDateTime.now(), SELL, 300, 15800, 0, 0, 0, 0, 15550, true);
        orderHandler.handleEnterOrder(enterOrderRq);
        verify(eventPublisher).publish((new OrderAcceptedEvent(1, 12)));
        EnterOrderRq updateOrderRq = EnterOrderRq.createUpdateOrderRq(2, security.getIsin(), 12, LocalDateTime.now(), SELL, 300, 15800, 0, 0, 0, 0, 15650, true);
        orderHandler.handleEnterOrder(updateOrderRq);
        assertThat(security.getOrderBook().findActiveByOrderId(SELL, 12, 15550, false).getStopPrice()).isEqualTo(enterOrderRq.getStopPrice());
    }
}