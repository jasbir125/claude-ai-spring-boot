# user-service messages (events)

## What is this page?

When something happens to a user, user-service publishes a short **message** (an *event*) so other services can react. A shop's order service, for example, might stop accepting orders from a deactivated user.

This page is the **public contract** for those messages: what they look like, when they are sent, and the promises other teams can rely on.

user-service is the **only** service that sends these messages. It saves each message in its database (the `outbox_event` table) together with the change itself, and a background worker (`OutboxRelay`) sends it to Kafka afterwards. That way a message is never lost and never sent for a change that failed ([how the outbox works](../../../skills/messaging-patterns/references/outbox.md)).

## The channel: `user.user.events.v1`

| Question | Answer |
|----------|--------|
| Who sends messages here? | Only `user-service` |
| What is the message key? | The user's ID. All messages about one user go to the same lane, so they arrive **in order** |
| How many lanes (partitions)? | 3 locally (`USER_EVENTS_PARTITIONS`). In shared environments the platform team creates the channel (`KAFKA_CREATE_TOPICS=false`) |
| What does a message look like? | A JSON "envelope" (below), sent as text |
| Extra labels (headers) | `eventId`, `eventType`, `schemaVersion`, so you can filter without reading the body |
| Can a message arrive twice? | **Yes, occasionally.** Remember the `eventId` values you have handled and skip repeats |
| Can messages arrive out of date? | Every message carries the user's `version` number. If you get one with a version you have already seen or passed, ignore it |
| Who listens today? | [order-service](../../order-service/docs/events.md): it keeps a list of known users and cancels a user's open orders on `UserDeactivated`. Add your service here |

## The envelope: the part every message shares

Think of it as the envelope around a letter: the same outside for every message, with the actual content (`payload`) inside.

| Field | Type | What it means |
|-------|------|---------------|
| `eventId` | UUID | Unique ID of this message. Use it to spot duplicates |
| `eventType` | text | What happened: `UserRegistered`, `UserUpdated`, or `UserDeactivated`. Ignore types you don't know (new ones may be added) |
| `schemaVersion` | number | Version of the content layout. It only changes when the layout changes in a way that breaks old readers |
| `occurredAt` | time (UTC) | When it happened |
| `source` | text | Always `user-service` |
| `aggregateId` | text | The user's ID (the same as the message key) |
| `correlationId` | text | A tracking number that follows one user action across all services and logs |
| `causationId` | text or empty | The message that triggered this one, if any; empty when a person triggered it through the API |
| `payload` | object | The details, described per message below |

Note: the order of the fields in the JSON is not fixed. Read fields by name.

## The messages

### `UserRegistered` (version 1)

**Sent when** a new user is created (`POST /api/v1/users`).

| Field | Type | What it means |
|-------|------|---------------|
| `userId` | number | The new user's ID |
| `email` | text | Email in lower case. This is personal data: only use it if you need it, and never write it to logs |
| `displayName` | text | The name to show |
| `registeredAt` | time (UTC) | When the account was created |
| `version` | number | Always `0` for a new user |

Example: [`user-registered-v1.json`](../src/test/resources/contracts/events/user-registered-v1.json)

### `UserUpdated` (version 1)

**Sent when** the email or display name really changes (`PUT /api/v1/users/{id}`). Saving the same values again sends nothing. The message always carries the **complete new state**, so you never have to ask user-service for it.

| Field | Type | What it means |
|-------|------|---------------|
| `userId` | number | The user's ID |
| `email` | text | The new email |
| `displayName` | text | The new display name |
| `version` | number | The user's version after the change |

Example: [`user-updated-v1.json`](../src/test/resources/contracts/events/user-updated-v1.json)

### `UserDeactivated` (version 1)

**Sent when** a user is deactivated for the first time (`POST /api/v1/users/{id}/deactivate`). Deactivating again sends nothing. This message contains **no personal data**.

| Field | Type | What it means |
|-------|------|---------------|
| `userId` | number | The user's ID |
| `deactivatedAt` | time (UTC) | When it happened |
| `version` | number | The user's version after the change |

Example: [`user-deactivated-v1.json`](../src/test/resources/contracts/events/user-deactivated-v1.json)

## Promises about future changes

- An automatic test (`EventContractTest`) compares every message with its example file. Any accidental change fails the build.
- **Adding** a new optional field is allowed at any time. Your code must ignore fields it doesn't know.
- **Removing, renaming, or changing the meaning** of a field is a breaking change. It gets `schemaVersion` 2, both versions are published side by side while consumers switch over, and the plan is written down in a decision record (ADR). See the [event contract rules](../../../skills/messaging-patterns/references/event-contracts.md).
