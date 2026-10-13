package su.nightexpress.excellenteconomy.hook;

public final class VaultHooks {

    private VaultHooks() {}

    public static boolean hasAPI(String name) {
        try {
            Class.forName(name, false, VaultHooks.class.getClassLoader());
            return true;
        }
        catch (ClassNotFoundException | LinkageError exception) {
            return false;
        }
    }
}
