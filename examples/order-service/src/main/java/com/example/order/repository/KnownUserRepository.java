package com.example.order.repository;

import com.example.order.model.KnownUser;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * The two locking reads make "place order" and "user deactivated → cancel open orders" safe against each other:
 * placing takes a shared row lock (many orders in parallel), the projection takes an exclusive one. Whichever
 * transaction comes second waits and then sees the other's committed result.
 */
public interface KnownUserRepository extends JpaRepository<KnownUser, Long> {

    /** {@code SELECT ... FOR SHARE}: the user cannot be deactivated until the order transaction ends. */
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select u from KnownUser u where u.userId = :userId")
    Optional<KnownUser> findForOrderPlacement(@Param("userId") Long userId);

    /** Exclusive row lock: waits for running order placements of this user to commit. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from KnownUser u where u.userId = :userId")
    Optional<KnownUser> findForUpdate(@Param("userId") Long userId);
}
