package matching;

/**
 * LIMIT: trade at this price or better; any unfilled remainder rests on the book.
 * MARKET: trade at whatever prices are available; any unfilled remainder is discarded.
 */
public enum OrderType {
    LIMIT,
    MARKET
}
