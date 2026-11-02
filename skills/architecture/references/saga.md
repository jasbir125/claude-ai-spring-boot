# Saga Pattern

A saga replaces a distributed transaction with a sequence of local transactions. Each step publishes an event (or replies to a command); a failure triggers **compensating** transactions for the steps already completed. There is no rollback, only semantic undo.

## Choreography vs Orchestration

| Question | Choreography | Orchestration |
|--------|--------------|---------------|
| Control | Each service reacts to events | A saga orchestrator sends commands and tracks state |
| Coupling | Low, but the flow is implicit | Orchestrator knows all participants |
| Visibility | Hard to see the whole flow | State is in one place, easy to query and monitor |
| Good for | 2–3 steps, simple compensation | 4+ steps, branching, timeouts, complex compensation |

Default: choreography for short flows, orchestration as soon as you need timeouts, branching, or an answer to "where is order 42 stuck?".

## Designing a Saga

1. List the steps and the service that owns each local transaction.
2. For every step define: the command/event, the success event, the failure event, the **compensation**, and whether the step is a **pivot** (point of no return) or **retriable** (must eventually succeed).
3. Order steps: compensatable steps first, then the pivot, then retriable steps.
4. Make every handler idempotent and every compensation idempotent and commutative with late messages.
5. Define timeouts per step and what happens on timeout (usually compensate).
6. Use semantic locks for intermediate states (`PENDING`, `RESERVED`) so other requests see the in-flight state.

Example (place order):

| # | Service | Action | Compensation | Type |
|---|---------|--------|--------------|------|
| 1 | Order | Create order `PENDING` | Mark `CANCELLED` | compensatable |
| 2 | Inventory | Reserve stock | Release reservation | compensatable |
| 3 | Payment | Charge card | Refund | **pivot** |
| 4 | Order | Mark `CONFIRMED` | — | retriable |
| 5 | Shipping | Create shipment | — | retriable |

## Orchestrator with Spring Boot

```java
@Entity
@Table(name = "order_saga")
public class OrderSaga {
    @Id
    private UUID id;                 // = correlationId
    private String orderId;
    @Enumerated(EnumType.STRING)
    private OrderSagaState state;    // STARTED, STOCK_RESERVED, PAID, COMPLETED, COMPENSATING, FAILED
    private Instant deadline;
    @Version
    private long version;
    // @Getter (Lombok); state transitions as methods with guard checks, no @Setter
}

@Service
public class OrderSagaOrchestrator {

    private final OrderSagaRepository sagas;
    private final OutboxWriter outbox;

    public OrderSagaOrchestrator(OrderSagaRepository sagas, OutboxWriter outbox) {
        this.sagas = sagas;
        this.outbox = outbox;
    }

    @Transactional
    public void on(EventEnvelope<StockReserved> event) {
        var saga = sagas.findById(UUID.fromString(event.correlationId())).orElseThrow();
        if (!saga.transitionTo(OrderSagaState.STOCK_RESERVED)) {
            return;                                  // duplicate or late message: ignore
        }
        outbox.append("OrderSaga", saga.getId().toString(), new ChargePayment(saga.getOrderId()));
    }

    @Transactional
    public void on(EventEnvelope<PaymentFailed> event) {
        var saga = sagas.findById(UUID.fromString(event.correlationId())).orElseThrow();
        if (!saga.transitionTo(OrderSagaState.COMPENSATING)) {
            return;
        }
        outbox.append("OrderSaga", saga.getId().toString(), new ReleaseStock(saga.getOrderId()));
    }
}
```

- State changes and outgoing commands are written in one transaction (outbox).
- A scheduled job (ShedLock-protected) finds sagas past `deadline` and compensates them.
- Expose saga state through a read endpoint and metrics (`saga_active`, `saga_compensated_total`, `saga_stuck`).
- For complex flows consider a workflow engine (Temporal, Camunda) — record the choice in an ADR.

## Testing

- Unit-test the state machine: every valid transition, every invalid/duplicate transition is ignored.
- Integration test with Testcontainers (Kafka + DB): happy path, failure at each step triggers the right compensations, duplicate messages, timeout.
