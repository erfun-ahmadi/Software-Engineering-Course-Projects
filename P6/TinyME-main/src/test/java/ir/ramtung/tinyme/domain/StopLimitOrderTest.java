package ir.ramtung.tinyme.domain;

import ir.ramtung.tinyme.config.MockedJMSTestConfig;
import ir.ramtung.tinyme.domain.entity.*;
import ir.ramtung.tinyme.domain.service.OrderHandler;
import ir.ramtung.tinyme.messaging.EventPublisher;
import ir.ramtung.tinyme.messaging.Message;
import ir.ramtung.tinyme.messaging.TradeDTO;
import ir.ramtung.tinyme.messaging.event.*;
import ir.ramtung.tinyme.messaging.request.DeleteOrderRq;
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
    private Shareholder shareholder;
    private Broker broker1;
    private Broker broker2;

    @BeforeEach
    void setup() {
        securityRepository.clear();
        brokerRepository.clear();
        shareholderRepository.clear();

        security = Security.builder().lastTradePrice(300).isin("ABC").build();
        securityRepository.addSecurity(security);

        shareholder = Shareholder.builder().build();
        shareholder.incPosition(security, 100_000);
        shareholderRepository.addShareholder(shareholder);

        broker1 = Broker.builder().brokerId(1).credit(100_000).build();
        broker2 = Broker.builder().brokerId(2).credit(100_000).build();
        brokerRepository.addBroker(broker1);
        brokerRepository.addBroker(broker2);
    }

    @Test
    void last_trade_price_updates() {
        assertThat(security.getLastTradePrice()).isEqualTo(300);
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 100, LocalDateTime.now(), Side.BUY, 30, 500, broker1.getBrokerId(), shareholder.getShareholderId(), 0, 0, 0, false));
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(2, "ABC", 200, LocalDateTime.now(), Side.SELL, 30, 500, broker2.getBrokerId(), shareholder.getShareholderId(), 0, 0, 0, false));
        assertThat(security.getLastTradePrice()).isEqualTo(500);
    }

    @Test
    void create_buy_order_with_stop_limit_and_minimum_execution_quantity_fails() {
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 100, LocalDateTime.now(), Side.BUY, 40, 500, broker1.getBrokerId(), shareholder.getShareholderId(), 0, 10, 1000, true));
        verify(eventPublisher).publish((new OrderRejectedEvent(1, 100, List.of(Message.NOT_ABLE_TO_CREATE_STOP_LIMIT_ORDER))));
        assertThat(broker1.getCredit()).isEqualTo(100_000);
    }

    @Test
    void create_sell_order_with_stop_limit_and_minimum_execution_quantity_fails() {
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 100, LocalDateTime.now(), Side.SELL, 40, 500, broker1.getBrokerId(), shareholder.getShareholderId(), 0, 10, 1000, true));
        verify(eventPublisher).publish((new OrderRejectedEvent(1, 100, List.of(Message.NOT_ABLE_TO_CREATE_STOP_LIMIT_ORDER))));
        assertThat(broker1.getCredit()).isEqualTo(100_000);
    }

    @Test
    void create_buy_iceberg_order_with_stop_limit_fails() {
        EnterOrderRq enterOrderRq = EnterOrderRq.createNewOrderRq(1, "ABC", 100, LocalDateTime.now(), Side.BUY, 40, 500, broker1.getBrokerId(), shareholder.getShareholderId(), 10, 0, 1000, true);
        orderHandler.handleEnterOrder(enterOrderRq);
        verify(eventPublisher).publish((new OrderRejectedEvent(1, 100, List.of(Message.NOT_ABLE_TO_CREATE_STOP_LIMIT_ORDER))));
        assertThat(broker1.getCredit()).isEqualTo(100_000);
    }

    @Test
    void create_sell_iceberg_order_with_stop_limit_fails() {
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 100, LocalDateTime.now(), Side.SELL, 40, 500, broker1.getBrokerId(), shareholder.getShareholderId(), 10, 0, 1000, true));
        verify(eventPublisher).publish((new OrderRejectedEvent(1, 100, List.of(Message.NOT_ABLE_TO_CREATE_STOP_LIMIT_ORDER))));
        assertThat(broker1.getCredit()).isEqualTo(100_000);
    }

    @Test
    void update_peak_size_for_buy_stop_limit_order_fails() {
        Order order = new Order(100, security, Side.BUY, 30, 500, 0, broker1, shareholder, LocalDateTime.now(), OrderStatus.NEW, 1000, true);
        security.getOrderBook().enqueue(order);
        orderHandler.handleEnterOrder(EnterOrderRq.createUpdateOrderRq(1, "ABC", 100, LocalDateTime.now(), Side.BUY, 40, 500, broker1.getBrokerId(), shareholder.getShareholderId(), 10, 0, 1000, true));
        verify(eventPublisher).publish((new OrderRejectedEvent(1, 100, List.of(Message.NOT_ABLE_TO_CREATE_STOP_LIMIT_ORDER))));
        assertThat(broker1.getCredit()).isEqualTo(100_000);
    }

    @Test
    void update_peak_size_for_sell_stop_limit_order_fails() {
        Order order = new Order(100, security, Side.SELL, 30, 500, 0, broker1, shareholder, LocalDateTime.now(), OrderStatus.NEW, 1000, true);
        security.getOrderBook().enqueue(order);
        EnterOrderRq enterOrderRq = EnterOrderRq.createUpdateOrderRq(1, "ABC", 100, LocalDateTime.now(), Side.SELL, 40, 500, broker1.getBrokerId(), shareholder.getShareholderId(), 10, 0, 1000, true);
        orderHandler.handleEnterOrder(enterOrderRq);
        verify(eventPublisher).publish((new OrderRejectedEvent(1, 100, List.of(Message.NOT_ABLE_TO_CREATE_STOP_LIMIT_ORDER))));
        assertThat(broker1.getCredit()).isEqualTo(100_000);
    }

    @Test
    void update_minimum_execution_quantity_for_buy_stop_limit_order_fails() {
        Order order = new Order(100, security, Side.BUY, 30, 500, 0, broker1, shareholder, LocalDateTime.now(), OrderStatus.NEW, 1000, true);
        security.getOrderBook().enqueue(order);
        orderHandler.handleEnterOrder(EnterOrderRq.createUpdateOrderRq(1, "ABC", 100, LocalDateTime.now(), Side.BUY, 40, 500, broker1.getBrokerId(), shareholder.getShareholderId(), 0, 10, 1000, true));
        verify(eventPublisher).publish((new OrderRejectedEvent(1, 100, List.of(Message.NOT_ABLE_TO_CREATE_STOP_LIMIT_ORDER))));
        assertThat(broker1.getCredit()).isEqualTo(100_000);
        assertThat(security.getOrderBook().findByOrderId(BUY, 100, true).getMinimumExecutionQuantity()).isEqualTo(0);
    }

    @Test
    void update_minimum_execution_quantity_for_sell_stop_limit_order_fails() {
        Order order = new Order(100, security, Side.SELL, 30, 500, 0, broker1, shareholder, LocalDateTime.now(), OrderStatus.NEW, 1000, true);
        security.getOrderBook().enqueue(order);
        orderHandler.handleEnterOrder(EnterOrderRq.createUpdateOrderRq(1, "ABC", 100, LocalDateTime.now(), Side.SELL, 40, 500, broker1.getBrokerId(), shareholder.getShareholderId(), 0, 10, 1000, true));
        verify(eventPublisher).publish((new OrderRejectedEvent(1, 100, List.of(Message.NOT_ABLE_TO_CREATE_STOP_LIMIT_ORDER))));
        assertThat(broker1.getCredit()).isEqualTo(100_000);
        assertThat(security.getOrderBook().findByOrderId(SELL, 100, true).getMinimumExecutionQuantity()).isEqualTo(0);
    }

    @Test
    void update_quantity_for_inactive_buy_stop_limit_order_successful() {
        Order order = new Order(100, security, Side.BUY, 30, 500, 0, broker1, shareholder, LocalDateTime.now(), OrderStatus.NEW, 1000, true);
        security.getOrderBook().enqueue(order);
        orderHandler.handleEnterOrder(EnterOrderRq.createUpdateOrderRq(1, "ABC", 100, LocalDateTime.now(), Side.BUY, 40, 500, broker1.getBrokerId(), shareholder.getShareholderId(), 0, 0, 1000, true));
        verify(eventPublisher).publish((new OrderUpdatedEvent(1, 100)));
        assertThat(broker1.getCredit()).isEqualTo(95000);
        assertThat(security.getOrderBook().findByOrderId(BUY, 100, true).getQuantity()).isEqualTo(40);
    }

    @Test
    void update_quantity_for_inactive_sell_stop_limit_order_successful() {
        Order order = new Order(100, security, SELL, 30, 500, 0, broker1, shareholder, LocalDateTime.now(), OrderStatus.NEW, 100, true);
        security.getOrderBook().enqueue(order);
        orderHandler.handleEnterOrder(EnterOrderRq.createUpdateOrderRq(1, "ABC", 100, LocalDateTime.now(), SELL, 40, 500, broker1.getBrokerId(), shareholder.getShareholderId(), 0, 0, 100, true));
        verify(eventPublisher).publish((new OrderUpdatedEvent(1, 100)));
        assertThat(broker1.getCredit()).isEqualTo(100_000);
        assertThat(security.getOrderBook().findByOrderId(SELL, 100, true).getQuantity()).isEqualTo(40);
    }

    @Test
    void update_price_for_inactive_buy_stop_limit_order_successful() {
        Order order = new Order(100, security, Side.BUY, 30, 500, 0, broker1, shareholder, LocalDateTime.now(), OrderStatus.NEW, 1000, true);
        security.getOrderBook().enqueue(order);
        orderHandler.handleEnterOrder(EnterOrderRq.createUpdateOrderRq(1, "ABC", 100, LocalDateTime.now(), Side.BUY, 30, 600, broker1.getBrokerId(), shareholder.getShareholderId(), 0, 0, 1000, true));
        verify(eventPublisher).publish((new OrderUpdatedEvent(1, 100)));
        assertThat(broker1.getCredit()).isEqualTo(97000);
        assertThat(security.getOrderBook().findByOrderId(BUY, 100, true).getPrice()).isEqualTo(600);
    }

    @Test
    void update_price_for_inactive_sell_stop_limit_order_successful() {
        Order order = new Order(100, security, SELL, 30, 500, 0, broker1, shareholder, LocalDateTime.now(), OrderStatus.NEW, 100, true);
        security.getOrderBook().enqueue(order);
        orderHandler.handleEnterOrder(EnterOrderRq.createUpdateOrderRq(1, "ABC", 100, LocalDateTime.now(), SELL, 30, 600, broker1.getBrokerId(), shareholder.getShareholderId(), 0, 0, 100, true));
        verify(eventPublisher).publish((new OrderUpdatedEvent(1, 100)));
        assertThat(broker1.getCredit()).isEqualTo(100_000);
        assertThat(security.getOrderBook().findByOrderId(SELL, 100, true).getPrice()).isEqualTo(600);
    }

    @Test
    void update_stop_price_for_buy_inactive_stop_limit_order_successful() {
        Order order = new Order(100, security, Side.BUY, 30, 500, 0, broker1, shareholder, LocalDateTime.now(), OrderStatus.NEW, 1000, true);
        security.getOrderBook().enqueue(order);
        orderHandler.handleEnterOrder(EnterOrderRq.createUpdateOrderRq(1, "ABC", 100, LocalDateTime.now(), Side.BUY, 30, 500, broker1.getBrokerId(), shareholder.getShareholderId(), 0, 0, 2000, true));
        verify(eventPublisher).publish((new OrderUpdatedEvent(1, 100)));
        assertThat(broker1.getCredit()).isEqualTo(100_000);
        assertThat(security.getOrderBook().findByOrderId(BUY, 100, true).getStopPrice()).isEqualTo(2000);
    }

    @Test
    void update_stop_price_for_sell_inactive_stop_limit_order_successful() {
        Order order = new Order(100, security, Side.SELL, 30, 500, 0, broker1, shareholder, LocalDateTime.now(), OrderStatus.NEW, 100, true);
        security.getOrderBook().enqueue(order);
        orderHandler.handleEnterOrder(EnterOrderRq.createUpdateOrderRq(1, "ABC", 100, LocalDateTime.now(), Side.SELL, 30, 500, broker1.getBrokerId(), shareholder.getShareholderId(), 0, 0, 200, true));
        verify(eventPublisher).publish((new OrderUpdatedEvent(1, 100)));
        assertThat(broker1.getCredit()).isEqualTo(100_000);
        assertThat(security.getOrderBook().findByOrderId(SELL, 100, true).getStopPrice()).isEqualTo(200);
    }

    @Test
    void update_stop_price_for_active_buy_stop_limit_order_fails() {
        Order order = new Order(100, security, Side.BUY, 30, 500, 0, broker1, shareholder, LocalDateTime.now(), OrderStatus.NEW, 1000, false);
        security.getOrderBook().enqueue(order);
        orderHandler.handleEnterOrder(EnterOrderRq.createUpdateOrderRq(1, "ABC", 100, LocalDateTime.now(), Side.BUY, 30, 500, broker1.getBrokerId(), shareholder.getShareholderId(), 0, 0, 2000, false));
        verify(eventPublisher).publish((new OrderRejectedEvent(1, 100, List.of(Message.INVALID_UPDATE_STOP_PRICE))));
        assertThat(broker1.getCredit()).isEqualTo(100_000);
        assertThat(security.getOrderBook().findByOrderId(BUY, 100, false).getStopPrice()).isEqualTo(1000);
    }

    @Test
    void update_stop_price_for_active_sell_stop_limit_order_fails() {
        Order order = new Order(100, security, Side.SELL, 30, 500, 0, broker1, shareholder, LocalDateTime.now(), OrderStatus.NEW, 1000, false);
        security.getOrderBook().enqueue(order);
        orderHandler.handleEnterOrder(EnterOrderRq.createUpdateOrderRq(1, "ABC", 100, LocalDateTime.now(), Side.SELL, 30, 500, broker1.getBrokerId(), shareholder.getShareholderId(), 0, 0, 2000, false));
        verify(eventPublisher).publish((new OrderRejectedEvent(1, 100, List.of(Message.INVALID_UPDATE_STOP_PRICE))));
        assertThat(broker1.getCredit()).isEqualTo(100_000);
        assertThat(security.getOrderBook().findByOrderId(SELL, 100, false).getStopPrice()).isEqualTo(1000);
    }

    @Test
    void update_stop_price_for_buy_inactive_stop_limit_order_successful_and_activates() {
        Order order = new Order(100, security, Side.BUY, 30, 500, 0, broker1, shareholder, LocalDateTime.now(), OrderStatus.NEW, 1000, true);
        security.getOrderBook().enqueue(order);
        orderHandler.handleEnterOrder(EnterOrderRq.createUpdateOrderRq(1, "ABC", 100, LocalDateTime.now(), Side.BUY, 30, 500, broker1.getBrokerId(), shareholder.getShareholderId(), 0, 0, 200, true));
        verify(eventPublisher).publish((new OrderUpdatedEvent(1, 100)));
        verify(eventPublisher).publish((new OrderActivatedEvent(100)));
        assertThat(broker1.getCredit()).isEqualTo(85000);
        assertThat(security.getOrderBook().findByOrderId(BUY, 100, false).getStopPrice()).isEqualTo(200);
        assertThat(security.getOrderBook().getInactiveBuyQueue().size()).isEqualTo(0);
        assertThat(security.getOrderBook().getBuyQueue().size()).isEqualTo(1);
    }

    @Test
    void update_stop_price_for_sell_inactive_stop_limit_order_successful_and_activates() {
        Order order = new Order(100, security, Side.SELL, 30, 500, 0, broker1, shareholder, LocalDateTime.now(), OrderStatus.NEW, 1000, true);
        security.getOrderBook().enqueue(order);
        orderHandler.handleEnterOrder(EnterOrderRq.createUpdateOrderRq(1, "ABC", 100, LocalDateTime.now(), Side.SELL, 30, 500, broker1.getBrokerId(), shareholder.getShareholderId(), 0, 0, 2000, true));
        verify(eventPublisher).publish((new OrderUpdatedEvent(1, 100)));
        verify(eventPublisher).publish((new OrderActivatedEvent(100)));
        assertThat(broker1.getCredit()).isEqualTo(100_000);
        assertThat(security.getOrderBook().findByOrderId(SELL, 100, false).getStopPrice()).isEqualTo(2000);
        assertThat(security.getOrderBook().getInactiveSellQueue().size()).isEqualTo(0);
        assertThat(security.getOrderBook().getSellQueue().size()).isEqualTo(1);
    }

    @Test
    void stop_limit_buy_order_enters_inactivated() {
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 100, LocalDateTime.now(), Side.BUY, 30, 500, broker1.getBrokerId(), shareholder.getShareholderId(), 0, 0, 0, false));
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(2, "ABC", 200, LocalDateTime.now(), Side.SELL, 30, 500, broker2.getBrokerId(), shareholder.getShareholderId(), 0, 0, 0, false));
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(3, "ABC", 300, LocalDateTime.now(), Side.BUY, 30, 500, broker1.getBrokerId(), shareholder.getShareholderId(), 0, 0, 1000, true));
        verify(eventPublisher).publish((new OrderAcceptedEvent(3, 300)));
        assertThat(security.getOrderBook().getInactiveBuyQueue().size()).isEqualTo(1);
        assertThat(security.getOrderBook().getBuyQueue().size()).isEqualTo(0);
    }

    @Test
    void stop_limit_sell_order_enters_inactivated() {
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 100, LocalDateTime.now(), Side.BUY, 30, 500, broker1.getBrokerId(), shareholder.getShareholderId(), 0, 0, 0, false));
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(2, "ABC", 200, LocalDateTime.now(), Side.SELL, 30, 500, broker2.getBrokerId(), shareholder.getShareholderId(), 0, 0, 0, false));
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(3, "ABC", 300, LocalDateTime.now(), Side.SELL, 30, 500, broker1.getBrokerId(), shareholder.getShareholderId(), 0, 0, 100, false));
        verify(eventPublisher).publish((new OrderAcceptedEvent(3, 300)));
        assertThat(security.getOrderBook().getInactiveSellQueue().size()).isEqualTo(1);
        assertThat(security.getOrderBook().getSellQueue().size()).isEqualTo(0);
    }


    @Test
    void stop_limit_buy_order_enters_activated() {
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 100, LocalDateTime.now(), Side.BUY, 30, 500, broker1.getBrokerId(), shareholder.getShareholderId(), 0, 0, 0, false));
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(2, "ABC", 200, LocalDateTime.now(), Side.SELL, 30, 500, broker2.getBrokerId(), shareholder.getShareholderId(), 0, 0, 0, false));
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(3, "ABC", 300, LocalDateTime.now(), Side.BUY, 30, 500, broker1.getBrokerId(), shareholder.getShareholderId(), 0, 0, 100, true));
        verify(eventPublisher).publish((new OrderAcceptedEvent(3, 300)));
        assertThat(security.getOrderBook().getInactiveBuyQueue().size()).isEqualTo(0);
        assertThat(security.getOrderBook().getBuyQueue().size()).isEqualTo(1);
    }

    @Test
    void stop_limit_sell_order_enters_activated() {
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 100, LocalDateTime.now(), Side.BUY, 30, 500, broker1.getBrokerId(), shareholder.getShareholderId(), 0, 0, 0, false));
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(2, "ABC", 200, LocalDateTime.now(), Side.SELL, 30, 500, broker2.getBrokerId(), shareholder.getShareholderId(), 0, 0, 0, false));
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(3, "ABC", 300, LocalDateTime.now(), Side.SELL, 30, 500, broker1.getBrokerId(), shareholder.getShareholderId(), 0, 0, 1000, true));
        verify(eventPublisher).publish((new OrderAcceptedEvent(3, 300)));
        verify(eventPublisher).publish((new OrderActivatedEvent(300)));
        assertThat(security.getOrderBook().getInactiveSellQueue().size()).isEqualTo(0);
        assertThat(security.getOrderBook().getSellQueue().size()).isEqualTo(1);
    }

    @Test
    void stop_limit_buy_order_enters_activated_and_match() {
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 100, LocalDateTime.now(), Side.BUY, 30, 500, broker1.getBrokerId(), shareholder.getShareholderId(), 0, 0, 0, true));
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(2, "ABC", 200, LocalDateTime.now(), Side.SELL, 30, 500, broker2.getBrokerId(), shareholder.getShareholderId(), 0, 0, 0, true));
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(3, "ABC", 300, LocalDateTime.now(), Side.BUY, 30, 500, broker1.getBrokerId(), shareholder.getShareholderId(), 0, 0, 100, true));
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(4, "ABC", 400, LocalDateTime.now(), Side.SELL, 30, 500, broker2.getBrokerId(), shareholder.getShareholderId(), 0, 0, 0, true));
        verify(eventPublisher).publish((new OrderAcceptedEvent(3, 300)));
        verify(eventPublisher).publish((new OrderActivatedEvent(300)));
        Order stopLimitOrder = new Order(300, security, Side.BUY, 30, 500, 0, broker1, shareholder, 100);
        Order matchWithStopLimitOrder = new Order(400, security, Side.SELL, 30, 500, 0, broker2, shareholder, 0);
        Trade trade = new Trade(security, 500, 30,matchWithStopLimitOrder , stopLimitOrder);
        verify(eventPublisher).publish(new OrderExecutedEvent(4, 400, List.of(new TradeDTO(trade))));
        assertThat(security.getOrderBook().getInactiveBuyQueue().size()).isEqualTo(0);
        assertThat(security.getOrderBook().getBuyQueue().size()).isEqualTo(0);
    }

    @Test
    void stop_limit_sell_order_enters_activated_and_match() {
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 100, LocalDateTime.now(), Side.BUY, 30, 500, broker1.getBrokerId(), shareholder.getShareholderId(), 0, 0, 0, false));
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(2, "ABC", 200, LocalDateTime.now(), Side.SELL, 30, 500, broker2.getBrokerId(), shareholder.getShareholderId(), 0, 0, 0, false));
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(3, "ABC", 300, LocalDateTime.now(), Side.SELL, 30, 500, broker1.getBrokerId(), shareholder.getShareholderId(), 0, 0, 1000, true));
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(4, "ABC", 400, LocalDateTime.now(), Side.BUY, 30, 500, broker2.getBrokerId(), shareholder.getShareholderId(), 0, 0, 0, false));
        verify(eventPublisher).publish((new OrderAcceptedEvent(3, 300)));
        verify(eventPublisher).publish((new OrderActivatedEvent(300)));
        Order stopLimitOrder = new Order(300, security, Side.SELL, 30, 500, 0, broker1, shareholder, 1000);
        Order matchWithStopLimitOrder = new Order(400, security, Side.BUY, 30, 500, 0, broker2, shareholder, 0);
        Trade trade = new Trade(security, 500, 30,matchWithStopLimitOrder , stopLimitOrder);
        verify(eventPublisher).publish(new OrderExecutedEvent(4, 400, List.of(new TradeDTO(trade))));
        assertThat(security.getOrderBook().getInactiveBuyQueue().size()).isEqualTo(0);
        assertThat(security.getOrderBook().getBuyQueue().size()).isEqualTo(0);
    }

    @Test
    void stop_limit_buy_order_enters_inactivated_and_activates_later() {
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(3, "ABC", 300, LocalDateTime.now(), Side.BUY, 30, 500, broker1.getBrokerId(), shareholder.getShareholderId(), 0, 0, 400, true));
        assertThat(security.getOrderBook().getInactiveBuyQueue().size()).isEqualTo(1);
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 100, LocalDateTime.now(), Side.BUY, 30, 500, broker1.getBrokerId(), shareholder.getShareholderId(), 0, 0, 0, false));
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(2, "ABC", 200, LocalDateTime.now(), Side.SELL, 30, 500, broker2.getBrokerId(), shareholder.getShareholderId(), 0, 0, 0, false));
        verify(eventPublisher).publish((new OrderAcceptedEvent(3, 300)));
        verify(eventPublisher).publish((new OrderActivatedEvent(300)));
        assertThat(security.getOrderBook().getInactiveBuyQueue().size()).isEqualTo(0);
        assertThat(security.getOrderBook().getBuyQueue().size()).isEqualTo(1);
    }


    @Test
    void stop_limit_sell_order_enters_inactivated_and_activates_later() {
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(3, "ABC", 300, LocalDateTime.now(), Side.SELL, 30, 500, broker1.getBrokerId(), shareholder.getShareholderId(), 0, 0, 200, true));
        assertThat(security.getOrderBook().getInactiveSellQueue().size()).isEqualTo(1);
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 100, LocalDateTime.now(), Side.BUY, 30, 100, broker1.getBrokerId(), shareholder.getShareholderId(), 0, 0, 0, false));
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(2, "ABC", 200, LocalDateTime.now(), Side.SELL, 30, 100, broker2.getBrokerId(), shareholder.getShareholderId(), 0, 0, 0, false));
        verify(eventPublisher).publish((new OrderAcceptedEvent(3, 300)));
        verify(eventPublisher).publish((new OrderActivatedEvent(300)));
        assertThat(security.getOrderBook().getInactiveSellQueue().size()).isEqualTo(0);
        assertThat(security.getOrderBook().getSellQueue().size()).isEqualTo(1);
    }

    @Test
    void stop_limit_buy_order_enters_inactivated_and_activates_later_and_match() {
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(3, "ABC", 300, LocalDateTime.now(), Side.BUY, 30, 500, broker1.getBrokerId(), shareholder.getShareholderId(), 0, 0, 400, true));
        assertThat(security.getOrderBook().getInactiveBuyQueue().size()).isEqualTo(1);
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 100, LocalDateTime.now(), Side.BUY, 30, 500, broker1.getBrokerId(), shareholder.getShareholderId(), 0, 0, 0, false));
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(2, "ABC", 200, LocalDateTime.now(), Side.SELL, 30, 500, broker2.getBrokerId(), shareholder.getShareholderId(), 0, 0, 0, false));
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(4, "ABC", 400, LocalDateTime.now(), Side.SELL, 30, 500, broker2.getBrokerId(), shareholder.getShareholderId(), 0, 0, 0, false));
        Order stopLimitOrder = new Order(300, security, Side.BUY, 30, 500, 0, broker1, shareholder, 200);
        Order matchWithStopLimitOrder = new Order(400, security, Side.SELL, 30, 500, 0, broker2, shareholder, 0);
        Trade trade = new Trade(security, 500, 30,matchWithStopLimitOrder , stopLimitOrder);
        verify(eventPublisher).publish((new OrderActivatedEvent(300)));
        verify(eventPublisher).publish(new OrderExecutedEvent(4, 400, List.of(new TradeDTO(trade))));
    }

    @Test
    void stop_limit_sell_order_enters_inactivated_and_activates_later_and_match() {
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(3, "ABC", 300, LocalDateTime.now(), Side.SELL, 30, 500, broker1.getBrokerId(), shareholder.getShareholderId(), 0, 0, 200, true));
        assertThat(security.getOrderBook().getInactiveSellQueue().size()).isEqualTo(1);
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 100, LocalDateTime.now(), Side.BUY, 30, 100, broker1.getBrokerId(), shareholder.getShareholderId(), 0, 0, 0, false));
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(2, "ABC", 200, LocalDateTime.now(), Side.SELL, 30, 100, broker2.getBrokerId(), shareholder.getShareholderId(), 0, 0, 0, false));
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(4, "ABC", 400, LocalDateTime.now(), Side.BUY, 30, 500, broker2.getBrokerId(), shareholder.getShareholderId(), 0, 0, 0, false));
        Order stopLimitOrder = new Order(300, security, Side.SELL, 30, 500, 0, broker1, shareholder, 200);
        Order matchWithStopLimitOrder = new Order(400, security, Side.BUY, 30, 500, 0, broker2, shareholder, 0);
        Trade trade = new Trade(security, 500, 30,matchWithStopLimitOrder , stopLimitOrder);
        verify(eventPublisher).publish((new OrderActivatedEvent(300)));
        verify(eventPublisher).publish(new OrderExecutedEvent(4, 400, List.of(new TradeDTO(trade))));
    }

    @Test
    void stop_limits_buy_order_enters_inactivated_and_several_activations_later() {
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(3, "ABC", 300, LocalDateTime.now(), Side.BUY, 30, 500, broker1.getBrokerId(), shareholder.getShareholderId(), 0, 0, 400, true));
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(5, "ABC", 500, LocalDateTime.now(), Side.BUY, 30, 500, broker1.getBrokerId(), shareholder.getShareholderId(), 0, 0, 450, true));
        assertThat(security.getOrderBook().getInactiveBuyQueue().size()).isEqualTo(2);
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 100, LocalDateTime.now(), Side.BUY, 30, 500, broker1.getBrokerId(), shareholder.getShareholderId(), 0, 0, 0, true));
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(2, "ABC", 200, LocalDateTime.now(), Side.SELL, 30, 500, broker2.getBrokerId(), shareholder.getShareholderId(), 0, 0, 0, true));
        verify(eventPublisher).publish((new OrderActivatedEvent(300)));
        verify(eventPublisher).publish((new OrderActivatedEvent(500)));
        assertThat(security.getOrderBook().getInactiveBuyQueue().size()).isEqualTo(0);
        assertThat(security.getOrderBook().getBuyQueue().size()).isEqualTo(2);
    }

    @Test
    void stop_limits_sell_order_enters_inactivated_and_several_activations_later() {
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(3, "ABC", 300, LocalDateTime.now(), Side.SELL, 30, 500, broker1.getBrokerId(), shareholder.getShareholderId(), 0, 0, 200, true));
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(5, "ABC", 500, LocalDateTime.now(), Side.SELL, 30, 500, broker1.getBrokerId(), shareholder.getShareholderId(), 0, 0, 230, true));
        assertThat(security.getOrderBook().getInactiveSellQueue().size()).isEqualTo(2);
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 100, LocalDateTime.now(), Side.BUY, 30, 100, broker1.getBrokerId(), shareholder.getShareholderId(), 0, 0, 0, true));
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(2, "ABC", 200, LocalDateTime.now(), Side.SELL, 30, 100, broker2.getBrokerId(), shareholder.getShareholderId(), 0, 0, 0, true));
        verify(eventPublisher).publish((new OrderActivatedEvent(300)));
        verify(eventPublisher).publish((new OrderActivatedEvent(500)));
        assertThat(security.getOrderBook().getInactiveSellQueue().size()).isEqualTo(0);
        assertThat(security.getOrderBook().getSellQueue().size()).isEqualTo(2);
    }

    @Test
    void stop_limits_buy_order_enters_inactivated_and_activate_and_match_later_causing_new_buy_activation() {
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(3, "ABC", 300, LocalDateTime.now(), Side.BUY, 30, 600, broker1.getBrokerId(), shareholder.getShareholderId(), 0, 0, 400, true));
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(5, "ABC", 500, LocalDateTime.now(), Side.BUY, 30, 100, broker1.getBrokerId(), shareholder.getShareholderId(), 0, 0, 550, true));
        assertThat(security.getOrderBook().getInactiveBuyQueue().size()).isEqualTo(2);
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 100, LocalDateTime.now(), Side.BUY, 30, 500, broker1.getBrokerId(), shareholder.getShareholderId(), 0, 0, 0, true));
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(2, "ABC", 200, LocalDateTime.now(), Side.SELL, 30, 500, broker2.getBrokerId(), shareholder.getShareholderId(), 0, 0, 0, true));
        assertThat(security.getOrderBook().getInactiveBuyQueue().size()).isEqualTo(1);
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(4, "ABC", 400, LocalDateTime.now(), Side.SELL, 30, 600, broker2.getBrokerId(), shareholder.getShareholderId(), 0, 0, 0, true));
        verify(eventPublisher).publish((new OrderActivatedEvent(500)));
        assertThat(security.getOrderBook().getInactiveBuyQueue().size()).isEqualTo(0);
    }

    @Test
    void stop_limits_sell_order_enters_inactivated_and_activate_and_match_later_causing_new_buy_activation() {
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(3, "ABC", 300, LocalDateTime.now(), Side.SELL, 30, 500, broker1.getBrokerId(), shareholder.getShareholderId(), 0, 0, 200, true));
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(5, "ABC", 500, LocalDateTime.now(), Side.BUY, 30, 50, broker1.getBrokerId(), shareholder.getShareholderId(), 0, 0, 400, true));
        assertThat(security.getOrderBook().getInactiveBuyQueue().size()).isEqualTo(1);
        assertThat(security.getOrderBook().getInactiveSellQueue().size()).isEqualTo(1);
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 100, LocalDateTime.now(), Side.BUY, 30, 170, broker1.getBrokerId(), shareholder.getShareholderId(), 0, 0, 0, true));
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(2, "ABC", 200, LocalDateTime.now(), Side.SELL, 30, 170, broker2.getBrokerId(), shareholder.getShareholderId(), 0, 0, 0, true));
        assertThat(security.getOrderBook().getInactiveBuyQueue().size()).isEqualTo(1);
        assertThat(security.getOrderBook().getInactiveSellQueue().size()).isEqualTo(0);
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(4, "ABC", 400, LocalDateTime.now(), Side.BUY, 30, 500, broker2.getBrokerId(), shareholder.getShareholderId(), 0, 0, 0, true));
        verify(eventPublisher).publish((new OrderActivatedEvent(500)));
        assertThat(security.getOrderBook().getInactiveBuyQueue().size()).isEqualTo(0);
    }

    @Test
    void stop_limits_buy_order_enters_inactivated_and_activate_and_match_later_causing_new_sell_activation() {
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(3, "ABC", 300, LocalDateTime.now(), Side.BUY, 30, 150, broker1.getBrokerId(), shareholder.getShareholderId(), 0, 0, 400, true));
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(5, "ABC", 500, LocalDateTime.now(), Side.SELL, 30, 1000, broker1.getBrokerId(), shareholder.getShareholderId(), 0, 0, 200, true));
        assertThat(security.getOrderBook().getInactiveBuyQueue().size()).isEqualTo(1);
        assertThat(security.getOrderBook().getInactiveSellQueue().size()).isEqualTo(1);
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 100, LocalDateTime.now(), Side.BUY, 30, 500, broker1.getBrokerId(), shareholder.getShareholderId(), 0, 0, 0, true));
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(2, "ABC", 200, LocalDateTime.now(), Side.SELL, 30, 500, broker2.getBrokerId(), shareholder.getShareholderId(), 0, 0, 0, true));
        assertThat(security.getOrderBook().getInactiveSellQueue().size()).isEqualTo(1);
        assertThat(security.getOrderBook().getInactiveBuyQueue().size()).isEqualTo(0);
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(4, "ABC", 400, LocalDateTime.now(), Side.SELL, 30, 150, broker2.getBrokerId(), shareholder.getShareholderId(), 0, 0, 0, true));
        verify(eventPublisher).publish((new OrderActivatedEvent(500)));
        assertThat(security.getOrderBook().getInactiveSellQueue().size()).isEqualTo(0);
    }

    @Test
    void stop_limits_sell_order_enters_inactivated_and_activate_and_match_later_causing_new_sell_activation() {
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(3, "ABC", 300, LocalDateTime.now(), Side.SELL, 30, 100, broker1.getBrokerId(), shareholder.getShareholderId(), 0, 0, 290, true));
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(5, "ABC", 500, LocalDateTime.now(), Side.SELL, 30, 1000, broker1.getBrokerId(), shareholder.getShareholderId(), 0, 0, 200, true));
        assertThat(security.getOrderBook().getInactiveSellQueue().size()).isEqualTo(2);
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 100, LocalDateTime.now(), Side.BUY, 30, 250, broker1.getBrokerId(), shareholder.getShareholderId(), 0, 0, 0, true));
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(2, "ABC", 200, LocalDateTime.now(), Side.SELL, 30, 250, broker2.getBrokerId(), shareholder.getShareholderId(), 0, 0, 0, true));
        assertThat(security.getOrderBook().getInactiveSellQueue().size()).isEqualTo(1);
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(4, "ABC", 400, LocalDateTime.now(), Side.BUY, 30, 100, broker2.getBrokerId(), shareholder.getShareholderId(), 0, 0, 0, true));
        verify(eventPublisher).publish((new OrderActivatedEvent(500)));
        assertThat(security.getOrderBook().getInactiveSellQueue().size()).isEqualTo(0);
    }

    @Test
    void delete_inactivated_stop_limit_buy_order() {
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(3, "ABC", 300, LocalDateTime.now(), Side.BUY, 30, 150, broker1.getBrokerId(), shareholder.getShareholderId(), 0, 0, 400, true));
        assertThat(security.getOrderBook().getInactiveBuyQueue().size()).isEqualTo(1);
        orderHandler.handleDeleteOrder(new DeleteOrderRq(3, security.getIsin(), Side.BUY, 300, 400, true));
        verify(eventPublisher).publish(new OrderDeletedEvent(3, 300));
        assertThat(security.getOrderBook().getInactiveBuyQueue().size()).isEqualTo(0);
        assertThat(broker1.getCredit()).isEqualTo(100_000);
    }

    @Test
    void delete_inactivated_stop_limit_sell_order() {
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(3, "ABC", 300, LocalDateTime.now(), Side.SELL, 30, 150, broker1.getBrokerId(), shareholder.getShareholderId(), 0, 0, 40, true));
        assertThat(security.getOrderBook().getInactiveSellQueue().size()).isEqualTo(1);
        orderHandler.handleDeleteOrder(new DeleteOrderRq(3, security.getIsin(), Side.SELL, 300, 40, true));
        verify(eventPublisher).publish(new OrderDeletedEvent(3, 300));
        assertThat(security.getOrderBook().getInactiveSellQueue().size()).isEqualTo(0);
        assertThat(broker1.getCredit()).isEqualTo(100_000);
    }

    @Test
    void delete_activated_stop_limit_buy_order() {
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(3, "ABC", 300, LocalDateTime.now(), Side.BUY, 30, 150, broker1.getBrokerId(), shareholder.getShareholderId(), 0, 0, 40, true));
        assertThat(security.getOrderBook().getBuyQueue().size()).isEqualTo(1);
        orderHandler.handleDeleteOrder(new DeleteOrderRq(3, security.getIsin(), Side.BUY, 300, 40, false));
        verify(eventPublisher).publish(new OrderDeletedEvent(3, 300));
        assertThat(security.getOrderBook().getBuyQueue().size()).isEqualTo(0);
        assertThat(broker1.getCredit()).isEqualTo(100_000);
    }

    @Test
    void delete_activated_stop_limit_sell_order() {
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(3, "ABC", 300, LocalDateTime.now(), Side.SELL, 30, 150, broker1.getBrokerId(), shareholder.getShareholderId(), 0, 0, 400, true));
        assertThat(security.getOrderBook().getSellQueue().size()).isEqualTo(1);
        orderHandler.handleDeleteOrder(new DeleteOrderRq(3, security.getIsin(), Side.SELL, 300, 400, false));
        verify(eventPublisher).publish(new OrderDeletedEvent(3, 300));
        assertThat(security.getOrderBook().getSellQueue().size()).isEqualTo(0);
        assertThat(broker1.getCredit()).isEqualTo(100_000);
    }
}
