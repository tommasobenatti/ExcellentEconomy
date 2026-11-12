package su.nightexpress.excellenteconomy.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import su.nightexpress.excellenteconomy.TestServer;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class SafeFileConfigTest {
    @TempDir Path directory;

    @Test void persistsMissingKeysAndPreservesCommentsCollectionsAndCustomValues() throws Exception {
        TestServer.initialize();
        Path path = directory.resolve("engine.yml");
        Files.writeString(path, "# my connection\nDatabase:\n  Type: MYSQL # keep backend\n  MySQL:\n    Password: 'private-test-value'\nCustom: []\nExchange:\n  Rates: {}\nTransfer_Tax:\n  Permission_Tiers: {}\n");
        var config = SafeFileConfig.load(path);
        config.setComments("Database", java.util.List.of("new default comment"));
        config.set("Database.PostgreSQL.Port", 5432);
        config.set("Exchange.Rates.example", 2);
        config.set("Transfer_Tax.Permission_Tiers.default.Permission", "example");
        config.saveChanges();
        String saved = Files.readString(path);
        assertTrue(saved.contains("# my connection"));
        assertTrue(saved.contains("keep backend"));
        var again = SafeFileConfig.load(path);
        assertEquals("MYSQL", again.getString("Database.Type"));
        assertEquals(5432, again.getInt("Database.PostgreSQL.Port"));
        assertEquals("private-test-value", again.getString("Database.MySQL.Password"));
        assertTrue(again.getList("Custom").isEmpty());
        assertTrue(again.getSection("Exchange.Rates").isEmpty());
        assertTrue(again.getSection("Transfer_Tax.Permission_Tiers").isEmpty());
        again.save();
        assertEquals(saved, Files.readString(path));
        assertTrue(Files.list(directory.resolve(".config-backups")).findAny().isPresent());
    }

    @Test void invalidOrConcurrentlyEditedYamlIsNeverOverwritten() throws Exception {
        TestServer.initialize();
        Path path = directory.resolve("config.yml");
        for (String invalid : java.util.List.of("broken: [", "same: 1\nsame: 2\n", "- scalar-root\n")) {
            Files.writeString(path, invalid);
            assertThrows(IllegalArgumentException.class, () -> SafeFileConfig.load(path));
            assertEquals(invalid, Files.readString(path));
        }
        Files.writeString(path, "Existing: custom\n");
        var config = SafeFileConfig.load(path);
        config.set("New", true);
        Files.writeString(path, "Existing: edited-during-reload\n");
        assertThrows(IllegalStateException.class, config::saveChanges);
        assertEquals("Existing: edited-during-reload\n", Files.readString(path));
    }
}
