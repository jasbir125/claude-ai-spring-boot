package com.example.user.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/** User aggregate. The service is the system of record for user accounts. */
@Entity
@Table(name = "users")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)   // for JPA
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "email", nullable = false, length = 254)
    private String email;

    @Column(name = "display_name", nullable = false, length = 100)
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private UserStatus status = UserStatus.ACTIVE;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    private User(String email, String displayName) {
        this.email = normalizeEmail(email);
        this.displayName = normalizeDisplayName(displayName);
    }

    public static User register(String email, String displayName) {
        return new User(email, displayName);
    }

    /** Emails are compared case-insensitively; they are stored trimmed and lower case. */
    public static String normalizeEmail(String email) {
        return Objects.requireNonNull(email, "email").strip().toLowerCase(Locale.ROOT);
    }

    private static String normalizeDisplayName(String displayName) {
        return Objects.requireNonNull(displayName, "displayName").strip();
    }

    /** @return {@code true} when a field actually changed */
    public boolean changeDetails(String newEmail, String newDisplayName) {
        String normalizedEmail = normalizeEmail(newEmail);
        String normalizedName = normalizeDisplayName(newDisplayName);
        boolean changed = !normalizedEmail.equals(email) || !normalizedName.equals(displayName);
        this.email = normalizedEmail;
        this.displayName = normalizedName;
        return changed;
    }

    /** Deactivation is idempotent: @return {@code true} only on the transition ACTIVE → DEACTIVATED. */
    public boolean deactivate() {
        if (status == UserStatus.DEACTIVATED) {
            return false;
        }
        status = UserStatus.DEACTIVATED;
        return true;
    }
}
