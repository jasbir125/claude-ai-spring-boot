package com.example.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

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
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private UserRepository users;

    @Mock
    private UserEventPublisher events;

    @InjectMocks
    private UserService service;

    @Test
    void register_normalizesEmail_savesAndPublishesEvent() {
        when(users.existsByEmail("ada@example.com")).thenReturn(false);
        when(users.saveAndFlush(any(User.class))).thenAnswer(inv -> persisted(inv.getArgument(0), 1L, 0L));

        UserResponse response = service.register(new UserCreateRequest(" Ada@Example.COM ", " Ada "));

        assertThat(response.id()).isEqualTo(1L);
        assertThat(response.email()).isEqualTo("ada@example.com");
        assertThat(response.displayName()).isEqualTo("Ada");
        assertThat(response.status()).isEqualTo(UserStatus.ACTIVE);
        verify(events).registered(any(User.class));
    }

    @Test
    void register_duplicateEmail_throws_andWritesNothing() {
        when(users.existsByEmail("ada@example.com")).thenReturn(true);

        assertThatThrownBy(() -> service.register(new UserCreateRequest("ADA@example.com", "Ada")))
                .isInstanceOf(EmailAlreadyUsedException.class);

        verify(users, never()).saveAndFlush(any());
        verifyNoInteractions(events);
    }

    @Test
    void findById_unknown_throwsNotFound() {
        when(users.findById(42L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findById(42L))
                .isInstanceOf(UserNotFoundException.class)
                .hasMessage("User 42 not found");
    }

    @Test
    void findAll_withAndWithoutStatusFilter() {
        Pageable pageable = PageRequest.of(0, 10);
        User user = persisted(User.register("a@example.com", "A"), 1L, 0L);
        when(users.findAll(pageable)).thenReturn(new PageImpl<>(List.of(user), pageable, 1));
        when(users.findByStatus(UserStatus.DEACTIVATED, pageable)).thenReturn(new PageImpl<>(List.of(), pageable, 0));

        assertThat(service.findAll(null, pageable).getContent()).extracting(UserResponse::id).containsExactly(1L);
        assertThat(service.findAll(UserStatus.DEACTIVATED, pageable).getContent()).isEmpty();
    }

    @Test
    void update_changedDetails_savesAndPublishesUpdated() {
        User user = persisted(User.register("old@example.com", "Old"), 7L, 3L);
        when(users.findById(7L)).thenReturn(Optional.of(user));
        when(users.existsByEmailAndIdNot("new@example.com", 7L)).thenReturn(false);
        when(users.saveAndFlush(user)).thenReturn(user);

        UserResponse response = service.update(7L, new UserUpdateRequest("New@example.com", "New", 3L));

        assertThat(response.email()).isEqualTo("new@example.com");
        assertThat(response.displayName()).isEqualTo("New");
        verify(events).updated(user);
    }

    @Test
    void update_withoutChanges_publishesNothing() {
        User user = persisted(User.register("same@example.com", "Same"), 7L, 3L);
        when(users.findById(7L)).thenReturn(Optional.of(user));

        service.update(7L, new UserUpdateRequest("SAME@example.com", " Same ", 3L));

        verify(users, never()).saveAndFlush(any());
        verifyNoInteractions(events);
    }

    @Test
    void update_staleVersion_throwsConflict_andPublishesNothing() {
        User user = persisted(User.register("a@example.com", "A"), 7L, 3L);
        when(users.findById(7L)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> service.update(7L, new UserUpdateRequest("a@example.com", "B", 2L)))
                .isInstanceOf(VersionConflictException.class);

        verifyNoInteractions(events);
    }

    @Test
    void update_emailOwnedByAnotherUser_throwsConflict() {
        User user = persisted(User.register("a@example.com", "A"), 7L, 0L);
        when(users.findById(7L)).thenReturn(Optional.of(user));
        when(users.existsByEmailAndIdNot("taken@example.com", 7L)).thenReturn(true);

        assertThatThrownBy(() -> service.update(7L, new UserUpdateRequest("taken@example.com", "A", 0L)))
                .isInstanceOf(EmailAlreadyUsedException.class);

        verifyNoInteractions(events);
    }

    @Test
    void update_unknownUser_throwsNotFound() {
        when(users.findById(9L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(9L, new UserUpdateRequest("a@example.com", "A", 0L)))
                .isInstanceOf(UserNotFoundException.class);
    }

    @Test
    void deactivate_isIdempotent_publishesOnlyOnce() {
        User user = persisted(User.register("a@example.com", "A"), 7L, 0L);
        when(users.findById(7L)).thenReturn(Optional.of(user));
        when(users.saveAndFlush(user)).thenReturn(user);

        assertThat(service.deactivate(7L).status()).isEqualTo(UserStatus.DEACTIVATED);
        assertThat(service.deactivate(7L).status()).isEqualTo(UserStatus.DEACTIVATED);

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(events, times(1)).deactivated(captor.capture());
        assertThat(captor.getValue().getId()).isEqualTo(7L);
    }

    private static User persisted(User user, Long id, Long version) {
        ReflectionTestUtils.setField(user, "id", id);
        ReflectionTestUtils.setField(user, "version", version);
        return user;
    }
}
