package matching;

/**
 * An instruction to buy or sell.
 *
 * @param id       unique order id, assigned by the caller
 * @param side     BUY or SELL
 * @param type     LIMIT or MARKET
 * @param price    limit price in pence; ignored (by convention 0) for MARKET orders
 * @param quantity number of units, always positive
 */
public record Order(long id, Side side, OrderType type, long price, long quantity) {

    // Compact constructor: runs before the fields are assigned, so we can reject bad input.
    public Order {
        if (side == null) {
            throw new IllegalArgumentException("side must not be null");
        }
        if (type == null) {
            throw new IllegalArgumentException("type must not be null");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive, got " + quantity);
        }
        if (type == OrderType.LIMIT && price <= 0) {
            throw new IllegalArgumentException("limit price must be positive, got " + price);
        }
    }

    public static Order limit(long id, Side side, long price, long quantity) {
        return new Order(id, side, OrderType.LIMIT, price, quantity);
    }

    public static Order market(long id, Side side, long quantity) {
        return new Order(id, side, OrderType.MARKET, 0, quantity);
    }
}
