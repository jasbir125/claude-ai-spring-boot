package com.example.user.service;

import com.example.user.dto.UserCreateRequest;
import com.example.user.dto.UserResponse;
import com.example.user.dto.UserUpdateRequest;
import com.example.user.exception.EmailAlreadyUsedException;
import com.example.user.exception.UserNotFoundException;
import com.example.user.exception.VersionConflictException;
import com.example.user.messaging.publisher.UserEventPublisher;
import com.example.user.model.User;
import com.example.user.model.UserStatus;
import com.example.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * User use cases. Every state change and its event are written in one transaction (outbox); nothing here talks to
 * the broker directly.
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
@Slf4j
public class UserService {

    private final UserRepository users;
    private final UserEventPublisher events;

    public Page<UserResponse> findAll(UserStatus status, Pageable pageable) {
        Page<User> page = status == null ? users.findAll(pageable) : users.findByStatus(status, pageable);
        return page.map(UserResponse::from);
    }

    public UserResponse findById(Long id) {
        return UserResponse.from(load(id));
    }

    @Transactional
    public UserResponse register(UserCreateRequest request) {
        String email = User.normalizeEmail(request.email());
        if (users.existsByEmail(email)) {
            throw new EmailAlreadyUsedException();
        }
        // flush: the unique constraint is checked here (concurrent registration) and id/version are assigned
        User user = users.saveAndFlush(User.register(email, request.displayName()));
        events.registered(user);
        log.info("User registered userId={}", user.getId());
        return UserResponse.from(user);
    }

    @Transactional
    public UserResponse update(Long id, UserUpdateRequest request) {
        User user = load(id);
        if (!user.getVersion().equals(request.version())) {
            throw new VersionConflictException(id);
        }
        String email = User.normalizeEmail(request.email());
        if (users.existsByEmailAndIdNot(email, id)) {
            throw new EmailAlreadyUsedException();
        }
        if (user.changeDetails(email, request.displayName())) {
            user = users.saveAndFlush(user);   // flush increments the version before it is published
            events.updated(user);
            log.info("User updated userId={} version={}", id, user.getVersion());
        }
        return UserResponse.from(user);
    }

    /** Idempotent: deactivating an already deactivated user changes nothing and publishes no event. */
    @Transactional
    public UserResponse deactivate(Long id) {
        User user = load(id);
        if (user.deactivate()) {
            user = users.saveAndFlush(user);
            events.deactivated(user);
            log.info("User deactivated userId={}", id);
        }
        return UserResponse.from(user);
    }

    private User load(Long id) {
        return users.findById(id).orElseThrow(() -> new UserNotFoundException(id));
    }
}
