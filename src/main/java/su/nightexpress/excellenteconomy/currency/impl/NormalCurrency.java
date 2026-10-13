package su.nightexpress.excellenteconomy.currency.impl;

import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;

public class NormalCurrency extends AbstractCurrency {

    private final boolean primary;

    public NormalCurrency(@NotNull Path path, @NotNull String id) {
        this(path, id, false);
    }

    public NormalCurrency(@NotNull Path path, @NotNull String id, boolean primary) {
        super(path, id);
        this.primary = primary;
    }

    @Override
    public void onRegister() {

    }

    @Override
    public void onUnregister() {

    }

    @Override
    public boolean isPrimary() {
        return this.primary;
    }
}
