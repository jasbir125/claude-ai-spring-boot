# order-service: how it is built (diagrams)

## How to read this page

Each section has a **picture** and a **plain-language explanation** under it. The pictures use the real names from the code (classes, tables, channels, messages), so a developer can find each box in the code. You can follow the explanations without knowing those names.

The `docs-writer` agent keeps these pictures up to date: any change to the code's flows, tables, or messages must update them in the same change.

Common names in the pictures:

| Name in the picture | What it is |
|---------------------|------------|
| API client | Any program that calls this service, with a login token |
| Keycloak | The login server that hands out tokens |
| PostgreSQL | The database |
| Kafka | The message system that delivers events between services |
| `known_user` | order-service's own short list of users, built only from user-service's messages |
| `outbox_event` | The table where outgoing messages wait until they are sent |
| `processed_event` | The *inbox*: IDs of incoming messages already handled, to skip duplicates |
| `OutboxRelay` | The background worker that sends waiting messages |
| DLT | Dead-letter topic: a separate channel for incoming messages that cannot be processed |

## 1. The big picture: who talks to whom

```mermaid
flowchart LR
    client["API client<br/>(OAuth2 client credentials)"]
    keycloak["Keycloak realm orders<br/>(issuer + JWKS)"]
    svc["order-service<br/>:8080"]
    db[("PostgreSQL<br/>database orders")]
    userTopic[["Kafka<br/>user.user.events.v1"]]
    userSvc["user-service<br/>(external, owns users)"]
    orderTopic[["Kafka<br/>order.order.events.v1"]]
    dlt[["Kafka<br/>user.user.events.v1.order-service-dlt"]]
    consumers["Consumers<br/>(external, none registered yet)"]
    prom["Prometheus<br/>(scrapes /actuator/prometheus)"]

    client -- "token request" --> keycloak
    client -- "REST /api/v1/orders + Bearer JWT" --> svc
    svc -- "JWKS / issuer check (lazy)" --> keycloak
    svc -- "JPA + Flyway" --> db
    userSvc -- "publishes" --> userTopic
    userTopic -- "UserEventsListener" --> svc
    svc -- "unprocessable records" --> dlt
    svc -- "OutboxRelay publishes" --> orderTopic
    orderTopic --> consumers
    prom -.-> svc
```

- A caller gets a **token** from the login server, then calls order-service with it.
- order-service stores orders in **its own database**. No other service reads or writes these tables.
- It **never calls user-service**. It learns about users only from user-service's messages and keeps a small local list (`known_user`). So orders can be placed even while user-service is down.
- It **announces** placed and cancelled orders on its own Kafka channel. Messages it cannot read go to its dead-letter channel.

## 2. Inside the service: the parts

```mermaid
flowchart TB
    subgraph web["Web layer"]
        filter["CorrelationIdFilter + SecurityConfig"]
        ctrl["OrderController<br/>/api/v1/orders"]
        err["ApiExceptionHandler<br/>Problem Details"]
    end
    subgraph app["Application"]
        orderSvc["OrderService"]
        projSvc["UserProjectionService"]
    end
    subgraph data["Persistence"]
        repos["OrderRepository<br/>KnownUserRepository<br/>ProcessedEventRepository"]
        tables[("orders, order_line,<br/>known_user, processed_event")]
    end
    subgraph msgIn["Messaging in"]
        listener["UserEventsListener<br/>+ UserEventParser"]
        errh["DefaultErrorHandler<br/>retries → DLT"]
    end
    subgraph msgOut["Messaging out (outbox)"]
        pub["OrderEventPublisher → OutboxWriter"]
        outbox[("outbox_event")]
        relay["OutboxRelay<br/>@Scheduled 500 ms, SKIP LOCKED"]
    end

    filter --> ctrl --> orderSvc
    ctrl -. errors .-> err
    listener --> projSvc
    listener -. failures .-> errh
    orderSvc --> repos
    projSvc --> repos
    repos --> tables
    orderSvc --> pub
    projSvc --> pub
    pub --> outbox
    relay --> outbox
```

