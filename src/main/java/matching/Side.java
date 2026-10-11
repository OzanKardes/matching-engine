package matching;

/** Which side of the book an order belongs to. */
public enum Side {
    BUY,
    SELL;

    /** The side this order would trade against. */
    public Side opposite() {
        return this == BUY ? SELL : BUY;
    }
}
