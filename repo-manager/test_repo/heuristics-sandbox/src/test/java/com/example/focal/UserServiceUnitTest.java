package com.example.focal;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class UserServiceUnitTest {
    @Mock
    UserRepository repo;

    @Test
    void register_ok_stubs_and_verifies() {
        UserService us = new UserService(repo);
        when(repo.exists("a@b.com")).thenReturn(false); // stubbing
        boolean ok = us.register("a@b.com");
        assertTrue(ok);
        verify(repo).save("a@b.com"); // verify (metodo verificato)
    }
}
