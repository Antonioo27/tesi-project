package com.example.focal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class TestEmailSender {
    @Test
    void format_is_used_directly_in_assert() {
        EmailSender es = new EmailSender();
        assertEquals("Hi Bob", es.format("Bob")); // DIRECT producer su EmailSender.format
    }

    @Test
    void format_assigned_then_assert_variable() {
        EmailSender es = new EmailSender();
        var res = es.format("Alice"); // VARIABLE_PRODUCER: EmailSender.format
        assertThat(res).startsWith("Hi "); // l'euristica risale al producer della var 'res'
    }
}
