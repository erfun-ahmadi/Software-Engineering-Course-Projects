package ir.ramtung.tinyme.domain.service;

import ir.ramtung.tinyme.domain.entity.*;

import java.util.LinkedList;

public class AuctionMatcher extends Matcher {
    private LinkedList<MatchResult> matchResults = new LinkedList<>();
    private int openPrice;

    @Override
    public MatchResult match(Order newOrder) {
        //todo
        return null;
    }

    @Override
    public LinkedList<MatchResult> execute(Order order) {
        order.getSecurity().getOrderBook().enqueue(order);
        openPrice = findOpenPrice();
        MatchResult result = match(order);

        //todo
    }

    private int findOpenPrice(){
        return 190;
    }
}
