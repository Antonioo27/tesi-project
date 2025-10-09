package com.example.focal;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class UserServiceIT {
    @Test
    void register_end_to_end_real_repo() {
        UserService us = new UserService(new SimpleUserRepository());
        assertTrue(us.register("a@b.com")); // save reale
        assertFalse(us.register("a@b.com")); // exists reale
    }
}
