package matching;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class DomainTypesTest {

    @Test
    void oppositeSide() {
        assertEquals(Side.SELL, Side.BUY.opposite());
        assertEquals(Side.BUY, Side.SELL.opposite());
    }

    @Test
    void recordsHaveValueEquality() {
        Order a = Order.limit(1, Side.BUY, 10_050, 100);
        Order b = Order.limit(1, Side.BUY, 10_050, 100);
        assertNotSame(a, b);   // two different objects in memory...
        assertEquals(a, b);    // ...but equal, because records compare field by field
    }

    @Test
    void accessorsReturnFields() {
        Order o = Order.limit(7, Side.SELL, 9_999, 25);
        assertEquals(7, o.id());
        assertEquals(Side.SELL, o.side());
        assertEquals(OrderType.LIMIT, o.type());
        assertEquals(9_999, o.price());
        assertEquals(25, o.quantity());
    }

    @Test
    void marketOrderHasZeroPrice() {
        Order o = Order.market(3, Side.BUY, 50);
        assertEquals(OrderType.MARKET, o.type());
        assertEquals(0, o.price());
    }

    @Test
    void rejectsNonPositiveQuantity() {
        assertThrows(IllegalArgumentException.class, () -> Order.limit(1, Side.BUY, 100, 0));
        assertThrows(IllegalArgumentException.class, () -> Order.limit(1, Side.BUY, 100, -5));
    }

    @Test
    void rejectsNonPositiveLimitPrice() {
        assertThrows(IllegalArgumentException.class, () -> Order.limit(1, Side.BUY, 0, 10));
    }

    @Test
    void rejectsNullSide() {
        assertThrows(IllegalArgumentException.class,
                () -> new Order(1, null, OrderType.LIMIT, 100, 10));
    }

    @Test
    void rejectsInvalidTrade() {
        assertThrows(IllegalArgumentException.class, () -> new Trade(1, 2, 100, 0));
        assertThrows(IllegalArgumentException.class, () -> new Trade(1, 2, 0, 10));
    }
}
