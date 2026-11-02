# order-service messages (events)

## What is this page?

order-service **sends** a short message (an *event*) whenever an order is placed or cancelled, so other services such as billing or shipping can react. It also **listens** to user-service's messages to learn which users may place orders.

This page is the **public contract** for the messages order-service sends, plus a description of how it reads the messages it receives.

Messages are saved in the database together with the change itself (table `outbox_event`) and a background worker (`OutboxRelay`) sends them to Kafka, the message system, afterwards. A message is never lost and never sent for a change that failed ([how the outbox works](../../../skills/messaging-patterns/references/outbox.md)).

## Messages this service sends

### The channel: `order.order.events.v1`

| Question | Answer |
|----------|--------|
| Who sends messages here? | Only `order-service` |
| What is the message key? | The order's ID. All messages about one order go to the same lane (partition), so they arrive **in order** |
| How many lanes (partitions)? | 3 locally (`ORDER_EVENTS_PARTITIONS`). In shared environments the platform team creates the channel (`KAFKA_CREATE_TOPICS=false`) |
| What does a message look like? | A JSON "envelope" (below), sent as text |
| Extra labels (headers) | `eventId`, `eventType`, `schemaVersion`, so you can filter without reading the body |
| Can a message arrive twice? | **Yes, occasionally** (at-least-once delivery). Remember the `eventId` values you have handled and skip repeats |
| Can messages arrive out of date? | Every message carries the order's `version`. Ignore a message whose version you have already seen or passed |
| Who listens today? | Nobody registered yet. Add your service here |

### The envelope

The same outside for every message; the content is in `payload`. It has the same shape as user-service's envelope.

| Field | Type | What it means |
|-------|------|---------------|
| `eventId` | UUID | Unique ID of this message. Use it to spot duplicates |
| `eventType` | text | `OrderPlaced` or `OrderCancelled`. Ignore types you don't know (new ones may be added) |
| `schemaVersion` | number | Version of the content layout. Only changes when the layout changes in a way that breaks old readers |
| `occurredAt` | time (UTC) | When it happened |
| `source` | text | Always `order-service` |
| `aggregateId` | text | The order's ID (the same as the message key) |
| `correlationId` | text | A tracking number that follows one action across services and logs. Taken from the `X-Correlation-Id` request header or from the user-service message that caused the change |
| `causationId` | text or empty | The `eventId` of the user-service message that caused this one (`UserDeactivated`); empty when a caller triggered it through the API |
| `payload` | object | The details, described per message below |

The order of the fields in the JSON is not fixed (the database stores the envelope as `jsonb`). Read fields by name.

**Amounts** are JSON **strings** holding a plain decimal number with exactly the currency's number of decimals (ISO 4217): `"25.50"` for EUR, `"1200"` for JPY, `"1.250"` for BHD. Strings keep the value exact in every language (no floating-point rounding, e.g. in JavaScript). Parse them as decimals (for example `new BigDecimal(text)`), always together with `currency`.

### `OrderPlaced` (version 1)

**Sent when** an order is created (`POST /api/v1/orders`). It carries the **complete order**, so you never have to call order-service to get it.

| Field | Type | What it means |
|-------|------|---------------|
| `orderId` | number | The new order's ID |
| `userId` | number | The user-service ID of the user who ordered |
| `currency` | text | ISO 4217 code, for example `EUR` |
| `totalAmount` | decimal string | Sum of all line totals |
| `lines` | list | The order lines, see below |
| `placedAt` | time (UTC) | When the order was placed |
| `version` | number | Always `0` for a new order |

Each entry in `lines`: `lineNo` (number, from 1), `sku` (text, max 64), `quantity` (1–1000), `unitPrice` (decimal string), `lineTotal` (decimal string, `unitPrice × quantity`).

Example: [`order-placed-v1.json`](../src/test/resources/contracts/events/produced/order-placed-v1.json)

### `OrderCancelled` (version 1)

**Sent when** an order changes from PLACED to CANCELLED, once per order. Cancelling an already cancelled order sends nothing.

