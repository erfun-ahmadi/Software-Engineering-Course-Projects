package ir.ramtung.tinyme.domain;

import ir.ramtung.tinyme.config.MockedJMSTestConfig;
import ir.ramtung.tinyme.domain.entity.*;
import ir.ramtung.tinyme.domain.service.OrderHandler;
import ir.ramtung.tinyme.domain.service.AuctionMatcher;
import ir.ramtung.tinyme.messaging.EventPublisher;
import ir.ramtung.tinyme.messaging.Message;
import ir.ramtung.tinyme.messaging.event.*;
import ir.ramtung.tinyme.messaging.request.ChangeMatchingStateRq;
import ir.ramtung.tinyme.messaging.request.EnterOrderRq;
import ir.ramtung.tinyme.messaging.request.DeleteOrderRq;
import ir.ramtung.tinyme.messaging.request.MatchingState;
import ir.ramtung.tinyme.repository.BrokerRepository;
import ir.ramtung.tinyme.repository.SecurityRepository;
import ir.ramtung.tinyme.repository.ShareholderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.LinkedList;

import static ir.ramtung.tinyme.domain.entity.Side.BUY;
import static ir.ramtung.tinyme.domain.entity.Side.SELL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

@SpringBootTest
@Import(MockedJMSTestConfig.class)
@DirtiesContext
class AuctionStateTest {
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
    private OrderBook orderBook;
    private AuctionMatcher auctionMatcher;

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

        broker1 = Broker.builder().brokerId(1).credit(100_000).build();
        broker2 = Broker.builder().brokerId(2).credit(100_000).build();
        brokerRepository.addBroker(broker1);
        brokerRepository.addBroker(broker2);

        orderBook = security.getOrderBook();
        auctionMatcher = new AuctionMatcher();