Read it from top to bottom:

1. **Web layer.** Each request gets a tracking number (correlation ID) and has its token checked, then reaches `OrderController`, which only hands it on. Errors become standard error responses.
2. **Messaging in.** `UserEventsListener` reads user-service messages, checks them, and hands them to `UserProjectionService`. Failures go to the error handler (retries, then the dead-letter channel).
3. **Application.** `OrderService` places and cancels orders. `UserProjectionService` keeps the user list up to date and cancels open orders of deactivated users.
4. **Messaging out.** Both services write outgoing messages to `outbox_event` in the same database step as the change. `OutboxRelay` sends them to Kafka.

`ArchitectureTest` keeps the layers separate: for example, the controller cannot reach repositories, the domain model has no framework dependencies, and only the relay may use `KafkaTemplate`.

## 3. Placing an order: when everything goes well

```mermaid
sequenceDiagram
    autonumber
    actor C as API client
    participant F as CorrelationIdFilter / Security
    participant OC as OrderController
    participant OS as OrderService
    participant DB as PostgreSQL
    participant R as OutboxRelay
    participant K as Kafka

    C->>F: POST /api/v1/orders {userId, currency, lines}<br/>Bearer JWT (orders.write), X-Correlation-Id
    F->>OC: authenticated, correlationId in MDC
    OC->>OS: place(request) — already validated (@Valid)
    rect rgb(235, 245, 255)
    note over OS,DB: one transaction
    OS->>DB: SELECT known_user … FOR SHARE (must be ACTIVE)
    OS->>DB: INSERT orders + order_line (totals computed here)
    OS->>DB: INSERT outbox_event (OrderPlaced envelope)
    end
    OS-->>OC: OrderResponse
    OC-->>C: 201 Created + Location
    loop every 500 ms
        R->>DB: SELECT … FOR UPDATE SKIP LOCKED
        R->>K: send(key = orderId, envelope) and wait for ack
        R->>DB: UPDATE outbox_event SET published_at
    end
```

Step by step:

1. The caller sends the user ID, a currency, and the lines.
2. The request is checked: valid currency, 1–100 lines, quantities 1–1000, prices with no more decimals than the currency allows.
3. Inside **one database step** (blue box), the service checks the user is known and ACTIVE, computes line totals and the total itself (never trusts the caller), saves the order, and saves the "OrderPlaced" message. The user's row is locked *for share*, so the user cannot be deactivated half-way through.
4. The caller gets `201 Created` straight away, without waiting for Kafka. The relay sends the message afterwards.

## 4. Placing and cancelling: when something goes wrong

```mermaid
sequenceDiagram
    autonumber
    actor C as API client
    participant S as Security
    participant OC as OrderController
    participant OS as OrderService
    participant DB as PostgreSQL
    participant R as OutboxRelay
    participant K as Kafka

    C->>S: request without / with invalid token
    S-->>C: 401 Unauthorized
    C->>S: POST with an orders.read-only token
    S-->>C: 403 Forbidden

    C->>OC: POST {currency: "JPY", unitPrice: 1.5}
    OC-->>C: 400 Problem Details, errors[].code = scale

    C->>OC: POST for a user not in known_user / DEACTIVATED
    OC->>OS: place
    OS->>DB: SELECT known_user FOR SHARE → missing / DEACTIVATED
    OS-->>C: 422 reason USER_UNKNOWN / USER_NOT_ACTIVE — nothing written

    C->>OC: POST /{id}/cancel at the same time as another change
    OS-->>C: 409 "Version conflict"

    R->>K: send(event)
    K--xR: broker down / timeout (10 s)
    R->>DB: attempts + 1, published_at stays NULL, batch stops (keeps order)
    note over R,K: next run retries; outbox_pending grows until Kafka is back
```

