package com.example.order.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * Read model of a user owned by user-service, built only from its events. {@code userVersion} is the user's version
 * in user-service: an event is applied only when it is newer, so duplicates and out-of-order events change nothing.
 * No personal data is kept.
 */
@Entity
@Table(name = "known_user")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)   // for JPA
public class KnownUser {

    @Id
    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private KnownUserStatus status;

    @Column(name = "user_version", nullable = false)
    private long userVersion;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    private KnownUser(long userId, KnownUserStatus status, long userVersion) {
        this.userId = userId;
        this.status = status;
        this.userVersion = userVersion;
    }

    public static KnownUser registered(long userId, long userVersion) {
        return new KnownUser(userId, KnownUserStatus.ACTIVE, userVersion);
    }

    /** A deactivation for a user we never saw registered (e.g. history no longer on the topic). */
    public static KnownUser deactivated(long userId, long userVersion) {
        return new KnownUser(userId, KnownUserStatus.DEACTIVATED, userVersion);
    }

    public boolean isActive() {
        return status == KnownUserStatus.ACTIVE;
    }

    /**
     * Applies a {@code UserUpdated}: only the version matters here (order-service keeps no name or email).
     *
     * @return {@code true} when the event was newer than the known state
     */
    public boolean applyUpdate(long newVersion) {
        if (newVersion <= userVersion) {
            return false;
        }
        userVersion = newVersion;
        return true;
    }

    /**
     * Applies a {@code UserDeactivated}. user-service has no reactivation, so DEACTIVATED is final.
     *
     * @return {@code true} when the event was newer than the known state
     */
    public boolean applyDeactivation(long newVersion) {
        if (newVersion <= userVersion) {
            return false;
        }
        userVersion = newVersion;
        status = KnownUserStatus.DEACTIVATED;
        return true;
    }
}
