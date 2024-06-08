package ir.ramtung.tinyme.domain;

import ir.ramtung.tinyme.domain.entity.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static ir.ramtung.tinyme.domain.entity.Side.BUY;
import static org.assertj.core.api.Assertions.assertThat;

class OrderBookTest {
    private Security security;
    private List<Order> orders;
    @BeforeEach
    void setupOrderBook() {
        security = Security.builder().build();
        Broker broker = Broker.builder().build();
        Shareholder shareholder = Shareholder.builder().build();
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
                        shareholder(shareholder).stopPrice(0).build()
        );
        orders.forEach(order -> security.getOrderBook().enqueue(order));
    }

    @Test
    void finds_the_first_order_by_id() {
        assertThat(security.getOrderBook().findByOrderId(Side.BUY, 1, false))
                .isEqualTo(orders.get(0));
    }

    @Test
    void fails_to_find_the_first_order_by_id_in_the_wrong_queue() {
        assertThat(security.getOrderBook().findByOrderId(Side.SELL, 1, false)).isNull();
    }

    @Test
    void finds_some_order_in_the_middle_by_id() {
        assertThat(security.getOrderBook().findByOrderId(Side.BUY, 3, false))
                .isEqualTo(orders.get(2));
    }

    @Test
    void finds_the_last_order_by_id() {
        assertThat(security.getOrderBook().findByOrderId(Side.SELL, 10, false))
                .isEqualTo(orders.get(9));
    }

    @Test
    void removes_the_first_order_by_id() {
        OrderBook orderBook = security.getOrderBook();
        orderBook.removeByOrderId(Side.BUY, 1, false);
        assertThat(orderBook.getBuyQueue()).isEqualTo(orders.subList(1, 5));
    }

    @Test
    void fails_to_remove_the_first_order_by_id_in_the_wrong_queue() {
        OrderBook orderBook = security.getOrderBook();
        orderBook.removeByOrderId(Side.SELL, 1, false);
        assertThat(orderBook.getBuyQueue()).isEqualTo(orders.subList(0, 5));
    }

    @Test
    void removes_the_last_order_by_id() {
        OrderBook orderBook = security.getOrderBook();
        orderBook.removeByOrderId(Side.SELL, 10, false);
        assertThat(orderBook.getSellQueue()).isEqualTo(orders.subList(5, 9));
    }
}