| What happened | What the caller gets | What happens inside |
|---------------|---------------------|---------------------|
| No token, or a fake one | `401 Unauthorized` | Stopped at the door |
| A read-only token used to place or cancel | `403 Forbidden` | Stopped at the door |
| Invalid input (bad currency, no lines, too many decimals…) | `400 Bad Request`, listing each wrong field with a `code` | Nothing is saved |
| The user is unknown or deactivated | `422`, with `reason` `USER_UNKNOWN` or `USER_NOT_ACTIVE` | Nothing is saved, no message |
| The order does not exist | `404 Not Found` | — |
| Two changes to the same order at once | `409 Version conflict` | The second change is refused; reload and retry |
| Kafka is down | Nothing, the caller already got its answer | The message waits in the outbox; the relay retries, keeping the order. `outbox_pending` rises |

## 5. A user is deactivated: open orders are cancelled

```mermaid
sequenceDiagram
    autonumber
    participant K as Kafka user.user.events.v1
    participant L as UserEventsListener
    participant P as UserProjectionService
    participant DB as PostgreSQL
    participant H as DefaultErrorHandler
    participant D as Kafka …order-service-dlt

    K->>L: UserDeactivated {userId, version} (key = userId)
    L->>L: UserEventParser: valid envelope? correlationId → MDC
    L->>P: apply(eventId, event)
    rect rgb(235, 245, 255)
    note over P,DB: one transaction
    P->>DB: INSERT processed_event ON CONFLICT DO NOTHING
    alt already there (duplicate delivery)
        P-->>L: DUPLICATE — nothing else happens
    else first time
        P->>DB: SELECT known_user … FOR UPDATE (waits for running placements)
        P->>DB: status = DEACTIVATED if version is newer
        P->>DB: cancel each PLACED order (USER_DEACTIVATED)
        P->>DB: INSERT outbox_event OrderCancelled per order (causationId = eventId)
    end
    end
    L-->>K: commit offset
    K->>L: "{not json" or missing payload.userId
    L--xH: NonRetryableEventException
    H->>D: publish unchanged, no retry
    note over L,H: other errors: 4 retries (0.5 s … 4 s), then DLT
```

Step by step:

1. user-service announces that a user was deactivated.
2. The listener checks the message and hands it on.
3. In **one database step**: the message ID is recorded in the inbox (a repeat is skipped here), the user is marked DEACTIVATED, every open order of that user is cancelled, and one "OrderCancelled" message per order is saved in the outbox. The user's row is locked, so an order being placed at that very moment either finishes first (and is then cancelled) or sees the deactivation (and gets 422).
4. Only then is the message marked as read in Kafka.
5. A broken message goes to the dead-letter channel immediately; anything else that keeps failing goes there after 4 retries. The channel keeps moving either way.

## 6. Which message causes which

```mermaid
flowchart LR
    subgraph in["Consumed (owned by user-service)"]
        ur["UserRegistered v1"]
        uu["UserUpdated v1"]
        ud["UserDeactivated v1"]
    end
    ut[["user.user.events.v1<br/>key = userId"]]
    ur & uu & ud --> ut
    ut --> l["UserEventsListener<br/>group order-service"]
    l -- "unprocessable" --> dlt[["user.user.events.v1.order-service-dlt"]]
    l --> ku[("known_user")]

    place["POST /orders"] --> op["OrderPlaced v1"]
    cancel["POST /orders/{id}/cancel<br/>(first call only)"] --> oc["OrderCancelled v1"]
    l -- "UserDeactivated:<br/>per open order" --> oc
    op & oc --> outbox[("outbox_event")] --> relay["OutboxRelay"]
    relay -- "key = orderId<br/>headers: eventId, eventType, schemaVersion" --> ot[["order.order.events.v1<br/>3 partitions (local)"]]
    ot --> c["Consumers (external)<br/>dedupe on eventId,<br/>drop stale by version"]
```

