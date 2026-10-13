package su.nightexpress.excellenteconomy.currency;

import org.junit.jupiter.api.Test;

import java.net.URLClassLoader;

import static org.junit.jupiter.api.Assertions.*;

class OptionalVaultTest {

    @Test
    void currencyFactoryCanBeVerifiedWhenVaultIsAbsent() throws Exception {
        var classes = CurrencyFactory.class.getProtectionDomain().getCodeSource().getLocation();
        try (URLClassLoader loader = new URLClassLoader(new java.net.URL[]{classes}, getClass().getClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.startsWith("net.milkbowl.vault")) throw new ClassNotFoundException(name);
                if (name.startsWith("su.nightexpress.excellenteconomy.currency.")) {
                    synchronized (getClassLoadingLock(name)) {
                        Class<?> loaded = findLoadedClass(name);
                        if (loaded == null) loaded = findClass(name);
                        if (resolve) resolveClass(loaded);
                        return loaded;
                    }
                }
                return super.loadClass(name, resolve);
            }
        }) {
            assertDoesNotThrow(() -> loader.loadClass(CurrencyFactory.class.getName()).getDeclaredMethods());
        }
    }
}
