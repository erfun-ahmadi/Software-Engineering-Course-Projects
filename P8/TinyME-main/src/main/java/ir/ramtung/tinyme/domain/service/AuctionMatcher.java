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
        AuctionExecute exe = new AuctionExecute();
        LinkedList<Trade> trades = new LinkedList<>();
        List<Integer> tradableQuantityOpeningPrice = new updateOpeningPrice().findOpeningPrice(security);
        tradableQuantity = tradableQuantityOpeningPrice.get(0);
        openingPrice = tradableQuantityOpeningPrice.get(1);
        LinkedList<Order> chosenSide = exe.chooseSide(security.getOrderBook() , openingPrice);
        for (Order order : chosenSide)
            trades.addAll(exe.match(order , openingPrice));
        return trades;
    }

    public MatchResult updateOpeningPriceWithNewOrder(Order order) {
        order.getSecurity().getOrderBook().enqueue(order);
        List<Integer> tradableQuantityOpeningPrice = new updateOpeningPrice().findOpeningPrice(order.getSecurity());
        tradableQuantity = tradableQuantityOpeningPrice.get(0);
        openingPrice = tradableQuantityOpeningPrice.get(1);
        return MatchResult.openingPriceHasBeenSet(order.getSecurity().getIsin(), openingPrice, tradableQuantity);
    }


}
