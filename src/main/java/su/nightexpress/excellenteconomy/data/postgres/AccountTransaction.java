package su.nightexpress.excellenteconomy.data.postgres;

import su.nightexpress.excellenteconomy.user.CoinsUser;
import java.util.*;

/** Buffers user-visible effects until PostgreSQL has accepted the transaction. */
public final class AccountTransaction {
    private static final ThreadLocal<AccountTransaction> CURRENT=new ThreadLocal<>();
    final PostgresAccounts owner;
    final Map<UUID,CoinsUser> accounts;
    final List<Runnable> effects=new ArrayList<>();
    final List<Runnable> failureEffects=new ArrayList<>();
    AccountTransaction(PostgresAccounts owner,Map<UUID,CoinsUser> accounts) { this.owner=owner; this.accounts=accounts; }
    static void enter(AccountTransaction tx) { CURRENT.set(tx); }
    static void leave() { CURRENT.remove(); }
    public static boolean active() { return CURRENT.get()!=null; }
    public static CoinsUser current(UUID uuid) { var tx=CURRENT.get(); return tx==null?null:tx.accounts.get(uuid); }
    public static CoinsUser named(String name) { var tx=CURRENT.get(); if(tx==null) return null; return tx.accounts.values().stream().filter(user->user.getName().equalsIgnoreCase(name)).findFirst().orElse(null); }
    public static Collection<CoinsUser> accounts() { var tx=CURRENT.get(); return tx==null?List.of():tx.accounts.values(); }
    public static boolean feedback(Runnable effect) { var tx=CURRENT.get(); if(tx==null) return false; tx.failureEffects.add(effect); return true; }
    public static boolean owns(CoinsUser user) { var tx=CURRENT.get(); return tx==null || tx.accounts.get(user.getId())==user; }
    public static boolean defer(Runnable effect) { var tx=CURRENT.get(); if(tx==null) return false; tx.effects.add(effect); return true; }
}