- **Incoming:** user-service's three messages update `known_user`. Unreadable ones go to the dead-letter channel.
- **Outgoing:** placing an order sends "OrderPlaced". Cancelling sends "OrderCancelled", either from the API (once per order) or for each open order when its user is deactivated.

Contents of each message: [events.md](events.md).

## 7. What is stored in the database

```mermaid
erDiagram
    known_user ||--o{ orders : "fk_orders_known_user"
    orders ||--|{ order_line : "fk_order_line_order (ON DELETE CASCADE)"
    known_user {
        bigint user_id PK "user-service ID"
        varchar status "ACTIVE | DEACTIVATED (ck_known_user_status)"
        bigint user_version "user-service version, >= 0"
        timestamptz created_at
        timestamptz updated_at
    }
    orders {
        bigint id PK "identity"
        bigint user_id FK
        varchar status "PLACED | CANCELLED (ck_orders_status)"
        varchar currency "^[A-Z]{3}$"
        numeric total_amount "19,4, >= 0"
        varchar cancellation_reason "CUSTOMER_REQUEST | USER_DEACTIVATED"
        timestamptz placed_at
        timestamptz cancelled_at "set iff CANCELLED (ck_orders_cancellation_consistent)"
        bigint version "optimistic lock"
    }
    order_line {
        bigint id PK
        bigint order_id FK "uk_order_line_order_line_no with line_no"
        int line_no ">= 1"
        varchar sku "1-64 chars"
        int quantity "1-1000"
        numeric unit_price "19,4, >= 0"
        numeric line_total "19,4, >= 0"
    }
    outbox_event {
        uuid id PK "= envelope eventId"
        varchar aggregate_type "Order"
        varchar aggregate_id "order id"
        varchar event_type
        varchar topic
        jsonb payload "full envelope"
        timestamptz published_at "NULL = pending"
        int attempts
    }
    processed_event {
        uuid event_id PK "consumed eventId"
        varchar handler PK "known-user-projection"
        timestamptz processed_at
    }
```

- **`known_user`**: one row per user order-service has heard of. No names or emails: only "may this user order?" and the last version seen.
- **`orders`** and **`order_line`**: an order and its lines. The database itself enforces the rules: valid status, a cancelled order always has a reason and a time, quantities and amounts in range. Amounts are stored with 4 decimals; the service always returns them with the currency's own number of decimals.
- **`outbox_event`**: outgoing messages; sent ones are deleted after 7 days. Linked to orders by `aggregate_id` only (no database link).
- **`processed_event`**: IDs of handled incoming messages; deleted after 30 days.

Created by `V1__create_known_user_and_orders.sql` and `V2__create_outbox_event_and_processed_event.sql`.

## 8. The life of an order and of a known user

```mermaid
stateDiagram-v2
    state "Order" as O {
        [*] --> PLACED : POST /orders / OrderPlaced
        PLACED --> CANCELLED : POST /{id}/cancel / OrderCancelled (CUSTOMER_REQUEST)
        PLACED --> CANCELLED : UserDeactivated / OrderCancelled (USER_DEACTIVATED)
        CANCELLED --> CANCELLED : cancel again / no event
    }
    state "known_user" as U {
        [*] --> ACTIVE : UserRegistered
        [*] --> DEACTIVATED : UserDeactivated (never seen before)
        ACTIVE --> ACTIVE : UserUpdated (newer version)
        ACTIVE --> DEACTIVATED : UserDeactivated (newer version)
        DEACTIVATED --> DEACTIVATED : UserUpdated (version only)
    }
```

- An order starts **PLACED** and can only move to **CANCELLED**, once. Cancelling again changes nothing and sends nothing.
- A known user starts **ACTIVE** when registered. Deactivation is final, because user-service has no way to reactivate a user. Older or repeated messages change nothing.
