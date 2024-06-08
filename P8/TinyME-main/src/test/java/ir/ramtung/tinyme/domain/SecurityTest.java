package ir.ramtung.tinyme.domain;

import ir.ramtung.tinyme.config.MockedJMSTestConfig;
import ir.ramtung.tinyme.domain.entity.*;
import ir.ramtung.tinyme.domain.service.AuctionMatcher;
import ir.ramtung.tinyme.domain.service.ContinuousMatcher;
import ir.ramtung.tinyme.messaging.exception.InvalidRequestException;
import ir.ramtung.tinyme.messaging.request.DeleteOrderRq;
import ir.ramtung.tinyme.messaging.request.EnterOrderRq;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

import static ir.ramtung.tinyme.domain.entity.Side.BUY;
import static ir.ramtung.tinyme.domain.entity.Side.SELL;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@Import(MockedJMSTestConfig.class)
class SecurityTest {
    private Security security;
    private Broker broker;
    private Shareholder shareholder;
    private List<Order> orders;
    @Autowired
    ContinuousMatcher continuousMatcher;
    @Autowired
    AuctionMatcher auctionMatcher;

    @BeforeEach
    void setupOrderBook() {
        security = Security.builder().build();
        broker = Broker.builder().brokerId(0).credit(1_000_000L).build();
        shareholder = Shareholder.builder().shareholderId(0).build();
        shareholder.incPosition(security, 100_000);
        orders = Arrays.asList(
                Order.builder().orderId(1).security(security).side(BUY).quantity(304).price(15700).
                        minimumExecutionQuantity(0).broker(broker).
                        shareholder(shareholder).stopPrice(0).build(),
                Order.builder().orderId(2).security(security).side(BUY).quantity(43).price(15500).
                        minimumExecutionQuantity(0).broker(broker).
                        shareholder(shareholder).stopPrice(0).build(),
                Order.builder().orderId(3).security(security).side(BUY).quantity(445).price(15450).
                        minimumExecutionQuantity(0).broker(broker).
                        shareholder(shareholder).stopPrice(0).build(),
                Order.builder().orderId(4).security(security).side(BUY).quantity(526).price(15450).
                        minimumExecutionQuantity(0).broker(broker).
                        shareholder(shareholder).stopPrice(0).build(),
                Order.builder().orderId(5).security(security).side(BUY).quantity(1000).price(15400).
                        minimumExecutionQuantity(0).broker(broker).
                        shareholder(shareholder).stopPrice(0).build(),
                Order.builder().orderId(6).security(security).side(Side.SELL).quantity(350).price(15800).
                        minimumExecutionQuantity(0).broker(broker).
                        shareholder(shareholder).stopPrice(0).build(),
                Order.builder().orderId(7).security(security).side(Side.SELL).quantity(285).price(15810).
                        minimumExecutionQuantity(0).broker(broker).
                        shareholder(shareholder).stopPrice(0).build(),
                Order.builder().orderId(8).security(security).side(Side.SELL).quantity(800).price(15810).
                        minimumExecutionQuantity(0).broker(broker).
                        shareholder(shareholder).stopPrice(0).build(),
                Order.builder().orderId(9).security(security).side(Side.SELL).quantity(340).price(15820).
                        minimumExecutionQuantity(0).broker(broker).
                        shareholder(shareholder).stopPrice(0).build(),
                Order.builder().orderId(10).security(security).side(Side.SELL).quantity(65).price(15820).
                        minimumExecutionQuantity(0).broker(broker).
                        shareholder(shareholder).stopPrice(0).build(),
                Order.builder().orderId(11).security(security).side(Side.SELL).quantity(400).price(15900).
                        minimumExecutionQuantity(100).broker(broker).
                        shareholder(shareholder).stopPrice(0).build()
        );
        orders.forEach(order -> security.getOrderBook().enqueue(order));
    }

