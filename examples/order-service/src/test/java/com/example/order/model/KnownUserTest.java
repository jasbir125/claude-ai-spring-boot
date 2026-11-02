package com.example.order.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class KnownUserTest {

    @Test
    void registered_isActive() {
        KnownUser user = KnownUser.registered(42L, 0L);

        assertThat(user.isActive()).isTrue();
        assertThat(user.getUserVersion()).isZero();
    }

    @Test
    void applyUpdate_onlyWhenNewer() {
        KnownUser user = KnownUser.registered(42L, 3L);

        assertThat(user.applyUpdate(3L)).isFalse();
        assertThat(user.applyUpdate(2L)).isFalse();
        assertThat(user.applyUpdate(4L)).isTrue();
        assertThat(user.getUserVersion()).isEqualTo(4L);
        assertThat(user.isActive()).isTrue();
    }

    @Test
    void applyDeactivation_onlyWhenNewer_andIsFinal() {
        KnownUser user = KnownUser.registered(42L, 1L);

        assertThat(user.applyDeactivation(1L)).isFalse();
        assertThat(user.isActive()).isTrue();

        assertThat(user.applyDeactivation(2L)).isTrue();
        assertThat(user.getStatus()).isEqualTo(KnownUserStatus.DEACTIVATED);

        assertThat(user.applyUpdate(3L)).isTrue();   // a later update does not reactivate
        assertThat(user.isActive()).isFalse();
    }

    @Test
    void deactivated_forUnknownUser_isNotActive() {
        assertThat(KnownUser.deactivated(7L, 2L).isActive()).isFalse();
    }
}
