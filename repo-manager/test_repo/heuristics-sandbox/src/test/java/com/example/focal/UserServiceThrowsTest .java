package com.example.focal;

import static org.junit.jupiter.api.Assertions.assertThrows;
import org.junit.jupiter.api.Test;

class UserServiceThrowsTest {
    @Test
    void null_email_throws() {
        UserService us = new UserService(new SimpleUserRepository());
        assertThrows(IllegalArgumentException.class, () -> us.register(null)); // producer in lambda
    }
}