    @Test
    void reducing_quantity_does_not_change_priority() {
        EnterOrderRq updateOrderRq = EnterOrderRq.createUpdateOrderRq(1, security.getIsin(), 3, LocalDateTime.now(), BUY, 440, 15450, 0, 0, 0, 0, 0, false);
        assertThatNoException().isThrownBy(() -> security.updateOrder(updateOrderRq, continuousMatcher, auctionMatcher));
        assertThat(security.getOrderBook().getBuyQueue().get(2).getQuantity()).isEqualTo(440);
        assertThat(security.getOrderBook().getBuyQueue().get(2).getOrderId()).isEqualTo(3);
    }

    @Test
    void increasing_quantity_changes_priority() {
        EnterOrderRq updateOrderRq = EnterOrderRq.createUpdateOrderRq(1, security.getIsin(), 3, LocalDateTime.now(), BUY, 450, 15450, 0, 0, 0, 0, 0, false);
        assertThatNoException().isThrownBy(() -> security.updateOrder(updateOrderRq, continuousMatcher, auctionMatcher));
        assertThat(security.getOrderBook().getBuyQueue().get(3).getQuantity()).isEqualTo(450);
        assertThat(security.getOrderBook().getBuyQueue().get(3).getOrderId()).isEqualTo(3);
    }

    @Test
    void changing_price_changes_priority() {
        EnterOrderRq updateOrderRq = EnterOrderRq.createUpdateOrderRq(1, security.getIsin(), 1, LocalDateTime.now(), BUY, 300, 15450, 0, 0, 0, 0, 0, false);
        assertThatNoException().isThrownBy(() -> security.updateOrder(updateOrderRq, continuousMatcher, auctionMatcher));
        assertThat(security.getOrderBook().getBuyQueue().get(3).getQuantity()).isEqualTo(300);
        assertThat(security.getOrderBook().getBuyQueue().get(3).getPrice()).isEqualTo(15450);
        assertThat(security.getOrderBook().getBuyQueue().get(3).getOrderId()).isEqualTo(1);
        assertThat(security.getOrderBook().getBuyQueue().get(0).getOrderId()).isEqualTo(2);
    }

    @Test
    void changing_price_causes_trades_to_happen() {
        EnterOrderRq updateOrderRq = EnterOrderRq.createUpdateOrderRq(1, security.getIsin(), 6, LocalDateTime.now(), Side.SELL, 350, 15700, 0, 0, 0, 0, 0, false);
        assertThatNoException().isThrownBy(() ->
                assertThat(security.updateOrder(updateOrderRq, continuousMatcher, auctionMatcher).getFirst().trades()).isNotEmpty()
        );
    }

    @Test
    void updating_non_existing_order_fails() {
        EnterOrderRq updateOrderRq = EnterOrderRq.createUpdateOrderRq(1, security.getIsin(), 6, LocalDateTime.now(), BUY, 350, 15700, 0, 0, 0, 0, 0, false);
        assertThatExceptionOfType(InvalidRequestException.class).isThrownBy(() -> security.updateOrder(updateOrderRq, continuousMatcher, auctionMatcher));
    }

    @Test
    void trying_to_change_minimum_execution_quantity_fails(){
        EnterOrderRq updateOrderRq = EnterOrderRq.createUpdateOrderRq(1, security.getIsin(), 11, LocalDateTime.now(), Side.SELL, 400, 15600, 0, 0, 0, 0, 0, false);
        assertThatExceptionOfType(InvalidRequestException.class).isThrownBy(() -> security.updateOrder(updateOrderRq, continuousMatcher, auctionMatcher));
    }

    @Test
    void delete_order_works() {
        DeleteOrderRq deleteOrderRq = new DeleteOrderRq(1, security.getIsin(), Side.SELL, 6, 0, false);
        assertThatNoException().isThrownBy(() -> security.deleteOrder(deleteOrderRq));
        assertThat(security.getOrderBook().getBuyQueue()).isEqualTo(orders.subList(0, 5));
        assertThat(security.getOrderBook().getSellQueue()).isEqualTo(orders.subList(6, 11));
    }

