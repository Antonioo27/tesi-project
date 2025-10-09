package com.example.focal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class SpyListTest {
    @Test
    void spy_on_list_with_doReturn() {
        List<String> real = new ArrayList<>();
        List<String> spy = spy(real);
        doReturn(10).when(spy).size(); // stubbing con doReturn
        assertEquals(10, spy.size());
        verify(spy, times(1)).size(); // verify
    }
}
