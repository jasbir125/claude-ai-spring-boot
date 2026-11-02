package com.example.order.messaging.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class StringOrBytesSerializerTest {

    private final StringOrBytesSerializer serializer = new StringOrBytesSerializer();

    @Test
    void serializesStringsAsUtf8_andPassesBytesThrough() {
        assertThat(serializer.serialize("t", "grüß")).isEqualTo("grüß".getBytes(StandardCharsets.UTF_8));
        assertThat(serializer.serialize("t", new byte[] {1, 2})).containsExactly(1, 2);
        assertThat(serializer.serialize("t", null)).isNull();
    }

    @Test
    void rejectsOtherTypes() {
        assertThatThrownBy(() -> serializer.serialize("t", 42)).isInstanceOf(IllegalArgumentException.class);
    }
}
