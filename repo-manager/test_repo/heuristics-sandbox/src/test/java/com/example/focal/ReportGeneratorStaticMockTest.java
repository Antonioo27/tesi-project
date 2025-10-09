package com.example.focal;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.mockStatic;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

class ReportGeneratorStaticMockTest {
    @Test
    void static_banner_is_mocked_and_verified() {
        try (MockedStatic<StaticUtil> ms = mockStatic(StaticUtil.class)) {
            ms.when(() -> StaticUtil.banner("ok")).thenReturn("[MOCK]");
            ReportGenerator rg = new ReportGenerator(new EmailSender());
            boolean ok = rg.sendReport("a@b.com", "ok");
            assertTrue(ok);
            ms.verify(() -> StaticUtil.banner("ok"), times(1));
        }
    }
}
