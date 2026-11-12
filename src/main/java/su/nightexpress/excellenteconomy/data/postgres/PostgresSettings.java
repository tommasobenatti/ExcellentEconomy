package su.nightexpress.excellenteconomy.data.postgres;

import su.nightexpress.nightcore.config.FileConfig;
import java.util.*;

public record PostgresSettings(String host, int port, String database, String username, String password,
                               String sslMode, String tablePrefix, int maximumPoolSize, int connectionTimeoutMs,
                               int queryTimeoutSeconds, int batchSize) {
    public PostgresSettings {
        identifier(tablePrefix + "_users");
        identifier(tablePrefix + "_name_idx");
        if (host.isBlank() || database.isBlank() || username.isBlank() || port<1 || port>65535
            || maximumPoolSize<1 || maximumPoolSize>16 || connectionTimeoutMs<250 || connectionTimeoutMs>30000
            || queryTimeoutSeconds<1 || queryTimeoutSeconds>60 || batchSize<1 || batchSize>256
            || !Set.of("disable","allow","prefer","require","verify-ca","verify-full").contains(sslMode))
            throw new IllegalArgumentException("Invalid PostgreSQL settings; no storage fallback will be used");
    }
    public static String identifier(String name) {
        if (!name.matches("[A-Za-z_][A-Za-z0-9_]{0,62}")) throw new IllegalArgumentException("Invalid PostgreSQL identifier");
        return "\""+name+"\"";
    }
    public static void addDefaults(FileConfig c) {
        Map<String,Object> defaults = new LinkedHashMap<>();
        defaults.put("Host","localhost"); defaults.put("Port",5432); defaults.put("Database","minecraft");
        defaults.put("Username","minecraft"); defaults.put("Password",""); defaults.put("SSL_Mode","verify-full");
        defaults.put("Pool.Maximum_Size",4); defaults.put("Pool.Connection_Timeout_Ms",3000);
        defaults.put("Balance_Refresh_Interval",1); defaults.put("Query_Timeout_Seconds",5); defaults.put("Batch_Size",64);
        defaults.forEach((key,value)->{ if(!c.contains("Database.PostgreSQL."+key)) c.set("Database.PostgreSQL."+key,value); });
    }
    public static PostgresSettings read(FileConfig c) {
        addDefaults(c);
        String p="Database.PostgreSQL.";
        return new PostgresSettings(c.getString(p+"Host","localhost"),c.getInt(p+"Port",5432),c.getString(p+"Database","minecraft"),
            c.getString(p+"Username","minecraft"),c.getString(p+"Password",""),c.getString(p+"SSL_Mode","verify-full"),
            c.getString("Database.Table_Prefix","excellenteconomy"),c.getInt(p+"Pool.Maximum_Size",4),
            c.getInt(p+"Pool.Connection_Timeout_Ms",3000),c.getInt(p+"Query_Timeout_Seconds",5),c.getInt(p+"Batch_Size",64));
    }
    @Override public String toString() { return "PostgresSettings[host="+host+", database="+database+", password=<redacted>]"; }
}
