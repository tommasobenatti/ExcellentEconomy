package su.nightexpress.excellenteconomy.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import su.nightexpress.nightcore.config.FileConfig;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;

/** Validates before loading, preserves administrator collections/comments, and publishes atomically. */
public final class SafeFileConfig extends FileConfig {
    private static final Set<String> COLLECTIONS = Set.of("General.Amount_Shortcuts", "Exchange.Rates", "Transfer_Tax.Brackets", "Transfer_Tax.Permission_Tiers", "Buttons", "Content", "Items");
    private byte[] expected;
    private boolean defaultsUpgrade;
    private YamlConfiguration original;

    private SafeFileConfig(Path path, byte[] bytes, boolean upgrade) {
        super(path.toFile());
        expected = bytes;
        defaultsUpgrade = upgrade;
        original = parse(new String(bytes, StandardCharsets.UTF_8));
        try { loadFromString(new String(bytes, StandardCharsets.UTF_8)); }
        catch (InvalidConfigurationException e) { throw new IllegalArgumentException("Invalid YAML: " + path.getFileName(), e); }
    }

    public static SafeFileConfig load(Path path) { return load(path,true); }

    public static SafeFileConfig load(Path path,boolean upgrade) {
        try {
            path = Files.exists(path) ? path.toRealPath() : path.toAbsolutePath();
            byte[] bytes = Files.exists(path) ? Files.readAllBytes(path) : new byte[0];
            parse(new String(bytes, StandardCharsets.UTF_8));
            return new SafeFileConfig(path, bytes, upgrade);
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }

    private static YamlConfiguration parse(String text) {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        try {
            Object root = new Yaml(new SafeConstructor(options)).load(text);
            if (root != null && !(root instanceof Map)) throw new IllegalArgumentException("YAML root must be a mapping");
            YamlConfiguration config = new YamlConfiguration();
            config.options().parseComments(true);
            config.loadFromString(text);
            return config;
        } catch (RuntimeException | InvalidConfigurationException e) { throw new IllegalArgumentException("Invalid YAML; original file was not changed", e); }
    }

    private static Object plain(Object value) {
        if (!(value instanceof ConfigurationSection section)) return value;
        Map<String,Object> map = new LinkedHashMap<>();
        section.getKeys(false).forEach(key -> map.put(key, plain(section.get(key))));
        return map;
    }

    @Override public void save() {
        Path path = getPath();
        if(defaultsUpgrade) for (String key : COLLECTIONS) if (original.contains(key) && contains(key)) set(key, plain(original.get(key)));
        for (String key : original.getKeys(true)) if (contains(key)) {
            setComments(key, original.getComments(key));
            setInlineComments(key, original.getInlineComments(key));
        }
        options().setHeader(original.options().getHeader());
        if (Objects.equals(plain(original), plain(this))) {
            checkUnchanged(path);
            defaultsUpgrade=false;
            return;
        }
        String updated = saveToString();
        YamlConfiguration verified = parse(updated);
        checkUnchanged(path);
        try {
            Files.createDirectories(path.getParent());
            if (expected.length > 0) {
                Path backups = path.getParent().resolve(".config-backups");
                if (Files.isSymbolicLink(backups)) throw new IOException("Unsafe backup directory");
                Files.createDirectories(backups);
                permissions(backups, "rwx------");
                Path backup = Files.createTempFile(backups, path.getFileName()+"-", ".bak");
                permissions(backup, "rw-------");
                Files.write(backup, expected);
            }
            Path temporary = Files.createTempFile(path.getParent(), ".economy-", ".tmp");
            try {
                permissions(temporary, "rw-------");
                try (var channel = java.nio.channels.FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                    var buffer=StandardCharsets.UTF_8.encode(updated);
                    while(buffer.hasRemaining()) channel.write(buffer);
                    channel.force(true);
                }
                if (Files.exists(path)) {
                    try { Files.setPosixFilePermissions(temporary, Files.getPosixFilePermissions(path)); }
                    catch (UnsupportedOperationException ignored) { }
                }
                checkUnchanged(path);
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally { Files.deleteIfExists(temporary); }
            expected = updated.getBytes(StandardCharsets.UTF_8);
            original = verified;
            defaultsUpgrade=false;
        } catch (IOException e) { throw new UncheckedIOException("Could not safely save " + path.getFileName(), e); }
    }

    private void checkUnchanged(Path path) {
        try {
            if (!Arrays.equals(expected, Files.exists(path) ? Files.readAllBytes(path) : new byte[0]))
                throw new IllegalStateException("Configuration changed during update; refusing to overwrite " + path.getFileName());
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }

    private static void permissions(Path path, String mode) throws IOException {
        try { Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(mode)); }
        catch (UnsupportedOperationException ignored) { }
    }
}
