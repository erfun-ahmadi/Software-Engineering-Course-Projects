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

import static org.assertj.core.api.Assertions.*;
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
    private List<Order> orders;

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

        orderBook = security.getOrderBook();
        orders = Arrays.asList(
                new Order(1, security, Side.SELL, 304, 15700, 0, broker1, shareholder, 0),
                new Order(2, security, Side.SELL, 43, 15500, 0, broker1, shareholder, 0),
                new Order(3, security, Side.SELL, 445, 15450, 0, broker2, shareholder, 0),
                new Order(4, security, Side.SELL, 526, 15450, 0, broker2, shareholder, 0),
                new Order(5, security, Side.SELL, 1000, 15400, 0, broker2, shareholder, 0),
                new Order(6, security, Side.BUY, 350, 15800, 0, broker2, shareholder, 0),
                new Order(7, security, Side.BUY, 285, 15810, 0, broker2, shareholder, 0),
                new Order(8, security, Side.BUY, 800, 15810, 0, broker1, shareholder, 0),
                new Order(9, security, Side.BUY, 340, 15820, 0, broker1, shareholder, 0),
                new Order(10, security, Side.BUY, 65, 15820, 0, broker1, shareholder, 0)
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
        assertThat(orderBook.getSellQueue().get(4).getPrice()).isEqualTo(16000);
    }

    @Test
    void update_price_for_buy_order_in_auction_state_successful() {
        security.setMatchingState(MatchingState.AUCTION);
        orderHandler.handleEnterOrder(EnterOrderRq.createUpdateOrderRq(5, "ABC", 10,
                LocalDateTime.now(), Side.BUY, 65, 15000, broker1.getBrokerId(), shareholder.getShareholderId(),
                0, 0, 0, false));
        assertThat(broker1.getCredit()).isEqualTo(153_300);
        assertThat(orderBook.getBuyQueue().get(4).getPrice()).isEqualTo(15000);
    }


    @Test
    void findTradableQuantityIfTotalOfBuyQueueIsLower() {
        AuctionMatcher auctionMatcher = new AuctionMatcher();
        Order newOrder = new Order(11, security, Side.BUY, 300, 15420, 0, broker2, shareholder, 0);
        security.setLastTradePrice(10000);
        auctionMatcher.updateOpeningPriceWithNewOrder(newOrder);
        assertThat(auctionMatcher.getTradableQuantity()).isEqualTo(1840);
    }

    @Test
    void findTradableQuantityIfTotalOfSellQueueIsLower() {
        AuctionMatcher auctionMatcher = new AuctionMatcher();
        Order newOrder = new Order(11, security, Side.BUY, 1000, 15870, 0, broker2, shareholder, 0);
        security.setLastTradePrice(10000);
        auctionMatcher.updateOpeningPriceWithNewOrder(newOrder);
        assertThat(auctionMatcher.getTradableQuantity()).isEqualTo(2318);
    }

    @Test
    void findTradableQuantityIfMaxSellQueuePriceIsMoreThanMinBuyQueuePrice() {
        AuctionMatcher auctionMatcher = new AuctionMatcher();
        Order newOrder = new Order(11, security, Side.BUY, 1200, 15470, 0, broker2, shareholder, 0);
        security.setLastTradePrice(10000);
        auctionMatcher.updateOpeningPriceWithNewOrder(newOrder);
        assertThat(auctionMatcher.getTradableQuantity()).isEqualTo(1971);
    }

    @Test
    void findTradableQuantityIfMaxSellQueuePriceIsLowerThanMinBuyQueuePrice() {
        AuctionMatcher auctionMatcher = new AuctionMatcher();
        Order newOrder = new Order(11, security, Side.BUY, 1200, 15470, 0, broker2, shareholder, 0);
        security.setLastTradePrice(10000);
        auctionMatcher.updateOpeningPriceWithNewOrder(newOrder);
        assertThat(auctionMatcher.getTradableQuantity()).isEqualTo(1971);
    }

    @Test
    void findTradableQuantityWithIcebergOrder() {
        AuctionMatcher auctionMatcher = new AuctionMatcher();
        Order newOrder = new IcebergOrder(11, security, Side.BUY , 200, 15800 , 0 , broker2, shareholder, LocalDateTime.now() , 0 ,  100,  OrderStatus.QUEUED , 0);
        security.setLastTradePrice(10000);
        auctionMatcher.updateOpeningPriceWithNewOrder(newOrder);
        assertThat(auctionMatcher.getTradableQuantity()).isEqualTo(2040);
    }

    @Test
    void findTradableQuantityWithNoOrderRequest(){
        AuctionMatcher auctionMatcher = new AuctionMatcher();
        Order newOrder = new Order(11, security, Side.BUY , 300, 15800 , 0 , broker2, shareholder, 0 );
        security.setLastTradePrice(10000);
        auctionMatcher.updateOpeningPriceWithNewOrder(newOrder);
        assertThat(auctionMatcher.getTradableQuantity()).isEqualTo(2140);
        auctionMatcher.findOpeningPrice(security);
        assertThat(auctionMatcher.getTradableQuantity()).isEqualTo(2140);
    }

    @Test
    void findOpenPriceWhichOneCandidateClosestAndLowerToLastTradePrice(){
        AuctionMatcher auctionMatcher = new AuctionMatcher();
        Order newOrder = new Order(11, security, Side.BUY, 300, 15800, 0, broker2, shareholder, 0);
        security.setLastTradePrice(15820);
        auctionMatcher.updateOpeningPriceWithNewOrder(newOrder);
        assertThat(auctionMatcher.getOpeningPrice()).isEqualTo(15800);
    }

    @Test
    void findOpenPriceWhichTwoCandidateClosestToLastTradePrice(){
        AuctionMatcher auctionMatcher = new AuctionMatcher();
        security.setLastTradePrice(10000);
        LinkedList<Integer> prices = new LinkedList<>();
        prices.add(10005);
        prices.add(9995);
        prices.add(9990);
        prices.add(10010);
        int bestOpenPrice = auctionMatcher.findClosestToLastTradePrice(prices, security);
        assertThat(bestOpenPrice).isEqualTo(9995);
    }

    @Test
    void addNewOrderButDontChangeOpenPrice(){
        AuctionMatcher auctionMatcher = new AuctionMatcher();
        Order newOrder = new Order(11, security, Side.BUY, 300, 15800, 0, broker2, shareholder, 0);
        security.setLastTradePrice(15820);
        auctionMatcher.updateOpeningPriceWithNewOrder(newOrder);
        assertThat(auctionMatcher.getOpeningPrice()).isEqualTo(15800);
        Order newOrder2 = new Order(12, security, Side.BUY, 3000, 15900, 0, broker2, shareholder, 0);
        auctionMatcher.updateOpeningPriceWithNewOrder(newOrder2);
        assertThat(auctionMatcher.getOpeningPrice()).isEqualTo(15800);
    }

    @Test
    void addNewOrderButDontChangeTradableQuantity(){
        AuctionMatcher auctionMatcher = new AuctionMatcher();
        Order newOrder = new Order(11, security, Side.BUY, 300, 15420, 0, broker2, shareholder, 0);
        security.setLastTradePrice(10000);
        auctionMatcher.updateOpeningPriceWithNewOrder(newOrder);
        assertThat(auctionMatcher.getTradableQuantity()).isEqualTo(1840);
        Order newOrder2 = new Order(12, security, Side.BUY, 300, 15400, 0, broker2, shareholder, 0);
        auctionMatcher.updateOpeningPriceWithNewOrder(newOrder2);
        assertThat(auctionMatcher.getTradableQuantity()).isEqualTo(1840);
    }

    @Test
    void addNewOrderChangeTradableQuantityButDontChangeOpenPrice(){
        AuctionMatcher auctionMatcher = new AuctionMatcher();
        Order newOrder = new Order(11, security, Side.BUY, 300, 15800, 0, broker2, shareholder, 0);
        security.setLastTradePrice(15820);
        auctionMatcher.updateOpeningPriceWithNewOrder(newOrder);
        assertThat(auctionMatcher.getOpeningPrice()).isEqualTo(15800);
        assertThat(auctionMatcher.getTradableQuantity()).isEqualTo(2140);
        Order newOrder2 = new Order(12, security, Side.BUY, 360, 15900, 0, broker2, shareholder, 0);
        auctionMatcher.updateOpeningPriceWithNewOrder(newOrder2);
        assertThat(auctionMatcher.getOpeningPrice()).isEqualTo(15800);
        assertThat(auctionMatcher.getTradableQuantity()).isEqualTo(2318);
    }

    @Test
    void addNewOrderChangeOpenPriceButDontChangeTradableQuantity(){
        AuctionMatcher auctionMatcher = new AuctionMatcher();
        Order newOrder = new Order(11, security, Side.BUY, 300, 15420, 0, broker2, shareholder, 0);
        security.setLastTradePrice(10000);
        auctionMatcher.updateOpeningPriceWithNewOrder(newOrder);
        assertThat(auctionMatcher.getTradableQuantity()).isEqualTo(1840);
        assertThat(auctionMatcher.getOpeningPrice()).isEqualTo(15450);
        Order newOrder2 = new Order(12, security, Side.SELL, 1000, 15440, 0, broker2, shareholder, 0);
        auctionMatcher.updateOpeningPriceWithNewOrder(newOrder2);
        assertThat(auctionMatcher.getTradableQuantity()).isEqualTo(1840);
        assertThat(auctionMatcher.getOpeningPrice()).isEqualTo(15440);
    }

    @Test
    void adding_minimum_execution_quantity_for_buy_order_in_auction_state_successfully_rejected() {
        assertThat(security.getOrderBook().getBuyQueue().size()).isEqualTo(5);
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
    void adding_minimum_execution_quantity_for_sell_order_in_auction_state_successfully_rejected() {
        assertThat(security.getOrderBook().getSellQueue().size()).isEqualTo(5);
        orderHandler.handleChangeMatchingState(new ChangeMatchingStateRq("ABC", MatchingState.AUCTION));
        EnterOrderRq enterOrderRq = EnterOrderRq.createNewOrderRq(1, "ABC", 100,
                LocalDateTime.now(), Side.SELL, 40, 500, broker1.getBrokerId(), shareholder.getShareholderId(),
                0, 10, 0, true);
        orderHandler.handleEnterOrder(enterOrderRq);
        verify(eventPublisher).publish((new OrderRejectedEvent(1, 100, List.of(Message.INVALID_ORDER_IN_AUCTION_STATE))));
        assertThat(security.getOrderBook().getSellQueue().size()).isEqualTo(5);
    }

    @Test
    void adding_stop_limit_order_for_buy_order_in_auction_state_successfully_rejected() {
        assertThat(security.getOrderBook().getBuyQueue().size()).isEqualTo(5);
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
    void adding_stop_limit_order_for_sell_order_in_auction_state_successfully_rejected() {
        assertThat(security.getOrderBook().getSellQueue().size()).isEqualTo(5);
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
    void update_price_for_sell_order_in_auction_state_successfully_done() {
        AuctionMatcher auctionMatcher = new AuctionMatcher();
        security.setMatchingState(MatchingState.AUCTION);
        orderHandler.handleEnterOrder(EnterOrderRq.createUpdateOrderRq(5, "ABC", 5,
                LocalDateTime.now(), Side.SELL, 1000, 16000, broker2.getBrokerId(), shareholder.getShareholderId(),
                0, 0, 0, false));
        assertThat(broker2.getCredit()).isEqualTo(100_000);
        assertThat(orderBook.getSellQueue().get(4).getPrice()).isEqualTo(16000);
        int openPrice = auctionMatcher.findOpeningPrice(security);
        assertThat(openPrice).isEqualTo(15800);
    }

    @Test
    void update_price_for_buy_order_in_auction_state_successfully_done() {
        AuctionMatcher auctionMatcher = new AuctionMatcher();
        security.setMatchingState(MatchingState.AUCTION);
        orderHandler.handleEnterOrder(EnterOrderRq.createUpdateOrderRq(5, "ABC", 10,
                LocalDateTime.now(), Side.BUY, 65, 15000, broker1.getBrokerId(), shareholder.getShareholderId(),
                0, 0, 0, false));
        assertThat(broker1.getCredit()).isEqualTo(153_300);
        assertThat(orderBook.getBuyQueue().get(4).getPrice()).isEqualTo(15000);
        int openPrice = auctionMatcher.findOpeningPrice(security);
        assertThat(openPrice).isEqualTo(15450);
    }

    @Test
    void update_quantity_for_sell_order_in_auction_state_successfully_done() {
        AuctionMatcher auctionMatcher = new AuctionMatcher();
        security.setMatchingState(MatchingState.AUCTION);
        orderHandler.handleEnterOrder(EnterOrderRq.createUpdateOrderRq(5, "ABC", 5,
                LocalDateTime.now(), Side.SELL, 100, 15400, broker2.getBrokerId(), shareholder.getShareholderId(),
                0, 0, 0, false));
        assertThat(broker2.getCredit()).isEqualTo(100_000);
        assertThat(orderBook.getSellQueue().get(0).getQuantity()).isEqualTo(100);
        int openPrice = auctionMatcher.findOpeningPrice(security);
        assertThat(openPrice).isEqualTo(15700);
    }

    @Test
    void update_quantity_for_buy_order_in_auction_state_successfully_done() {
        AuctionMatcher auctionMatcher = new AuctionMatcher();
        security.setMatchingState(MatchingState.AUCTION);
        orderHandler.handleEnterOrder(EnterOrderRq.createUpdateOrderRq(5, "ABC", 10,
                LocalDateTime.now(), Side.BUY, 55, 15820, broker1.getBrokerId(), shareholder.getShareholderId(),
                0, 0, 0, false));
        assertThat(broker1.getCredit()).isEqualTo(258200);
        assertThat(orderBook.getBuyQueue().get(1).getQuantity()).isEqualTo(55);
        int openPrice = auctionMatcher.findOpeningPrice(security);
        assertThat(openPrice).isEqualTo(15700);
    }


}