        List<Order> orders = Arrays.asList(
                Order.builder().orderId(1).security(security).side(SELL).quantity(304).price(15700).
                        minimumExecutionQuantity(0).broker(broker1).
                        shareholder(shareholder).stopPrice(0).build(),
                Order.builder().orderId(2).security(security).side(SELL).quantity(43).price(15500).
                        minimumExecutionQuantity(0).broker(broker1).
                        shareholder(shareholder).stopPrice(0).build(),
                Order.builder().orderId(3).security(security).side(SELL).quantity(445).price(15450).
                        minimumExecutionQuantity(0).broker(broker2).
                        shareholder(shareholder).stopPrice(0).build(),
                Order.builder().orderId(4).security(security).side(SELL).quantity(526).price(15450).
                        minimumExecutionQuantity(0).broker(broker2).
                        shareholder(shareholder).stopPrice(0).build(),
                Order.builder().orderId(5).security(security).side(SELL).quantity(1000).price(15400).
                        minimumExecutionQuantity(0).broker(broker2).
                        shareholder(shareholder).stopPrice(0).build(),
                Order.builder().orderId(6).security(security).side(BUY).quantity(350).price(15800).
                        minimumExecutionQuantity(0).broker(broker2).
                        shareholder(shareholder).stopPrice(0).build(),
                Order.builder().orderId(7).security(security).side(BUY).quantity(285).price(15810).
                        minimumExecutionQuantity(0).broker(broker2).
                        shareholder(shareholder).stopPrice(0).build(),
                Order.builder().orderId(8).security(security).side(BUY).quantity(800).price(15810).
                        minimumExecutionQuantity(0).broker(broker1).
                        shareholder(shareholder).stopPrice(0).build(),
                Order.builder().orderId(9).security(security).side(BUY).quantity(340).price(15820).
                        minimumExecutionQuantity(0).broker(broker1).
                        shareholder(shareholder).stopPrice(0).build(),
                Order.builder().orderId(10).security(security).side(BUY).quantity(65).price(15820).
                        minimumExecutionQuantity(0).broker(broker1).
                        shareholder(shareholder).stopPrice(0).build()
        );
        orders.forEach(order -> orderBook.enqueue(order));
    }


    @Test
    void change_state_from_continuous_to_auction_successful() {
        orderHandler.handleChangeMatchingState(new ChangeMatchingStateRq("ABC", MatchingState.AUCTION));
        ArgumentCaptor<SecurityStateChangedEvent> securityStateChangedEventCaptor = ArgumentCaptor.forClass(SecurityStateChangedEvent.class);
        verify(eventPublisher).publish(securityStateChangedEventCaptor.capture());
        SecurityStateChangedEvent outputEvent = securityStateChangedEventCaptor.getValue();
        assertThat(outputEvent.getSecurityIsin()).isEqualTo("ABC");
        assertThat(outputEvent.getState()).isEqualTo(MatchingState.AUCTION);
    }

    @Test
    void change_state_from_auction_to_continuous_successful() {
        security.setMatchingState(MatchingState.AUCTION);
        orderHandler.handleChangeMatchingState(new ChangeMatchingStateRq("ABC", MatchingState.CONTINUOUS));
        ArgumentCaptor<SecurityStateChangedEvent> securityStateChangedEventCaptor = ArgumentCaptor.forClass(SecurityStateChangedEvent.class);
        verify(eventPublisher).publish(securityStateChangedEventCaptor.capture());
        SecurityStateChangedEvent outputEvent = securityStateChangedEventCaptor.getValue();
        assertThat(outputEvent.getSecurityIsin()).isEqualTo("ABC");
        assertThat(outputEvent.getState()).isEqualTo(MatchingState.CONTINUOUS);
    }

    @Test
    void change_state_from_auction_to_auction_successful() {
        security.setMatchingState(MatchingState.AUCTION);
        orderHandler.handleChangeMatchingState(new ChangeMatchingStateRq("ABC", MatchingState.AUCTION));
        ArgumentCaptor<SecurityStateChangedEvent> securityStateChangedEventCaptor = ArgumentCaptor.forClass(SecurityStateChangedEvent.class);
        verify(eventPublisher).publish(securityStateChangedEventCaptor.capture());
        SecurityStateChangedEvent outputEvent = securityStateChangedEventCaptor.getValue();
        assertThat(outputEvent.getSecurityIsin()).isEqualTo("ABC");
        assertThat(outputEvent.getState()).isEqualTo(MatchingState.AUCTION);
    }

    @Test
    void adding_minimum_execution_quantity_for_buy_order_in_auction_state_rejected() {
        orderHandler.handleChangeMatchingState(new ChangeMatchingStateRq("ABC", MatchingState.AUCTION));
        EnterOrderRq enterOrderRq = EnterOrderRq.createNewOrderRq(1, "ABC", 100,
                LocalDateTime.now(), Side.BUY, 40, 500, broker1.getBrokerId(), shareholder.getShareholderId(),
                0, 10, 0, true);
        orderHandler.handleEnterOrder(enterOrderRq);
        verify(eventPublisher).publish((new OrderRejectedEvent(1, 100, List.of(Message.INVALID_ORDER_IN_AUCTION_STATE))));
        assertThat(security.getOrderBook().getBuyQueue().size()).isEqualTo(5);
        assertThat(broker1.getCredit()).isEqualTo(100_000);
    }

    @Test
    void adding_minimum_execution_quantity_for_sell_order_in_auction_state_rejected() {
        orderHandler.handleChangeMatchingState(new ChangeMatchingStateRq("ABC", MatchingState.AUCTION));
        EnterOrderRq enterOrderRq = EnterOrderRq.createNewOrderRq(1, "ABC", 100,
                LocalDateTime.now(), Side.SELL, 40, 500, broker1.getBrokerId(), shareholder.getShareholderId(),
                0, 10, 0, true);
        orderHandler.handleEnterOrder(enterOrderRq);
        verify(eventPublisher).publish((new OrderRejectedEvent(1, 100, List.of(Message.INVALID_ORDER_IN_AUCTION_STATE))));
        assertThat(security.getOrderBook().getSellQueue().size()).isEqualTo(5);
    }

    @Test
    void adding_stop_limit_for_buy_order_in_auction_state_rejected() {
        orderHandler.handleChangeMatchingState(new ChangeMatchingStateRq("ABC", MatchingState.AUCTION));
        EnterOrderRq enterOrderRq = EnterOrderRq.createNewOrderRq(1, "ABC", 100,
                LocalDateTime.now(), Side.BUY, 40, 500, broker1.getBrokerId(), shareholder.getShareholderId(),
                0, 0, 100, true);
        orderHandler.handleEnterOrder(enterOrderRq);
        verify(eventPublisher).publish((new OrderRejectedEvent(1, 100, List.of(Message.INVALID_ORDER_IN_AUCTION_STATE))));
        assertThat(security.getOrderBook().getBuyQueue().size()).isEqualTo(5);
        assertThat(security.getOrderBook().getInactiveBuyQueue().size()).isEqualTo(0);
        assertThat(broker1.getCredit()).isEqualTo(100_000);
    }

    @Test
    void adding_stop_limit_for_sell_order_in_auction_state_rejected() {
        orderHandler.handleChangeMatchingState(new ChangeMatchingStateRq("ABC", MatchingState.AUCTION));
        EnterOrderRq enterOrderRq = EnterOrderRq.createNewOrderRq(1, "ABC", 100,
                LocalDateTime.now(), Side.SELL, 40, 500, broker1.getBrokerId(), shareholder.getShareholderId(),
                0, 0, 100, true);
        orderHandler.handleEnterOrder(enterOrderRq);
        verify(eventPublisher).publish((new OrderRejectedEvent(1, 100, List.of(Message.INVALID_ORDER_IN_AUCTION_STATE))));
        assertThat(security.getOrderBook().getSellQueue().size()).isEqualTo(5);
        assertThat(security.getOrderBook().getInactiveSellQueue().size()).isEqualTo(0);
    }

    @Test
    void update_price_for_sell_order_in_auction_state_successful() {
        security.setMatchingState(MatchingState.AUCTION);
        orderHandler.handleEnterOrder(EnterOrderRq.createUpdateOrderRq(5, "ABC", 5,
                LocalDateTime.now(), Side.SELL, 1000, 16000, broker2.getBrokerId(), shareholder.getShareholderId(),
                0, 0, 0, false));
        assertThat(broker2.getCredit()).isEqualTo(100_000);
        assertThat(orderBook.findByOrderId(Side.SELL, 5, false).getPrice()).isEqualTo(16000);
        int openPrice = auctionMatcher.findOpeningPrice(security);
        assertThat(openPrice).isEqualTo(15800);
    }

    @Test
    void update_price_for_buy_order_in_auction_state_successful() {
        security.setMatchingState(MatchingState.AUCTION);
        orderHandler.handleEnterOrder(EnterOrderRq.createUpdateOrderRq(5, "ABC", 10,
                LocalDateTime.now(), Side.BUY, 65, 15000, broker1.getBrokerId(), shareholder.getShareholderId(),
                0, 0, 0, false));
        assertThat(broker1.getCredit()).isEqualTo(153_300);
        assertThat(orderBook.findByOrderId(Side.BUY, 10, false).getPrice()).isEqualTo(15000);
        int openPrice = auctionMatcher.findOpeningPrice(security);
        assertThat(openPrice).isEqualTo(15450);
    }

    @Test
    void calculating_tradable_quantity_if_total_of_buy_queue_is_lower_successful() {
        Order newOrder = Order.builder().orderId(11).security(security).side(BUY).quantity(300).price(15420).
                minimumExecutionQuantity(0).broker(broker2).
                shareholder(shareholder).stopPrice(0).build();
        security.setLastTradePrice(10000);
        auctionMatcher.updateOpeningPriceWithNewOrder(newOrder);
        assertThat(auctionMatcher.getTradableQuantity()).isEqualTo(1840);
    }

    @Test
    void calculating_tradable_quantity_if_total_of_sell_queue_is_lower_successful() {
        Order newOrder = Order.builder().orderId(11).security(security).side(BUY).quantity(1000).price(15870).
                minimumExecutionQuantity(0).broker(broker2).
                shareholder(shareholder).stopPrice(0).build();
        security.setLastTradePrice(10000);
        auctionMatcher.updateOpeningPriceWithNewOrder(newOrder);
        assertThat(auctionMatcher.getTradableQuantity()).isEqualTo(2318);
    }

    @Test
    void calculating_tradable_quantity_if_max_sell_queue_price_is_more_than_min_buy_queue_price_successful() {
        Order newOrder = Order.builder().orderId(11).security(security).side(BUY).quantity(1200).price(15470).
                minimumExecutionQuantity(0).broker(broker2).
                shareholder(shareholder).stopPrice(0).build();
        security.setLastTradePrice(10000);
        auctionMatcher.updateOpeningPriceWithNewOrder(newOrder);
        assertThat(auctionMatcher.getTradableQuantity()).isEqualTo(1971);
    }

    @Test
    void calculating_tradable_quantity_if_max_sell_queue_price_is_less_than_min_buy_queue_price_successful() {
        Order newOrder = Order.builder().orderId(11).security(security).side(BUY).quantity(1200).price(15470).
                minimumExecutionQuantity(0).broker(broker2).
                shareholder(shareholder).stopPrice(0).build();
        security.setLastTradePrice(10000);
        auctionMatcher.updateOpeningPriceWithNewOrder(newOrder);
        assertThat(auctionMatcher.getTradableQuantity()).isEqualTo(1971);
    }

    @Test
    void calculating_tradable_quantity_with_iceberg_order_successful() {
        Order newOrder = IcebergOrder.builder().orderId(11).security(security).side(BUY).quantity(200).price(15800).
                minimumExecutionQuantity(0).broker(broker2).
                shareholder(shareholder).entryTime(LocalDateTime.now()).peakSize(0).displayedQuantity(100).status(OrderStatus.QUEUED).stopPrice(0).build();
        security.setLastTradePrice(10000);
        auctionMatcher.updateOpeningPriceWithNewOrder(newOrder);
        assertThat(auctionMatcher.getTradableQuantity()).isEqualTo(2040);
    }

    @Test
    void calculating_tradable_quantity_with_no_order_request_successful() {
        Order newOrder = Order.builder().orderId(11).security(security).side(BUY).quantity(300).price(15800).
                minimumExecutionQuantity(0).broker(broker2).
                shareholder(shareholder).stopPrice(0).build();
        security.setLastTradePrice(10000);
        auctionMatcher.updateOpeningPriceWithNewOrder(newOrder);
        assertThat(auctionMatcher.getTradableQuantity()).isEqualTo(2140);
        auctionMatcher.findOpeningPrice(security);
        assertThat(auctionMatcher.getTradableQuantity()).isEqualTo(2140);
    }

    @Test
    void calculating_open_price_with_one_candidate_closest_and_lower_to_last_trade_price_successful() {
        Order newOrder = Order.builder().orderId(11).security(security).side(BUY).quantity(300).price(15800).
                minimumExecutionQuantity(0).broker(broker2).
                shareholder(shareholder).stopPrice(0).build();
        security.setLastTradePrice(15820);
        auctionMatcher.updateOpeningPriceWithNewOrder(newOrder);
        assertThat(auctionMatcher.getOpeningPrice()).isEqualTo(15800);
    }

    @Test
    void calculating_open_price_with_two_candidate_closest_to_last_trade_price_successful() {
        security.setLastTradePrice(10000);
        LinkedList<Integer> prices = new LinkedList<>(Arrays.asList(1005, 9995, 9990, 10010));
        int bestOpenPrice = auctionMatcher.findClosestToLastTradePrice(prices, security);
        assertThat(bestOpenPrice).isEqualTo(9995);
    }

    @Test
    void adding_new_order_that_does_not_change_open_price() {
        Order newOrder = Order.builder().orderId(11).security(security).side(BUY).quantity(300).price(15800).
                minimumExecutionQuantity(0).broker(broker2).
                shareholder(shareholder).stopPrice(0).build();
        security.setLastTradePrice(15820);
        auctionMatcher.updateOpeningPriceWithNewOrder(newOrder);
        assertThat(auctionMatcher.getOpeningPrice()).isEqualTo(15800);
        Order newOrder2 = Order.builder().orderId(12).security(security).side(BUY).quantity(3000).price(15900).
                minimumExecutionQuantity(0).broker(broker2).
                shareholder(shareholder).stopPrice(0).build();
        auctionMatcher.updateOpeningPriceWithNewOrder(newOrder2);
        assertThat(auctionMatcher.getOpeningPrice()).isEqualTo(15800);
    }

    @Test
    void adding_new_order_that_does_not_change_tradable_quantity() {
        Order newOrder = Order.builder().orderId(11).security(security).side(BUY).quantity(300).price(15420).
                minimumExecutionQuantity(0).broker(broker2).
                shareholder(shareholder).stopPrice(0).build();
        security.setLastTradePrice(10000);
        auctionMatcher.updateOpeningPriceWithNewOrder(newOrder);
        assertThat(auctionMatcher.getTradableQuantity()).isEqualTo(1840);
        Order newOrder2 = Order.builder().orderId(12).security(security).side(BUY).quantity(300).price(15400).
                minimumExecutionQuantity(0).broker(broker2).
                shareholder(shareholder).stopPrice(0).build();
        auctionMatcher.updateOpeningPriceWithNewOrder(newOrder2);
        assertThat(auctionMatcher.getTradableQuantity()).isEqualTo(1840);
    }

    @Test
    void adding_new_order_that_changes_tradable_quantity_but_does_not_change_open_price() {
        Order newOrder = Order.builder().orderId(11).security(security).side(BUY).quantity(300).price(15800).
                minimumExecutionQuantity(0).broker(broker2).
                shareholder(shareholder).stopPrice(0).build();
        security.setLastTradePrice(15820);
        auctionMatcher.updateOpeningPriceWithNewOrder(newOrder);
        assertThat(auctionMatcher.getOpeningPrice()).isEqualTo(15800);
        assertThat(auctionMatcher.getTradableQuantity()).isEqualTo(2140);
        Order newOrder2 = Order.builder().orderId(12).security(security).side(BUY).quantity(360).price(15900).
                minimumExecutionQuantity(0).broker(broker2).
                shareholder(shareholder).stopPrice(0).build();
        auctionMatcher.updateOpeningPriceWithNewOrder(newOrder2);
        assertThat(auctionMatcher.getOpeningPrice()).isEqualTo(15800);
        assertThat(auctionMatcher.getTradableQuantity()).isEqualTo(2318);
    }

    @Test
    void adding_new_order_that_changes_open_price_but_does_not_change_tradable_quantity() {
        Order newOrder = Order.builder().orderId(11).security(security).side(BUY).quantity(300).price(15420).
                minimumExecutionQuantity(0).broker(broker2).
                shareholder(shareholder).stopPrice(0).build();
        security.setLastTradePrice(10000);
        auctionMatcher.updateOpeningPriceWithNewOrder(newOrder);
        assertThat(auctionMatcher.getTradableQuantity()).isEqualTo(1840);
        assertThat(auctionMatcher.getOpeningPrice()).isEqualTo(15450);
        Order newOrder2 = Order.builder().orderId(12).security(security).side(SELL).quantity(1000).price(15440).
                minimumExecutionQuantity(0).broker(broker2).
                shareholder(shareholder).stopPrice(0).build();
        auctionMatcher.updateOpeningPriceWithNewOrder(newOrder2);
        assertThat(auctionMatcher.getTradableQuantity()).isEqualTo(1840);
        assertThat(auctionMatcher.getOpeningPrice()).isEqualTo(15440);
    }

    @Test
    void adding_stop_limit_order_for_buy_order_in_auction_state_rejected() {
        orderHandler.handleChangeMatchingState(new ChangeMatchingStateRq("ABC", MatchingState.AUCTION));
        EnterOrderRq enterOrderRq = EnterOrderRq.createNewOrderRq(1, "ABC", 100,
                LocalDateTime.now(), Side.BUY, 40, 500, broker1.getBrokerId(), shareholder.getShareholderId(),
                0, 0, 100, true);
        orderHandler.handleEnterOrder(enterOrderRq);
        verify(eventPublisher).publish((new OrderRejectedEvent(1, 100, List.of(Message.INVALID_ORDER_IN_AUCTION_STATE))));
        assertThat(security.getOrderBook().getBuyQueue().size()).isEqualTo(5);
        assertThat(security.getOrderBook().getInactiveBuyQueue().size()).isEqualTo(0);
        assertThat(broker1.getCredit()).isEqualTo(100_000);
    }

    @Test
    void adding_stop_limit_order_for_sell_order_in_auction_state_rejected() {
        orderHandler.handleChangeMatchingState(new ChangeMatchingStateRq("ABC", MatchingState.AUCTION));
        EnterOrderRq enterOrderRq = EnterOrderRq.createNewOrderRq(1, "ABC", 100,
                LocalDateTime.now(), Side.SELL, 40, 500, broker1.getBrokerId(), shareholder.getShareholderId(),
                0, 0, 100, true);
        orderHandler.handleEnterOrder(enterOrderRq);
        verify(eventPublisher).publish((new OrderRejectedEvent(1, 100, List.of(Message.INVALID_ORDER_IN_AUCTION_STATE))));
        assertThat(security.getOrderBook().getSellQueue().size()).isEqualTo(5);
        assertThat(security.getOrderBook().getInactiveSellQueue().size()).isEqualTo(0);
    }

    @Test
    void update_quantity_for_sell_order_in_auction_state_successful() {
        security.setMatchingState(MatchingState.AUCTION);
        orderHandler.handleEnterOrder(EnterOrderRq.createUpdateOrderRq(5, "ABC", 5,
                LocalDateTime.now(), Side.SELL, 100, 15400, broker2.getBrokerId(), shareholder.getShareholderId(),
                0, 0, 0, false));
        assertThat(broker2.getCredit()).isEqualTo(100_000);
        assertThat(orderBook.findByOrderId(Side.SELL, 5, false).getQuantity()).isEqualTo(100);
        int openPrice = auctionMatcher.findOpeningPrice(security);
        assertThat(openPrice).isEqualTo(15700);
    }

    @Test
    void update_quantity_for_buy_order_in_auction_state_successful() {
        security.setMatchingState(MatchingState.AUCTION);
        orderHandler.handleEnterOrder(EnterOrderRq.createUpdateOrderRq(5, "ABC", 10,
                LocalDateTime.now(), Side.BUY, 55, 15820, broker1.getBrokerId(), shareholder.getShareholderId(),
                0, 0, 0, false));
        assertThat(broker1.getCredit()).isEqualTo(258200);
        assertThat(orderBook.findByOrderId(Side.BUY, 10, false).getQuantity()).isEqualTo(55);
        int openPrice = auctionMatcher.findOpeningPrice(security);
        assertThat(openPrice).isEqualTo(15700);
    }

    @Test
    void new_stop_limit_order_changes_opening_price_and_tradable_quantity() {
        int firstOpeningPrice = auctionMatcher.findOpeningPrice(security);
        assertThat(firstOpeningPrice).isEqualTo(15700);
        assertThat(auctionMatcher.getTradableQuantity()).isEqualTo(1840);
        Order newOrder = Order.builder().orderId(100).security(security).side(BUY).quantity(100).price(15840).
                minimumExecutionQuantity(0).broker(broker1).shareholder(shareholder).
                entryTime(LocalDateTime.now()).status(OrderStatus.NEW).stopPrice(15890).inactive(false).build();
        security.setLastTradePrice(15900);
        auctionMatcher.updateOpeningPriceWithNewOrder(newOrder);
        assertThat(auctionMatcher.getOpeningPrice()).isEqualTo(15800);
        assertThat(auctionMatcher.getTradableQuantity()).isEqualTo(1940);
    }

    @Test
    void publish_opening_price_event_with_new_order() {
        security.setMatchingState(MatchingState.AUCTION);
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 11, LocalDateTime.now(), Side.BUY, 5, 15000, broker2.getBrokerId(), shareholder.getShareholderId(), 0, 0));
        ArgumentCaptor<OpeningPriceEvent> openingPriceEventCaptor = ArgumentCaptor.forClass(OpeningPriceEvent.class);
        verify(eventPublisher).publish(openingPriceEventCaptor.capture());
        OpeningPriceEvent outputEvent = openingPriceEventCaptor.getValue();
        assertThat(outputEvent.getSecurityIsin()).isEqualTo("ABC");
        assertThat(outputEvent.getOpeningPrice()).isEqualTo(15450);
        assertThat(outputEvent.getTradableQuantity()).isEqualTo(1840);
    }

    @Test
    void publish_trades_successfully(){
        Security security1 = Security.builder().build();
        securityRepository.addSecurity(security1);
        List<Order> orders = Arrays.asList(
                Order.builder().orderId(1).security(security1).side(SELL).quantity(304).price(15700).
                        minimumExecutionQuantity(0).broker(broker1).
                        shareholder(shareholder).stopPrice(0).build(),
                Order.builder().orderId(6).security(security1).side(BUY).quantity(350).price(15800).
                        minimumExecutionQuantity(0).broker(broker2).
                        shareholder(shareholder).stopPrice(0).build()
        );
        orders.forEach(order -> security1.getOrderBook().enqueue(order));
        security1.setMatchingState(MatchingState.AUCTION);
        orderHandler.handleChangeMatchingState(new ChangeMatchingStateRq(security1.getIsin(), MatchingState.AUCTION));
        ArgumentCaptor<TradeEvent> tradeEventCaptor = ArgumentCaptor.forClass(TradeEvent.class);
        verify(eventPublisher).publish(tradeEventCaptor.capture());
        TradeEvent outputEvent = tradeEventCaptor.getValue();
        assertThat(outputEvent.getSecurityIsin()).isEqualTo(security1.getIsin());
        assertThat(outputEvent.getPrice()).isEqualTo(15700);
        assertThat(outputEvent.getQuantity()).isEqualTo(304);
        assertThat(outputEvent.getBuyId()).isEqualTo(6);
        assertThat(outputEvent.getSellId()).isEqualTo(1);
    }

    @Test
    void delete_buy_order_deletes_successfully_and_increases_credit_in_auction_state() {
        security.setMatchingState(MatchingState.AUCTION);
        orderHandler.handleDeleteOrder(new DeleteOrderRq(1, security.getIsin(), Side.BUY, 6));
        verify(eventPublisher).publish(new OrderDeletedEvent(1, 6));
        assertThat(broker2.getCredit()).isEqualTo(5630000);
        int openPrice = auctionMatcher.findOpeningPrice(security);
        assertThat(openPrice).isEqualTo(15700);
    }

    @Test
    void delete_sell_order_deletes_successfully_and_increases_credit_in_auction_state() {
        security.setMatchingState(MatchingState.AUCTION);
        orderHandler.handleDeleteOrder(new DeleteOrderRq(1, security.getIsin(), Side.SELL, 1));
        verify(eventPublisher).publish(new OrderDeletedEvent(1, 1));
        assertThat(broker1.getCredit()).isEqualTo(100_000);
        int openPrice = auctionMatcher.findOpeningPrice(security);
        assertThat(openPrice).isEqualTo(15500);
    }
}