package ir.ramtung.tinyme.domain.service;

import ir.ramtung.tinyme.domain.entity.Trade;
import ir.ramtung.tinyme.domain.entity.Order;
import ir.ramtung.tinyme.domain.entity.Security;
import ir.ramtung.tinyme.domain.entity.MatchResult;
import lombok.Getter;
import org.springframework.stereotype.Service;

import java.util.LinkedList;
import java.util.List;

@Getter
@Service
public class AuctionMatcher {
    private int openingPrice;
    private int tradableQuantity;

    public LinkedList<Trade> execute(Security security) {
        updateOpeningPriceWithNewOrderOperations uopwnoo = new updateOpeningPriceWithNewOrderOperations();
        executeOperations exe = new executeOperations();
        LinkedList<Trade> trades = new LinkedList<>();
        List<Integer> tradableQuantityOpenningPrice = uopwnoo.findOpeningPrice(security , tradableQuantity);
        tradableQuantity = tradableQuantityOpenningPrice.get(0).intValue();
        openingPrice = tradableQuantityOpenningPrice.get(1).intValue();
        LinkedList<Order> chosenSide = exe.chooseSide(security.getOrderBook() , openingPrice);
        for (Order order : chosenSide)
            trades.addAll(exe.match(order , openingPrice));
        return trades;
    }

    public MatchResult updateOpeningPriceWithNewOrder(Order order) {
        updateOpeningPriceWithNewOrderOperations uopwnoo = new updateOpeningPriceWithNewOrderOperations();
        order.getSecurity().getOrderBook().enqueue(order);
        List<Integer> tradableQuantityOpenningPrice = uopwnoo.findOpeningPrice(order.getSecurity() , tradableQuantity);
        tradableQuantity = tradableQuantityOpenningPrice.get(0).intValue();
        openingPrice = tradableQuantityOpenningPrice.get(1).intValue();
        return MatchResult.openingPriceHasBeenSet(order.getSecurity().getIsin(), openingPrice, tradableQuantity);
    }


}