| Field | Type | What it means |
|-------|------|---------------|
| `orderId` | number | The order's ID |
| `userId` | number | The user who ordered |
| `reason` | text | `CUSTOMER_REQUEST` (cancelled through the API) or `USER_DEACTIVATED` (user-service deactivated the user). New reasons may be added: treat unknown values as "cancelled, other reason" |
| `cancelledAt` | time (UTC) | When it was cancelled |
| `version` | number | The order's version after the change |

Example: [`order-cancelled-v1.json`](../src/test/resources/contracts/events/produced/order-cancelled-v1.json)

### Promises about future changes

- An automatic test (`EventContractTest`) compares every message with its example file. Any accidental change fails the build.
- **Adding** a new optional field is allowed at any time. Your code must ignore fields it doesn't know.
- **Removing, renaming, or changing the meaning** of a field is a breaking change. It gets `schemaVersion` 2, both versions are published side by side while consumers switch over, and the plan is written down in a decision record (ADR). See the [event contract rules](../../../skills/messaging-patterns/references/event-contracts.md).

## Messages this service receives

### From `user.user.events.v1` (owned by user-service)

The contract is user-service's: [user-service/docs/events.md](../../user-service/docs/events.md). Copies of its example files live in [`src/test/resources/contracts/events/consumed`](../src/test/resources/contracts/events/consumed) and `UserEventParserTest` proves order-service can read each of them.

| Question | Answer |
|----------|--------|
| Listener | `UserEventsListener` (listener ID `user-events`) |
| Consumer group | `order-service` (`spring.application.name`) |
| Where does a new deployment start? | At the oldest message Kafka still keeps (`auto-offset-reset: earliest`). That is **not** the full history: Kafka deletes messages after 7 days by default, so users registered before that are missing until a rebuild path exists (see README, "Known limitations") |
| Which fields are read? | Only `eventId`, `eventType`, `schemaVersion`, `correlationId`, and from the payload `userId`, `version`, `deactivatedAt`. **Email and display name are never read**, so no personal data enters order-service |
| Unknown `eventType` or a `schemaVersion` other than 1 | Skipped and counted (`user.events.skipped`) |

What each message does here:

| Message | Effect on the `known_user` table | Effect on orders |
|---------|----------------------------------|------------------|
| `UserRegistered` | Adds the user as `ACTIVE` (if not known yet) | The user may now place orders |
| `UserUpdated` | Only stores the newer `version`. An update for a user never seen before is **ignored** (fail closed: the message does not say whether the user is active) | None |
| `UserDeactivated` | Marks the user `DEACTIVATED` (also for a user never seen before) | **All PLACED orders of the user are cancelled** with reason `USER_DEACTIVATED`, and one `OrderCancelled` is sent per order, with `causationId` = the `UserDeactivated` `eventId`. New orders get 422 |

### Duplicates, retries, and the dead-letter channel

- **Duplicates are harmless, twice over.** Each handled `eventId` is stored in the `processed_event` table (the *inbox*) in the same database step as its effect, so a repeated message is skipped (`user.events.duplicates`). And a message whose `version` is not newer than what is stored changes nothing, even with a new `eventId` (for example after a replay).
- **Offsets are committed after the database step succeeded**, one message at a time, so a crash means "process again", never "lost".
- **Temporary failures** (for example the database is briefly down) are retried 4 times in place, waiting 0.5 s, 1 s, 2 s, 4 s (`app.messaging.consumer.*`). Retrying in place keeps the order of messages for each user.
- **Broken messages** (not JSON, missing or invalid fields) are not retried: they go straight to the dead-letter channel. So does a message that still fails after all retries.
- **Dead-letter channel:** `user.user.events.v1.order-service-dlt`, owned by order-service. Key and value are copied unchanged; Spring Kafka adds `kafka_dlt-*` headers (original topic, partition, offset, exception class). Logs and headers never contain the message body. Each dead-lettered message increments `user.events.dead.lettered`.
- Processed `eventId`s are deleted from the inbox after 30 days (`INBOX_RETENTION`). Keep this longer than the time a message can be redelivered or replayed; after that, the version check still protects the data.
