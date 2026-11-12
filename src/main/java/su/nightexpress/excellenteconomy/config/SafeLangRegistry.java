package su.nightexpress.excellenteconomy.config;

import su.nightexpress.nightcore.NightPlugin;
import su.nightexpress.nightcore.locale.*;
import java.nio.file.*;
import java.io.*;
import java.util.*;

public final class SafeLangRegistry extends LangRegistry {
    private final NightPlugin owner;
    private final List<LangElement> entries = new ArrayList<>();
    private SafeFileConfig file;
    public SafeLangRegistry(NightPlugin plugin) { super(plugin); owner = plugin; }
    @Override public void register(Class<? extends LangContainer> type) { entries.addAll(getElements(type)); }
    @Override public boolean hasElements() { return !entries.isEmpty(); }
    @Override public void loadLocale() {
        String locale = owner.getDetails().getLanguage();
        if (!locale.matches("[a-zA-Z_]{2,12}")) throw new IllegalArgumentException("Invalid language code");
        Path dir = owner.getDataFolder().toPath().resolve("lang");
        Path path = dir.resolve(getFileName(locale));
        if (!Files.exists(path) && owner.getResource("lang/"+getFileName(locale)) == null) path=dir.resolve(getFileName("en"));
        if (!Files.exists(path)) {
            try (InputStream input=owner.getResource("lang/"+path.getFileName())) {
                if (input!=null) { Files.createDirectories(dir); Files.copy(input,path); }
            } catch (IOException e) { throw new UncheckedIOException(e); }
        }
        file = SafeFileConfig.load(path);
        loadEntries(entries,owner,file);
    }
    @Override public void inject(Class<? extends LangContainer> type) { if(file!=null) loadEntries(type,owner,file); }
    @Override public void inject(LangContainer container) { inject(container.getClass()); }
    @Override public void complete() { if(file!=null) { file.saveChanges(); file=null; } }
    @Override protected void onShutdown() { entries.clear(); file=null; }
}
