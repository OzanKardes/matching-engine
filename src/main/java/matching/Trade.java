package matching;

/**
 * A fill between a resting order (already on the book) and an aggressing order (just arrived).
 * The trade always happens at the resting order's price.
 *
 * @param restingOrderId    id of the order that was waiting on the book
 * @param aggressingOrderId id of the incoming order that crossed the spread
 * @param price             execution price in pence
 * @param quantity          units exchanged, always positive
 */
public record Trade(long restingOrderId, long aggressingOrderId, long price, long quantity) {

    public Trade {
        if (price <= 0) {
            throw new IllegalArgumentException("trade price must be positive, got " + price);
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("trade quantity must be positive, got " + quantity);
        }
    }
}
