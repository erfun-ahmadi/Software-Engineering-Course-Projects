package ir.ramtung.tinyme.domain;

import ir.ramtung.tinyme.config.MockedJMSTestConfig;
import ir.ramtung.tinyme.domain.entity.*;
import ir.ramtung.tinyme.domain.service.AuctionMatcher;
import ir.ramtung.tinyme.domain.service.ContinuousMatcher;
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
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

import static ir.ramtung.tinyme.domain.entity.Side.BUY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@SpringBootTest
@Import(MockedJMSTestConfig.class)
@DirtiesContext
public class OrderHandlerTest {
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
    private Broker broker3;

    @BeforeEach
    void setup() {
        securityRepository.clear();
        brokerRepository.clear();
        shareholderRepository.clear();

        security = Security.builder().isin("ABC").build();
        securityRepository.addSecurity(security);

        shareholder = Shareholder.builder().build();
        shareholder.incPosition(security, 100_000);
        shareholderRepository.addShareholder(shareholder);

        broker1 = Broker.builder().brokerId(1).build();
        broker2 = Broker.builder().brokerId(2).build();
        broker3 = Broker.builder().brokerId(2).build();
        brokerRepository.addBroker(broker1);
        brokerRepository.addBroker(broker2);
        brokerRepository.addBroker(broker3);
    }
    @Test
    void new_order_matched_completely_with_one_trade() {
        Order matchingBuyOrder = Order.builder().orderId(100).security(security).side(Side.BUY).quantity(1000).price(15550).
                minimumExecutionQuantity(0).broker(broker1).
                shareholder(shareholder).stopPrice(0).build();
        Order incomingSellOrder = Order.builder().orderId(200).security(security).side(Side.SELL).quantity(300).price(15450).
                minimumExecutionQuantity(0).broker(broker2).
                shareholder(shareholder).stopPrice(0).build();
        security.getOrderBook().enqueue(matchingBuyOrder);

        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 200, LocalDateTime.now(), Side.SELL, 300, 15450, 2, shareholder.getShareholderId(), 0, 0, 0, false));

        Trade trade = new Trade(security, matchingBuyOrder.getPrice(), incomingSellOrder.getQuantity(),
                matchingBuyOrder, incomingSellOrder);
        verify(eventPublisher).publish((new OrderAcceptedEvent(1, 200)));
        verify(eventPublisher).publish(new OrderExecutedEvent(1, 200, List.of(new TradeDTO(trade))));
    }

    @Test
    void new_order_queued_with_no_trade() {
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 200, LocalDateTime.now(), Side.SELL, 300, 15450, 2, shareholder.getShareholderId(), 0, 0, 0, false));
        verify(eventPublisher).publish(new OrderAcceptedEvent(1, 200));
    }
    @Test
    void new_order_matched_partially_with_two_trades() {
        Order matchingBuyOrder1 = Order.builder().orderId(100).security(security).side(Side.BUY).quantity(300).price(15500).
                minimumExecutionQuantity(0).broker(broker1).
                shareholder(shareholder).stopPrice(0).build();
        Order matchingBuyOrder2 = Order.builder().orderId(110).security(security).side(Side.BUY).quantity(300).price(15500).
                minimumExecutionQuantity(0).broker(broker1).
                shareholder(shareholder).stopPrice(0).build();
        Order incomingSellOrder = Order.builder().orderId(200).security(security).side(Side.SELL).quantity(1000).price(15450).
                minimumExecutionQuantity(0).broker(broker2).
                shareholder(shareholder).stopPrice(0).build();
        security.getOrderBook().enqueue(matchingBuyOrder1);
        security.getOrderBook().enqueue(matchingBuyOrder2);

        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1,
                incomingSellOrder.getSecurity().getIsin(),
                incomingSellOrder.getOrderId(),
                incomingSellOrder.getEntryTime(),
                incomingSellOrder.getSide(),
                incomingSellOrder.getTotalQuantity(),
                incomingSellOrder.getPrice(),
                incomingSellOrder.getBroker().getBrokerId(),
                incomingSellOrder.getShareholder().getShareholderId(), 0, 0));

        Trade trade1 = new Trade(security, matchingBuyOrder1.getPrice(), matchingBuyOrder1.getQuantity(),
                matchingBuyOrder1, incomingSellOrder);
        Trade trade2 = new Trade(security, matchingBuyOrder2.getPrice(), matchingBuyOrder2.getQuantity(),
                matchingBuyOrder2, incomingSellOrder.snapshotWithQuantity(700));
        verify(eventPublisher).publish(new OrderAcceptedEvent(1, 200));
        verify(eventPublisher).publish(new OrderExecutedEvent(1, 200, List.of(new TradeDTO(trade1), new TradeDTO(trade2))));
    }

    @Test
    void iceberg_order_behaves_normally_before_being_queued() {
        Order matchingBuyOrder = Order.builder().orderId(100).security(security).side(Side.BUY).quantity(1000).price(15500).
                minimumExecutionQuantity(0).broker(broker1).
                shareholder(shareholder).stopPrice(0).build();
        Order incomingSellOrder = IcebergOrder.builder().orderId(200).security(security).side(Side.SELL).quantity(300).price(15450).
                minimumExecutionQuantity(0).broker(broker2).
                shareholder(shareholder).peakSize(100).stopPrice(0).build();
        security.getOrderBook().enqueue(matchingBuyOrder);
        Trade trade = new Trade(security, matchingBuyOrder.getPrice(), incomingSellOrder.getQuantity(),
                matchingBuyOrder, incomingSellOrder);

        EventPublisher mockEventPublisher = mock(EventPublisher.class, withSettings().verboseLogging());
        OrderHandler myOrderHandler = new OrderHandler(securityRepository, brokerRepository, shareholderRepository, mockEventPublisher, new ContinuousMatcher(), new AuctionMatcher());
        myOrderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1,
                incomingSellOrder.getSecurity().getIsin(),
                incomingSellOrder.getOrderId(),
                incomingSellOrder.getEntryTime(),
                incomingSellOrder.getSide(),
                incomingSellOrder.getTotalQuantity(),
                incomingSellOrder.getPrice(),
                incomingSellOrder.getBroker().getBrokerId(),
                incomingSellOrder.getShareholder().getShareholderId(), 100, 0));

        verify(mockEventPublisher).publish(new OrderAcceptedEvent(1, 200));
        verify(mockEventPublisher).publish(new OrderExecutedEvent(1, 200, List.of(new TradeDTO(trade))));
    }

    @Test
    void invalid_new_order_with_multiple_errors() {
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "XXX", -1, LocalDateTime.now(), Side.SELL, 0, 0, -1, -1, 0, 0, 0, false));
        ArgumentCaptor<OrderRejectedEvent> orderRejectedCaptor = ArgumentCaptor.forClass(OrderRejectedEvent.class);
        verify(eventPublisher).publish(orderRejectedCaptor.capture());
        OrderRejectedEvent outputEvent = orderRejectedCaptor.getValue();
        assertThat(outputEvent.getOrderId()).isEqualTo(-1);
        assertThat(outputEvent.getErrors()).containsOnly(
                Message.UNKNOWN_SECURITY_ISIN,
                Message.INVALID_ORDER_ID,
                Message.ORDER_PRICE_NOT_POSITIVE,
                Message.ORDER_QUANTITY_NOT_POSITIVE,
                Message.INVALID_PEAK_SIZE,
                Message.UNKNOWN_BROKER_ID,
                Message.UNKNOWN_SHAREHOLDER_ID
        );
    }

    @Test
    void invalid_new_order_with_negative_minimum_execution_quantity() {
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, security.getIsin(), 100, LocalDateTime.now(), Side.SELL, 300, 15450, broker1.getBrokerId(), shareholder.getShareholderId(), 0, -1, 0, false));
        ArgumentCaptor<OrderRejectedEvent> orderRejectedCaptor = ArgumentCaptor.forClass(OrderRejectedEvent.class);
        verify(eventPublisher).publish(orderRejectedCaptor.capture());
        OrderRejectedEvent outputEvent = orderRejectedCaptor.getValue();
        assertThat(outputEvent.getOrderId()).isEqualTo(100);
        assertThat(outputEvent.getErrors()).containsOnly(Message.INVALID_ORDER_MINIMUM_EXECUTION_QUANTITY);
    }

    @Test
    void invalid_new_order_with_minimum_execution_quantity_greater_than_quantity() {
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, security.getIsin(), 100, LocalDateTime.now(), Side.SELL, 300, 15450, broker1.getBrokerId(), shareholder.getShareholderId(), 0, 301, 0, false));
        ArgumentCaptor<OrderRejectedEvent> orderRejectedCaptor = ArgumentCaptor.forClass(OrderRejectedEvent.class);
        verify(eventPublisher).publish(orderRejectedCaptor.capture());
        OrderRejectedEvent outputEvent = orderRejectedCaptor.getValue();
        assertThat(outputEvent.getOrderId()).isEqualTo(100);
        assertThat(outputEvent.getErrors()).containsOnly(Message.INVALID_ORDER_MINIMUM_EXECUTION_QUANTITY);
    }

    @Test
    void invalid_new_order_with_tick_and_lot_size_errors() {
        Security aSecurity = Security.builder().isin("XXX").lotSize(10).tickSize(10).build();
        securityRepository.addSecurity(aSecurity);
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "XXX", 1, LocalDateTime.now(), Side.SELL, 12, 1001, 1, shareholder.getShareholderId(), 0, 0, 0, false));
        ArgumentCaptor<OrderRejectedEvent> orderRejectedCaptor = ArgumentCaptor.forClass(OrderRejectedEvent.class);
        verify(eventPublisher).publish(orderRejectedCaptor.capture());
        OrderRejectedEvent outputEvent = orderRejectedCaptor.getValue();
        assertThat(outputEvent.getOrderId()).isEqualTo(1);
        assertThat(outputEvent.getErrors()).containsOnly(
                Message.QUANTITY_NOT_MULTIPLE_OF_LOT_SIZE,
                Message.PRICE_NOT_MULTIPLE_OF_TICK_SIZE
        );
    }

    @Test
    void update_order_causing_no_trades() {
        Order queuedOrder = Order.builder().orderId(200).security(security).side(Side.SELL).quantity(500).price(15450).
                minimumExecutionQuantity(0).broker(broker1).
                shareholder(shareholder).stopPrice(0).build();
        security.getOrderBook().enqueue(queuedOrder);
        orderHandler.handleEnterOrder(EnterOrderRq.createUpdateOrderRq(1, "ABC", 200, LocalDateTime.now(), Side.SELL, 1000, 15450, 1, shareholder.getShareholderId(), 0, 0, 0, false));
        verify(eventPublisher).publish(new OrderUpdatedEvent(1, 200));
    }

    @Test
    void handle_valid_update_with_trades() {
        Order matchingOrder = Order.builder().orderId(1).security(security).side(Side.BUY).quantity(500).price(15450).
                minimumExecutionQuantity(0).broker(broker1).
                shareholder(shareholder).stopPrice(0).build();
        Order beforeUpdate = Order.builder().orderId(200).security(security).side(Side.SELL).quantity(1000).price(15455).
                minimumExecutionQuantity(0).broker(broker2).
                shareholder(shareholder).stopPrice(0).build();
        Order afterUpdate = Order.builder().orderId(200).security(security).side(Side.SELL).quantity(500).price(15450).
                minimumExecutionQuantity(0).broker(broker2).
                shareholder(shareholder).stopPrice(0).build();
        security.getOrderBook().enqueue(matchingOrder);
        security.getOrderBook().enqueue(beforeUpdate);

        orderHandler.handleEnterOrder(EnterOrderRq.createUpdateOrderRq(1, "ABC", 200, LocalDateTime.now(), Side.SELL, 1000, 15450, broker2.getBrokerId(), shareholder.getShareholderId(), 0, 0, 0, false));

        Trade trade = new Trade(security, 15450, 500, matchingOrder, afterUpdate);
        verify(eventPublisher).publish(new OrderUpdatedEvent(1, 200));
        verify(eventPublisher).publish(new OrderExecutedEvent(1, 200, List.of(new TradeDTO(trade))));
    }

    @Test
    void invalid_update_with_order_id_not_found() {
        orderHandler.handleEnterOrder(EnterOrderRq.createUpdateOrderRq(1, "ABC", 200, LocalDateTime.now(), Side.SELL, 1000, 15450, 1, shareholder.getShareholderId(), 0, 0, 0, false));
        verify(eventPublisher).publish(new OrderRejectedEvent(1, 200, any()));
    }

    @Test
    void invalid_update_with_multiple_errors() {
        orderHandler.handleEnterOrder(EnterOrderRq.createUpdateOrderRq(1, "XXX", -1, LocalDateTime.now(), Side.SELL, 0, 0, -1, shareholder.getShareholderId(), 0, 0, 0, false));
        ArgumentCaptor<OrderRejectedEvent> orderRejectedCaptor = ArgumentCaptor.forClass(OrderRejectedEvent.class);
        verify(eventPublisher).publish(orderRejectedCaptor.capture());
        OrderRejectedEvent outputEvent = orderRejectedCaptor.getValue();
        assertThat(outputEvent.getOrderId()).isEqualTo(-1);
        assertThat(outputEvent.getErrors()).containsOnly(
                Message.UNKNOWN_SECURITY_ISIN,
                Message.UNKNOWN_BROKER_ID,
                Message.INVALID_ORDER_ID,
                Message.ORDER_PRICE_NOT_POSITIVE,
                Message.ORDER_QUANTITY_NOT_POSITIVE,
                Message.INVALID_PEAK_SIZE
        );
    }

    @Test
    void delete_buy_order_deletes_successfully_and_increases_credit() {
        Broker buyBroker = Broker.builder().credit(1_000_000).build();
        brokerRepository.addBroker(buyBroker);
        Order someOrder = Order.builder().orderId(100).security(security).side(Side.BUY).quantity(300).price(15500).
                minimumExecutionQuantity(0).broker(buyBroker).
                shareholder(shareholder).stopPrice(0).build();
        Order queuedOrder = Order.builder().orderId(200).security(security).side(Side.BUY).quantity(1000).price(15500).
                minimumExecutionQuantity(0).broker(buyBroker).
                shareholder(shareholder).stopPrice(0).build();
        security.getOrderBook().enqueue(someOrder);
        security.getOrderBook().enqueue(queuedOrder);
        orderHandler.handleDeleteOrder(new DeleteOrderRq(1, security.getIsin(), Side.BUY, 200));
        verify(eventPublisher).publish(new OrderDeletedEvent(1, 200));
        assertThat(buyBroker.getCredit()).isEqualTo(1_000_000 + 1000*15500);
    }

    @Test
    void delete_sell_order_deletes_successfully_and_does_not_change_credit() {
        Broker sellBroker = Broker.builder().credit(1_000_000).build();
        brokerRepository.addBroker(sellBroker);
        Order someOrder = Order.builder().orderId(100).security(security).side(Side.SELL).quantity(300).price(15500).
                minimumExecutionQuantity(0).broker(sellBroker).
                shareholder(shareholder).stopPrice(0).build();
        Order queuedOrder = Order.builder().orderId(200).security(security).side(Side.SELL).quantity(1000).price(15500).
                minimumExecutionQuantity(0).broker(sellBroker).
                shareholder(shareholder).stopPrice(0).build();
        security.getOrderBook().enqueue(someOrder);
        security.getOrderBook().enqueue(queuedOrder);
        orderHandler.handleDeleteOrder(new DeleteOrderRq(1, security.getIsin(), Side.SELL, 200));
        verify(eventPublisher).publish(new OrderDeletedEvent(1, 200));
        assertThat(sellBroker.getCredit()).isEqualTo(1_000_000);
    }


    @Test
    void invalid_delete_with_order_id_not_found() {
        Broker buyBroker = Broker.builder().credit(1_000_000).build();
        brokerRepository.addBroker(buyBroker);
        Order queuedOrder = Order.builder().orderId(200).security(security).side(Side.BUY).quantity(1000).price(15500).
                minimumExecutionQuantity(0).broker(buyBroker).
                shareholder(shareholder).stopPrice(0).build();
        security.getOrderBook().enqueue(queuedOrder);
        orderHandler.handleDeleteOrder(new DeleteOrderRq(1, "ABC", Side.SELL, 100, 0, false));
        verify(eventPublisher).publish(new OrderRejectedEvent(1, 100, List.of(Message.ORDER_ID_NOT_FOUND)));
        assertThat(buyBroker.getCredit()).isEqualTo(1_000_000);
    }

    @Test
    void invalid_delete_order_with_non_existing_security() {
        Order queuedOrder = Order.builder().orderId(200).security(security).side(Side.BUY).quantity(1000).price(15500).
                minimumExecutionQuantity(0).broker(broker1).
                shareholder(shareholder).stopPrice(0).build();
        security.getOrderBook().enqueue(queuedOrder);
        orderHandler.handleDeleteOrder(new DeleteOrderRq(1, "XXX", Side.SELL, 200, 0, false));
        verify(eventPublisher).publish(new OrderRejectedEvent(1, 200, List.of(Message.UNKNOWN_SECURITY_ISIN)));
    }

    @Test
    void buyers_credit_decreases_on_new_order_without_trades() {
        Broker broker = Broker.builder().brokerId(10).credit(10_000).build();
        brokerRepository.addBroker(broker);
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 200, LocalDateTime.now(), Side.BUY, 30, 100, 10, shareholder.getShareholderId(), 0, 0, 0, false));
        assertThat(broker.getCredit()).isEqualTo(10_000-30*100);
    }

    @Test
    void buyers_credit_decreases_on_new_iceberg_order_without_trades() {
        Broker broker = Broker.builder().brokerId(10).credit(10_000).build();
        brokerRepository.addBroker(broker);

        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 200, LocalDateTime.now(), Side.BUY, 30, 100, 10, shareholder.getShareholderId(), 10, 0, 0, false));
        assertThat(broker.getCredit()).isEqualTo(10_000-30*100);
    }

    @Test
    void credit_does_not_change_on_invalid_new_order() {
        Broker broker = Broker.builder().brokerId(10).credit(10_000).build();
        brokerRepository.addBroker(broker);

        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", -1, LocalDateTime.now(), Side.BUY, 30, 100, broker.getBrokerId(), shareholder.getShareholderId(), 0, 0, 0, false));
        assertThat(broker.getCredit()).isEqualTo(10_000);
    }

    @Test
    void credit_updated_on_new_order_matched_partially_with_two_orders() {
        Broker broker1 = Broker.builder().brokerId(10).credit(100_000).build();
        Broker broker2 = Broker.builder().brokerId(20).credit(100_000).build();
        Broker broker3 = Broker.builder().brokerId(30).credit(100_000).build();
        List.of(broker1, broker2, broker3).forEach(b -> brokerRepository.addBroker(b));

        Order matchingSellOrder1 = Order.builder().orderId(100).security(security).side(Side.SELL).quantity(30).price(500).
                minimumExecutionQuantity(0).broker(broker1).
                shareholder(shareholder).stopPrice(0).build();
        Order matchingSellOrder2 = Order.builder().orderId(110).security(security).side(Side.SELL).quantity(20).price(500).
                minimumExecutionQuantity(0).broker(broker2).
                shareholder(shareholder).stopPrice(0).build();
        security.getOrderBook().enqueue(matchingSellOrder1);
        security.getOrderBook().enqueue(matchingSellOrder2);

        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 200, LocalDateTime.now(), Side.BUY, 100, 550, broker3.getBrokerId(), shareholder.getShareholderId(), 0, 0, 0, false));

        assertThat(broker1.getCredit()).isEqualTo(100_000 + 30*500);
        assertThat(broker2.getCredit()).isEqualTo(100_000 + 20*500);
        assertThat(broker3.getCredit()).isEqualTo(100_000 - 50*500 - 50*550);
    }

    @Test
    void new_order_from_buyer_with_not_enough_credit_no_trades() {
        Broker broker = Broker.builder().brokerId(10).credit(1000).build();
        brokerRepository.addBroker(broker);

        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 200, LocalDateTime.now(), Side.BUY, 30, 100, 10, shareholder.getShareholderId(), 0, 0, 0, false));
        assertThat(broker.getCredit()).isEqualTo(1000);
        verify(eventPublisher).publish(new OrderRejectedEvent(1, 200, List.of(Message.BUYER_HAS_NOT_ENOUGH_CREDIT)));
    }

    @Test
    void new_order_from_buyer_with_enough_credit_based_on_trades() {
        Broker broker1 = Broker.builder().brokerId(10).credit(100_000).build();
        Broker broker2 = Broker.builder().brokerId(20).credit(100_000).build();
        Broker broker3 = Broker.builder().brokerId(30).credit(52_500).build();
        List.of(broker1, broker2, broker3).forEach(b -> brokerRepository.addBroker(b));
        Order matchingSellOrder1 = Order.builder().orderId(100).security(security).side(Side.SELL).quantity(30).price(500).
                minimumExecutionQuantity(0).broker(broker1).
                shareholder(shareholder).stopPrice(0).build();
        Order matchingSellOrder2 = Order.builder().orderId(110).security(security).side(Side.SELL).quantity(20).price(500).
                minimumExecutionQuantity(0).broker(broker2).
                shareholder(shareholder).stopPrice(0).build();
        Order incomingBuyOrder = Order.builder().orderId(200).security(security).side(Side.BUY).quantity(100).price(550).
                minimumExecutionQuantity(0).broker(broker3).
                shareholder(shareholder).stopPrice(0).build();
        security.getOrderBook().enqueue(matchingSellOrder1);
        security.getOrderBook().enqueue(matchingSellOrder2);
        Trade trade1 = new Trade(security, matchingSellOrder1.getPrice(), matchingSellOrder1.getQuantity(),
                incomingBuyOrder, matchingSellOrder1);
        Trade trade2 = new Trade(security, matchingSellOrder2.getPrice(), matchingSellOrder2.getQuantity(),
                incomingBuyOrder.snapshotWithQuantity(700), matchingSellOrder2);

        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 200, LocalDateTime.now(), Side.BUY, 100, 550, broker3.getBrokerId(), shareholder.getShareholderId(), 0, 0, 0, false));

        assertThat(broker1.getCredit()).isEqualTo(100_000 + 30*500);
        assertThat(broker2.getCredit()).isEqualTo(100_000 + 20*500);
        assertThat(broker3.getCredit()).isEqualTo(0);

        verify(eventPublisher).publish(new OrderAcceptedEvent(1, 200));
        verify(eventPublisher).publish(new OrderExecutedEvent(1, 200, List.of(new TradeDTO(trade1), new TradeDTO(trade2))));
    }

    @Test
    void new_order_from_buyer_with_not_enough_credit_based_on_trades() {
        Broker broker1 = Broker.builder().brokerId(1).credit(100_000).build();
        Broker broker2 = Broker.builder().brokerId(2).credit(100_000).build();
        Broker broker3 = Broker.builder().brokerId(3).credit(50_000).build();
        List.of(broker1, broker2, broker3).forEach(b -> brokerRepository.addBroker(b));
        Order matchingSellOrder1 = Order.builder().orderId(100).security(security).side(Side.SELL).quantity(30).price(500).
                minimumExecutionQuantity(0).broker(broker1).
                shareholder(shareholder).stopPrice(0).build();
        Order matchingSellOrder2 = Order.builder().orderId(110).security(security).side(Side.SELL).quantity(20).price(500).
                minimumExecutionQuantity(0).broker(broker2).
                shareholder(shareholder).stopPrice(0).build();
        security.getOrderBook().enqueue(matchingSellOrder1);
        security.getOrderBook().enqueue(matchingSellOrder2);

        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 200, LocalDateTime.now(), Side.BUY, 100, 550, broker3.getBrokerId(), shareholder.getShareholderId(), 0, 0, 0, false));

        assertThat(broker1.getCredit()).isEqualTo(100_000);
        assertThat(broker2.getCredit()).isEqualTo(100_000);
        assertThat(broker3.getCredit()).isEqualTo(50_000);

        verify(eventPublisher).publish(new OrderRejectedEvent(1, 200, List.of(Message.BUYER_HAS_NOT_ENOUGH_CREDIT)));
    }

    @Test
    void update_buy_order_changing_price_with_no_trades_changes_buyers_credit() {
        Broker broker1 = Broker.builder().brokerId(1).credit(100_000).build();
        brokerRepository.addBroker(broker1);
        Order order = Order.builder().orderId(100).security(security).side(Side.BUY).quantity(30).price(500).
                minimumExecutionQuantity(0).broker(broker1).
                shareholder(shareholder).stopPrice(0).build();
        security.getOrderBook().enqueue(order);

        orderHandler.handleEnterOrder(EnterOrderRq.createUpdateOrderRq(1, "ABC", 100, LocalDateTime.now(), Side.BUY, 30, 550, broker1.getBrokerId(), shareholder.getShareholderId(), 0, 0, 0, false));

        assertThat(broker1.getCredit()).isEqualTo(100_000 - 1_500);
    }
    @Test
    void update_sell_order_changing_price_with_no_trades_does_not_changes_sellers_credit() {
        Broker broker1 = Broker.builder().brokerId(1).credit(100_000).build();
        brokerRepository.addBroker(broker1);
        Order order = Order.builder().orderId(100).security(security).side(Side.SELL).quantity(30).price(500).
                minimumExecutionQuantity(0).broker(broker1).
                shareholder(shareholder).stopPrice(0).build();
        security.getOrderBook().enqueue(order);

        orderHandler.handleEnterOrder(EnterOrderRq.createUpdateOrderRq(1, "ABC", 100, LocalDateTime.now(), Side.SELL, 30, 550, broker1.getBrokerId(), shareholder.getShareholderId(), 0, 0, 0, false));

        assertThat(broker1.getCredit()).isEqualTo(100_000);
    }

    @Test
    void update_order_changing_price_with_trades_changes_buyers_and_sellers_credit() {
        Broker broker1 = Broker.builder().brokerId(10).credit(100_000).build();
        Broker broker2 = Broker.builder().brokerId(20).credit(100_000).build();
        Broker broker3 = Broker.builder().brokerId(30).credit(100_000).build();
        List.of(broker1, broker2, broker3).forEach(b -> brokerRepository.addBroker(b));
        List<Order> orders = Arrays.asList(
                Order.builder().orderId(1).security(security).side(Side.BUY).quantity(304).price(570).
                        minimumExecutionQuantity(0).broker(broker3).
                        shareholder(shareholder).stopPrice(0).build(),
                Order.builder().orderId(2).security(security).side(Side.BUY).quantity(430).price(550).
                        minimumExecutionQuantity(0).broker(broker3).
                        shareholder(shareholder).stopPrice(0).build(),
                Order.builder().orderId(3).security(security).side(Side.BUY).quantity(445).price(545).
                        minimumExecutionQuantity(0).broker(broker3).
                        shareholder(shareholder).stopPrice(0).build(),
                Order.builder().orderId(6).security(security).side(Side.SELL).quantity(350).price(580).
                        minimumExecutionQuantity(0).broker(broker1).
                        shareholder(shareholder).stopPrice(0).build(),
                Order.builder().orderId(7).security(security).side(Side.SELL).quantity(100).price(581).
                        minimumExecutionQuantity(0).broker(broker2).
                        shareholder(shareholder).stopPrice(0).build()
        );
        orders.forEach(order -> security.getOrderBook().enqueue(order));

        orderHandler.handleEnterOrder(EnterOrderRq.createUpdateOrderRq(1, "ABC", 2, LocalDateTime.now(), Side.BUY, 500, 590, broker3.getBrokerId(), shareholder.getShareholderId(), 0, 0, 0, false));

        assertThat(broker1.getCredit()).isEqualTo(100_000 + 350*580);
        assertThat(broker2.getCredit()).isEqualTo(100_000 + 100*581);
        assertThat(broker3.getCredit()).isEqualTo(100_000 + 430*550 - 350*580 - 100*581 - 50*590);
    }

    @Test
    void update_order_changing_price_with_trades_for_buyer_with_insufficient_quantity_rolls_back() {
        Broker broker1 = Broker.builder().brokerId(10).credit(100_000).build();
        Broker broker2 = Broker.builder().brokerId(20).credit(100_000).build();
        Broker broker3 = Broker.builder().brokerId(30).credit(54_000).build();
        List.of(broker1, broker2, broker3).forEach(b -> brokerRepository.addBroker(b));
        List<Order> orders = Arrays.asList(
                Order.builder().orderId(1).security(security).side(Side.BUY).quantity(304).price(570).
                        minimumExecutionQuantity(0).broker(broker3).
                        shareholder(shareholder).stopPrice(0).build(),
                Order.builder().orderId(2).security(security).side(Side.BUY).quantity(430).price(550).
                        minimumExecutionQuantity(0).broker(broker3).
                        shareholder(shareholder).stopPrice(0).build(),
                Order.builder().orderId(3).security(security).side(Side.BUY).quantity(445).price(545).
                        minimumExecutionQuantity(0).broker(broker3).
                        shareholder(shareholder).stopPrice(0).build(),
                Order.builder().orderId(6).security(security).side(Side.SELL).quantity(350).price(580).
                        minimumExecutionQuantity(0).broker(broker1).
                        shareholder(shareholder).stopPrice(0).build(),
                Order.builder().orderId(7).security(security).side(Side.SELL).quantity(100).price(581).
                        minimumExecutionQuantity(0).broker(broker2).
                        shareholder(shareholder).stopPrice(0).build()
        );
        orders.forEach(order -> security.getOrderBook().enqueue(order));
        Order originalOrder = orders.get(1).snapshot();
        originalOrder.queue();

        orderHandler.handleEnterOrder(EnterOrderRq.createUpdateOrderRq(1, "ABC", 2, LocalDateTime.now(), Side.BUY, 500, 590, broker3.getBrokerId(), shareholder.getShareholderId(), 0, 0, 0, false));

        assertThat(broker1.getCredit()).isEqualTo(100_000);
        assertThat(broker2.getCredit()).isEqualTo(100_000);
        assertThat(broker3.getCredit()).isEqualTo(54_000);
        assertThat(originalOrder).isEqualTo(security.getOrderBook().findByOrderId(Side.BUY, 2, false));
    }

    @Test
    void update_order_without_trade_decreasing_quantity_changes_buyers_credit() {
        Broker broker1 = Broker.builder().brokerId(10).credit(100_000).build();
        Broker broker2 = Broker.builder().brokerId(20).credit(100_000).build();
        Broker broker3 = Broker.builder().brokerId(30).credit(100_000).build();
        List.of(broker1, broker2, broker3).forEach(b -> brokerRepository.addBroker(b));
        List<Order> orders = Arrays.asList(
                Order.builder().orderId(1).security(security).side(Side.BUY).quantity(304).price(570).
                        minimumExecutionQuantity(0).broker(broker3).
                        shareholder(shareholder).stopPrice(0).build(),
                Order.builder().orderId(2).security(security).side(Side.BUY).quantity(430).price(550).
                        minimumExecutionQuantity(0).broker(broker3).
                        shareholder(shareholder).stopPrice(0).build(),
                Order.builder().orderId(3).security(security).side(Side.BUY).quantity(445).price(545).
                        minimumExecutionQuantity(0).broker(broker3).
                        shareholder(shareholder).stopPrice(0).build(),
                Order.builder().orderId(6).security(security).side(Side.SELL).quantity(350).price(580).
                        minimumExecutionQuantity(0).broker(broker1).
                        shareholder(shareholder).stopPrice(0).build(),
                Order.builder().orderId(7).security(security).side(Side.SELL).quantity(100).price(581).
                        minimumExecutionQuantity(0).broker(broker2).
                        shareholder(shareholder).stopPrice(0).build()
        );
        orders.forEach(order -> security.getOrderBook().enqueue(order));

        orderHandler.handleEnterOrder(EnterOrderRq.createUpdateOrderRq(1, "ABC", 2, LocalDateTime.now(), Side.BUY, 400, 550, broker3.getBrokerId(), shareholder.getShareholderId(), 0, 0, 0, false));

        assertThat(broker1.getCredit()).isEqualTo(100_000);
        assertThat(broker2.getCredit()).isEqualTo(100_000);
        assertThat(broker3.getCredit()).isEqualTo(100_000 + 30*550);
    }

    @Test
    void new_sell_order_without_enough_positions_is_rejected() {
        List<Order> orders = Arrays.asList(
                Order.builder().orderId(1).security(security).side(Side.BUY).quantity(304).price(570).
                        minimumExecutionQuantity(0).broker(broker3).
                        shareholder(shareholder).stopPrice(0).build(),
                Order.builder().orderId(2).security(security).side(Side.BUY).quantity(430).price(550).
                        minimumExecutionQuantity(0).broker(broker3).
                        shareholder(shareholder).stopPrice(0).build(),
                Order.builder().orderId(3).security(security).side(Side.BUY).quantity(445).price(545).
                        minimumExecutionQuantity(0).broker(broker3).
                        shareholder(shareholder).stopPrice(0).build(),
                Order.builder().orderId(6).security(security).side(Side.SELL).quantity(350).price(580).
                        minimumExecutionQuantity(0).broker(broker1).
                        shareholder(shareholder).stopPrice(0).build(),
                Order.builder().orderId(7).security(security).side(Side.SELL).quantity(100).price(581).
                        minimumExecutionQuantity(0).broker(broker2).
                        shareholder(shareholder).stopPrice(0).build()
        );
        orders.forEach(order -> security.getOrderBook().enqueue(order));
        shareholder.decPosition(security, 99_500);
        broker3.increaseCreditBy(100_000_000);

        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 200, LocalDateTime.now(), Side.SELL, 400, 590, broker1.getBrokerId(), shareholder.getShareholderId(), 0, 0, 0, false));

        verify(eventPublisher).publish(new OrderRejectedEvent(1, 200, List.of(Message.SELLER_HAS_NOT_ENOUGH_POSITIONS)));
    }

    @Test
    void update_sell_order_without_enough_positions_is_rejected() {
        List<Order> orders = Arrays.asList(
                Order.builder().orderId(1).security(security).side(Side.BUY).quantity(304).price(570).
                        minimumExecutionQuantity(0).broker(broker3).
                        shareholder(shareholder).stopPrice(0).build(),
                Order.builder().orderId(2).security(security).side(Side.BUY).quantity(430).price(550).
                        minimumExecutionQuantity(0).broker(broker3).
                        shareholder(shareholder).stopPrice(0).build(),
                Order.builder().orderId(3).security(security).side(Side.BUY).quantity(445).price(545).
                        minimumExecutionQuantity(0).broker(broker3).
                        shareholder(shareholder).stopPrice(0).build(),
                Order.builder().orderId(6).security(security).side(Side.SELL).quantity(350).price(580).
                        minimumExecutionQuantity(0).broker(broker1).
                        shareholder(shareholder).stopPrice(0).build(),
                Order.builder().orderId(7).security(security).side(Side.SELL).quantity(100).price(581).
                        minimumExecutionQuantity(0).broker(broker2).
                        shareholder(shareholder).stopPrice(0).build()
        );
        orders.forEach(order -> security.getOrderBook().enqueue(order));
        shareholder.decPosition(security, 99_500);
        broker3.increaseCreditBy(100_000_000);

        orderHandler.handleEnterOrder(EnterOrderRq.createUpdateOrderRq(1, "ABC", 6, LocalDateTime.now(), Side.SELL, 450, 580, broker1.getBrokerId(), shareholder.getShareholderId(), 0, 0, 0, false));

        verify(eventPublisher).publish(new OrderRejectedEvent(1, 6, List.of(Message.SELLER_HAS_NOT_ENOUGH_POSITIONS)));
    }

    @Test
    void update_sell_order_with_enough_positions_is_executed() {
        Shareholder shareholder1 = Shareholder.builder().build();
        shareholder1.incPosition(security, 100_000);
        shareholderRepository.addShareholder(shareholder1);
        List<Order> orders = Arrays.asList(
                Order.builder().orderId(1).security(security).side(Side.BUY).quantity(304).price(570).
                        minimumExecutionQuantity(0).broker(broker3).
                        shareholder(shareholder1).stopPrice(0).build(),
                Order.builder().orderId(2).security(security).side(Side.BUY).quantity(430).price(550).
                        minimumExecutionQuantity(0).broker(broker3).
                        shareholder(shareholder1).stopPrice(0).build(),
                Order.builder().orderId(3).security(security).side(Side.BUY).quantity(445).price(545).
                        minimumExecutionQuantity(0).broker(broker3).
                        shareholder(shareholder1).stopPrice(0).build(),
                Order.builder().orderId(6).security(security).side(Side.SELL).quantity(350).price(580).
                        minimumExecutionQuantity(0).broker(broker1).
                        shareholder(shareholder).stopPrice(0).build(),
                Order.builder().orderId(7).security(security).side(Side.SELL).quantity(100).price(581).
                        minimumExecutionQuantity(0).broker(broker2).
                        shareholder(shareholder).stopPrice(0).build()
        );
        orders.forEach(order -> security.getOrderBook().enqueue(order));
        shareholder.decPosition(security, 99_500);
        broker3.increaseCreditBy(100_000_000);

        orderHandler.handleEnterOrder(EnterOrderRq.createUpdateOrderRq(1, "ABC", 6, LocalDateTime.now(), Side.SELL, 250, 570, broker1.getBrokerId(), shareholder.getShareholderId(), 0 ,0, 0, false));

        verify(eventPublisher).publish(any(OrderExecutedEvent.class));
        assertThat(shareholder1.hasEnoughPositionsOn(security, 100_000 + 250)).isTrue();
        assertThat(shareholder.hasEnoughPositionsOn(security, 99_500 - 251)).isFalse();
    }

    @Test
    void new_buy_order_does_not_check_for_position() {
        Shareholder shareholder1 = Shareholder.builder().build();
        shareholder1.incPosition(security, 100_000);
        shareholderRepository.addShareholder(shareholder1);
        List<Order> orders = Arrays.asList(
                Order.builder().orderId(1).security(security).side(Side.BUY).quantity(304).price(570).
                        minimumExecutionQuantity(0).broker(broker3).
                        shareholder(shareholder).stopPrice(0).build(),
                Order.builder().orderId(2).security(security).side(Side.BUY).quantity(430).price(550).
                        minimumExecutionQuantity(0).broker(broker3).
                        shareholder(shareholder).stopPrice(0).build(),
                Order.builder().orderId(3).security(security).side(Side.BUY).quantity(445).price(545).
                        minimumExecutionQuantity(0).broker(broker3).
                        shareholder(shareholder).stopPrice(0).build(),
                Order.builder().orderId(6).security(security).side(Side.SELL).quantity(350).price(580).
                        minimumExecutionQuantity(0).broker(broker1).
                        shareholder(shareholder).stopPrice(0).build(),
                Order.builder().orderId(7).security(security).side(Side.SELL).quantity(100).price(581).
                        minimumExecutionQuantity(0).broker(broker2).
                        shareholder(shareholder).stopPrice(0).build()
        );
        orders.forEach(order -> security.getOrderBook().enqueue(order));
        shareholder.decPosition(security, 99_500);
        broker3.increaseCreditBy(100_000_000);

        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 200, LocalDateTime.now(), Side.BUY, 500, 570, broker3.getBrokerId(), shareholder.getShareholderId(), 0, 0, 0, false));

        verify(eventPublisher).publish(any(OrderAcceptedEvent.class));
        assertThat(shareholder1.hasEnoughPositionsOn(security, 100_000)).isTrue();
        assertThat(shareholder.hasEnoughPositionsOn(security, 500)).isTrue();
    }

    @Test
    void update_buy_order_does_not_check_for_position() {
        Shareholder shareholder1 = Shareholder.builder().build();
        shareholder1.incPosition(security, 100_000);
        shareholderRepository.addShareholder(shareholder1);
        List<Order> orders = Arrays.asList(
                Order.builder().orderId(1).security(security).side(Side.BUY).quantity(304).price(570).
                        minimumExecutionQuantity(0).broker(broker3).
                        shareholder(shareholder).stopPrice(0).build(),
                Order.builder().orderId(2).security(security).side(Side.BUY).quantity(430).price(550).
                        minimumExecutionQuantity(0).broker(broker3).
                        shareholder(shareholder).stopPrice(0).build(),
                Order.builder().orderId(3).security(security).side(Side.BUY).quantity(445).price(545).
                        minimumExecutionQuantity(0).broker(broker3).
                        shareholder(shareholder).stopPrice(0).build(),
                Order.builder().orderId(6).security(security).side(Side.SELL).quantity(350).price(580).
                        minimumExecutionQuantity(0).broker(broker1).
                        shareholder(shareholder).stopPrice(0).build(),
                Order.builder().orderId(7).security(security).side(Side.SELL).quantity(100).price(581).
                        minimumExecutionQuantity(0).broker(broker2).
                        shareholder(shareholder).stopPrice(0).build()
        );
        orders.forEach(order -> security.getOrderBook().enqueue(order));
        shareholder.decPosition(security, 99_500);
        broker3.increaseCreditBy(100_000_000);

        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 3, LocalDateTime.now(), Side.BUY, 500, 545, broker3.getBrokerId(), shareholder1.getShareholderId(), 0, 0, 0, false));

        verify(eventPublisher).publish(any(OrderAcceptedEvent.class));
        assertThat(shareholder1.hasEnoughPositionsOn(security, 100_000)).isTrue();
        assertThat(shareholder.hasEnoughPositionsOn(security, 500)).isTrue();
    }

}