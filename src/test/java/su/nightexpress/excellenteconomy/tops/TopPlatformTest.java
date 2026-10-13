package su.nightexpress.excellenteconomy.tops;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import su.nightexpress.excellenteconomy.config.Config;
import su.nightexpress.nightcore.util.Version;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mockStatic;

class TopPlatformTest {

    @Test
    void foliaUsesTextUntilNightcoreMenusAreEntitySafe() {
        Config.TOPS_USE_GUI.set(true);
        try (MockedStatic<Version> platform = mockStatic(Version.class)) {
            platform.when(Version::isFolia).thenReturn(false);
            assertTrue(Config.isTopMenuEnabled());
            platform.when(Version::isFolia).thenReturn(true);
            assertFalse(Config.isTopMenuEnabled());
        }
    }
}