    @Test
    void deleting_non_existing_order_fails() {
        DeleteOrderRq deleteOrderRq = new DeleteOrderRq(1, security.getIsin(), Side.SELL, 1, 0, false);
        assertThatExceptionOfType(InvalidRequestException.class).isThrownBy(() -> security.deleteOrder(deleteOrderRq));
    }

    @Test
    void increasing_iceberg_peak_size_changes_priority() {
        security = Security.builder().build();
        broker = Broker.builder().credit(1_000_000L).build();
        orders = Arrays.asList(
                Order.builder().orderId(1).security(security).side(BUY).quantity(304).price(15700).
                        minimumExecutionQuantity(0).broker(broker).
                        shareholder(shareholder).stopPrice(0).build(),
                Order.builder().orderId(2).security(security).side(BUY).quantity(43).price(15500).
                        minimumExecutionQuantity(0).broker(broker).
                        shareholder(shareholder).stopPrice(0).build(),
                IcebergOrder.builder().orderId(3).security(security).side(BUY).quantity(445).price(15450).
                        minimumExecutionQuantity(0).broker(broker).
                        shareholder(shareholder).peakSize(100).stopPrice(0).build(),
                Order.builder().orderId(4).security(security).side(BUY).quantity(526).price(15450).
                        minimumExecutionQuantity(0).broker(broker).
                        shareholder(shareholder).stopPrice(0).build(),
                Order.builder().orderId(5).security(security).side(BUY).quantity(1000).price(15400).
                        minimumExecutionQuantity(0).broker(broker).
                        shareholder(shareholder).stopPrice(0).build()
        );
        orders.forEach(order -> security.getOrderBook().enqueue(order));
        EnterOrderRq updateOrderRq = EnterOrderRq.createUpdateOrderRq(1, security.getIsin(), 3, LocalDateTime.now(), BUY, 445, 15450, 0, 0, 150, 0, 0, false);
        assertThatNoException().isThrownBy(() -> security.updateOrder(updateOrderRq, continuousMatcher, auctionMatcher));
        assertThat(security.getOrderBook().getBuyQueue().get(3).getQuantity()).isEqualTo(150);
        assertThat(security.getOrderBook().getBuyQueue().get(3).getOrderId()).isEqualTo(3);
    }

    @Test
    void decreasing_iceberg_quantity_to_amount_larger_than_peak_size_does_not_changes_priority() {
        security = Security.builder().build();
        broker = Broker.builder().build();
        orders = Arrays.asList(
                Order.builder().orderId(1).security(security).side(BUY).quantity(304).price(15700).
                        minimumExecutionQuantity(0).broker(broker).
                        shareholder(shareholder).stopPrice(0).build(),
                Order.builder().orderId(2).security(security).side(BUY).quantity(43).price(15500).
                        minimumExecutionQuantity(0).broker(broker).
                        shareholder(shareholder).stopPrice(0).build(),
                IcebergOrder.builder().orderId(3).security(security).side(BUY).quantity(445).price(15450).
                        minimumExecutionQuantity(0).broker(broker).
                        shareholder(shareholder).peakSize(100).stopPrice(0).build(),
                Order.builder().orderId(4).security(security).side(BUY).quantity(526).price(15450).
                        minimumExecutionQuantity(0).broker(broker).
                        shareholder(shareholder).stopPrice(0).build(),
                Order.builder().orderId(5).security(security).side(BUY).quantity(1000).price(15400).
                        minimumExecutionQuantity(0).broker(broker).
                        shareholder(shareholder).stopPrice(0).build()
        );
        orders.forEach(order -> security.getOrderBook().enqueue(order));
        EnterOrderRq updateOrderRq = EnterOrderRq.createUpdateOrderRq(1, security.getIsin(), 3, LocalDateTime.now(), BUY, 300, 15450, 0, 0, 100, 0, 0, false);
        assertThatNoException().isThrownBy(() -> security.updateOrder(updateOrderRq, continuousMatcher, auctionMatcher));
        assertThat(security.getOrderBook().getBuyQueue().get(2).getOrderId()).isEqualTo(3);
    }

