package com.example.order.model;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Currency;
import java.util.List;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * Order aggregate: the order and its lines change together, only through this class. The total is derived from the
 * lines when the order is placed and never changes afterwards.
 */
@Entity
@Table(name = "orders")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)   // for JPA
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private OrderStatus status = OrderStatus.PLACED;

    @Column(name = "currency", nullable = false, length = 3, updatable = false)
    private String currency;

    @Getter(AccessLevel.NONE)   // exposed at the currency's scale, see getTotalAmount()
    @Column(name = "total_amount", nullable = false, precision = 19, scale = 4, updatable = false)
    private BigDecimal totalAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "cancellation_reason", length = 30)
    private CancellationReason cancellationReason;

    @Column(name = "placed_at", nullable = false, updatable = false)
    private Instant placedAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @Getter(AccessLevel.NONE)
    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("lineNo ASC")
    private List<OrderLine> lines = new ArrayList<>();

    private Order(long userId, Currency currency, Instant placedAt) {
        this.userId = userId;
        this.currency = currency.getCurrencyCode();
        this.placedAt = placedAt;
    }

    /**
     * Places a new order. Amounts are kept at the currency's minor units; line totals and the order total are
     * computed here, never taken from the caller.
     *
     * @throws IllegalArgumentException when there are no lines or an amount has more decimals than the currency allows
     */
    public static Order place(long userId, Currency currency, List<NewOrderLine> newLines, Instant now) {
        Objects.requireNonNull(currency, "currency");
        if (newLines == null || newLines.isEmpty()) {
            throw new IllegalArgumentException("An order needs at least one line");
        }
        Order order = new Order(userId, currency, now);
        BigDecimal total = Money.normalize(BigDecimal.ZERO, currency);
        int lineNo = 1;
        for (NewOrderLine line : newLines) {
            if (!Money.fitsMinorUnits(line.unitPrice(), currency)) {
                throw new IllegalArgumentException("Unit price has more decimals than " + currency + " allows");
            }
            OrderLine orderLine = new OrderLine(order, lineNo++, line.sku().strip(), line.quantity(),
                    Money.normalize(line.unitPrice(), currency));
            order.lines.add(orderLine);
            total = total.add(orderLine.getLineTotal());
        }
        order.totalAmount = total;
        return order;
    }

    /**
     * Cancellation is idempotent.
     *
     * @return {@code true} only on the transition PLACED → CANCELLED
     */
    public boolean cancel(CancellationReason reason, Instant now) {
        Objects.requireNonNull(reason, "reason");
        if (status == OrderStatus.CANCELLED) {
            return false;
        }
        status = OrderStatus.CANCELLED;
        cancellationReason = reason;
        cancelledAt = now;
        return true;
    }

    public Currency currencyUnit() {
        return Currency.getInstance(currency);
    }

    /** The database keeps 4 decimals for every currency; callers always see the currency's own scale. */
    public BigDecimal getTotalAmount() {
        return Money.normalize(totalAmount, currencyUnit());
    }

    public List<OrderLine> getLines() {
        return Collections.unmodifiableList(lines);
    }
}