    @Test
    void update_iceberg_that_loses_priority_with_no_trade_works() {
        security = Security.builder().isin("TEST").build();
        broker = Broker.builder().brokerId(1).credit(100).build();

        security.getOrderBook().enqueue(
                IcebergOrder.builder().orderId(1).security(security).side(BUY).quantity(100).price(9).
                        minimumExecutionQuantity(0).broker(broker).
                        shareholder(shareholder).peakSize(10).stopPrice(0).build()
        );

        EnterOrderRq updateReq = EnterOrderRq.createUpdateOrderRq(2, security.getIsin(), 1, LocalDateTime.now(), BUY, 100, 10, 0, 0, 10, 0, 0, false);
        assertThatNoException().isThrownBy(() -> security.updateOrder(updateReq, continuousMatcher, auctionMatcher));

        assertThat(broker.getCredit()).isEqualTo(0);
        assertThat(security.getOrderBook().getBuyQueue().get(0).getOrderId()).isEqualTo(1);
    }

    @Test
    void update_iceberg_order_decrease_peak_size() {
        security = Security.builder().isin("TEST").build();
        security.getOrderBook().enqueue(
                IcebergOrder.builder().orderId(1).security(security).side(BUY).quantity(20).price(10).
                        minimumExecutionQuantity(0).broker(broker).
                        shareholder(shareholder).peakSize(10).stopPrice(0).build()
        );

        EnterOrderRq updateReq = EnterOrderRq.createUpdateOrderRq(2, security.getIsin(), 1, LocalDateTime.now(), BUY, 20, 10, 0, 0, 5, 0, 0, false);
        assertThatNoException().isThrownBy(() -> security.updateOrder(updateReq, continuousMatcher, auctionMatcher));

        assertThat(security.getOrderBook().getBuyQueue().get(0).getQuantity()).isEqualTo(5);
    }

    @Test
    void update_iceberg_order_price_leads_to_match_as_new_order() throws InvalidRequestException {
        security = Security.builder().isin("TEST").build();
        shareholder.incPosition(security, 1_000);
        orders = List.of(
                Order.builder().orderId(1).security(security).side(BUY).quantity(15).price(10).
                        minimumExecutionQuantity(0).broker(broker).
                        shareholder(shareholder).stopPrice(0).build(),
                Order.builder().orderId(2).security(security).side(BUY).quantity(20).price(10).
                        minimumExecutionQuantity(0).broker(broker).
                        shareholder(shareholder).stopPrice(0).build(),
                Order.builder().orderId(3).security(security).side(BUY).quantity(40).price(10).
                        minimumExecutionQuantity(0).broker(broker).
                        shareholder(shareholder).stopPrice(0).build(),
                IcebergOrder.builder().orderId(4).security(security).side(SELL).quantity(30).price(12).
                        minimumExecutionQuantity(0).broker(broker).
                        shareholder(shareholder).peakSize(10).stopPrice(0).build()
        );
        orders.forEach(order -> security.getOrderBook().enqueue(order));

        EnterOrderRq updateReq = EnterOrderRq.createUpdateOrderRq(5, security.getIsin(), 4, LocalDateTime.now(), SELL, 30, 10, 0, 0, 10, 0, 0, false);

        MatchResult result = security.updateOrder(updateReq, continuousMatcher, auctionMatcher).getFirst();

        assertThat(result.outcome()).isEqualTo(MatchingOutcome.EXECUTED);
        assertThat(result.trades()).hasSize(2);
        assertThat(result.remainder().getQuantity()).isZero();
    }
}