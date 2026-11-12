package su.nightexpress.excellenteconomy.user;

import org.jspecify.annotations.NonNull;

import su.nightexpress.excellenteconomy.EconomyPlugin;
import su.nightexpress.excellenteconomy.api.currency.ExcellentCurrency;
import su.nightexpress.excellenteconomy.currency.CurrencyRegistry;
import su.nightexpress.excellenteconomy.data.DataHandler;
import su.nightexpress.excellenteconomy.data.DataColumns;
import su.nightexpress.excellenteconomy.user.data.CurrencySettings;
import su.nightexpress.nightcore.user.AbstractUserManager;
import su.nightexpress.nightcore.user.data.DefaultUserDataAccessor;
import su.nightexpress.nightcore.user.cache.CachedUser;
import su.nightexpress.nightcore.user.listener.UserListener;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

import java.net.InetAddress;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.bukkit.command.CommandSender;
import java.util.function.Consumer;
import su.nightexpress.excellenteconomy.config.Lang;

public class UserManager extends AbstractUserManager<EconomyPlugin, CoinsUser> {

    private final CurrencyRegistry registry;
    private final DataHandler storage;
    private final ConcurrentMap<UUID, CoinsUser> dirtyUsers = new ConcurrentHashMap<>();
    private final ReentrantLock writer = new ReentrantLock();
    private final ThreadPoolExecutor lookups;
    private volatile boolean stopping;
    private long lastSaveWarning;
    private final Set<CompletableFuture<?>> pendingReads=ConcurrentHashMap.newKeySet();
    private volatile long storageEpoch;
    private final ConcurrentMap<String, CompletableFuture<Optional<CoinsUser>>> inFlight = new ConcurrentHashMap<>();


    public UserManager(@NonNull EconomyPlugin plugin, @NonNull CurrencyRegistry registry,
                       @NonNull DataHandler dataHandler) {
        super(plugin, dataHandler == null ? new DefaultUserDataAccessor<>(null, null) : dataHandler.getUserDataAccessor());
        this.registry = registry;
        this.storage = dataHandler;
        if(dataHandler!=null) dataHandler.onCommitted(this::applyCommitted);
        AtomicInteger sequence = new AtomicInteger();
        this.lookups = new ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(128), runnable -> {
            Thread thread = new Thread(runnable, "ExcellentEconomy-lookup-" + sequence.incrementAndGet());
            thread.setDaemon(true); return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
        this.lookups.allowCoreThreadTimeOut(true);
    }

    private final class Work<T> implements Runnable {
        final CompletableFuture<T> future;
        final java.util.function.Supplier<T> operation;
        Work(CompletableFuture<T> future,java.util.function.Supplier<T> operation) { this.future=future; this.operation=operation; }
        @Override public void run() {
            if(future.isDone()) return;
            try { future.complete(operation.get()); } catch(RuntimeException e) { future.completeExceptionally(e); }
        }
        void discard() { future.completeExceptionally(new RejectedExecutionException("Economy is closing")); }
    }
    public <T> CompletableFuture<T> workAsync(java.util.function.Supplier<T> task) {
        var result=new CompletableFuture<T>();
        if(stopping || Thread.holdsLock(UserBalance.class) || su.nightexpress.excellenteconomy.data.postgres.AccountTransaction.active())
            return CompletableFuture.failedFuture(new RejectedExecutionException("Economy is closing or a balance event cannot start nested asynchronous I/O"));
        pendingReads.add(result);
        result.whenComplete((value,error)->pendingReads.remove(result));
        try {
            lookups.execute(new Work<>(result,task));
        } catch(RejectedExecutionException e) { result.completeExceptionally(e); }
        return result;
    }

    @Override protected void onLoad() {
        this.settings.load(this.plugin.getEngineConfig());
        this.addAsyncTask(this::saveDirty, Math.max(1L, this.settings.getSaveInterval()));
        if (this.settings.getCacheCleanupInterval() > 0) this.addAsyncTask(this::cleanCache, this.settings.getCacheCleanupInterval());
        this.plugin.onPostLoad(() -> {
            this.addListener(new UserListener<EconomyPlugin, CoinsUser>(this.plugin, this) {
                @Override @EventHandler(priority=EventPriority.MONITOR)
                public void onUserLogin(AsyncPlayerPreLoginEvent event) { prepareLogin(event); }
                @Override @EventHandler(priority=EventPriority.LOWEST)
                public void onUserJoin(PlayerJoinEvent event) { join(event.getPlayer()); }
                @Override @EventHandler(priority=EventPriority.MONITOR)
                public void onUserQuit(PlayerQuitEvent event) { quit(event.getPlayer()); }
            });
            this.dataAccessor.addSynchronization(fetched -> {
                if(storage!=null && storage.isPostgres()) { refreshFromStore(fetched); return; }
                var cached=this.repository.getById(fetched.getId());
                cached.ifPresent(user -> this.synchronize(fetched,user));
            });
            Bukkit.getOnlinePlayers().forEach(this::join);
        });
    }

    private void prepareLogin(AsyncPlayerPreLoginEvent event) {
        if(event.getLoginResult()!=AsyncPlayerPreLoginEvent.Result.ALLOWED) return;
        try {
            writer.lock();
            try {
                var loaded=storage!=null && storage.isPostgres() ? dataAccessor.loadById(event.getUniqueId()) : findById(event.getUniqueId());
                if(loaded.isEmpty()) {
                    var created=create(event.getUniqueId(),event.getName());
                    dataAccessor.insert(created);
                    var stored=dataAccessor.loadById(event.getUniqueId()).orElseThrow();
                    cacheTemporary(stored);
                } else {
                    refreshFromStore(loaded.get());
                    if(!repository.contains(loaded.get().getId())) cacheTemporary(loaded.get());
                }
            } finally { writer.unlock(); }
        } catch(RuntimeException e) {
            plugin.error("Economy login data unavailable; connection refused to protect account data.");
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,"Economy data is unavailable. Please try again shortly.");
        }
    }

    private void join(org.bukkit.entity.Player player) {
        findByIdAsync(player.getUniqueId()).thenAccept(found -> plugin.runForSender(player, () -> {
            if (!player.isOnline() || stopping) return;
            found.ifPresent(user -> {
                synchronized(repository) {
                    repository.getLoadedByNameMap().remove(user.getName().toLowerCase(Locale.ROOT));
                    synchronized(UserBalance.class) { user.setName(player.getName()); user.setLastSeen(System.currentTimeMillis()); user.markDirty(); }
                    cachePermanent(user);
                }
            });
        })).exceptionally(e -> { plugin.error("Could not warm economy account after join"); return null; });
    }

    private void quit(org.bukkit.entity.Player player) {
        var cached=repository.getLoadedByIdMap().get(player.getUniqueId());
        if(cached==null) return;
        var user=cached.user();
        synchronized(UserBalance.class) { user.setLastSeen(System.currentTimeMillis()); user.markDirty(); }
        cacheTemporary(user);
    }

    @Override protected void cacheTemporary(CoinsUser user) { cache(user,false); }
    @Override protected void cachePermanent(CoinsUser user) { cache(user,true); }
    private void cache(CoinsUser user,boolean permanent) {
        synchronized(repository) {
            synchronized(UserBalance.class) {
                user.trackChanges(this::dirty);
                if(storage!=null && storage.isPostgres()) user.setPersistenceEditor((currency,action)->storage.edit(user.getId(),currency,action));
                long lifetime=settings.getCacheLifetime();
                long deadline=permanent || user.isDirty() || user.isOnline() || lifetime<0 ? -1L
                    : System.currentTimeMillis()+Math.max(1,lifetime)*1000;
                var entry=new CachedUser<>(user,deadline);
                repository.getLoadedByIdMap().put(user.getId(),entry);
                repository.getLoadedByNameMap().put(user.getName().toLowerCase(Locale.ROOT),entry);
                repository.addNameIdMapping(user.getName(),user.getId());
            }
        }
    }
    private void applyCommitted(Collection<CoinsUser> accounts) {
        for(var fresh:accounts) {
            var cached=dirtyUsers.get(fresh.getId());
            if(cached==null) { var entry=repository.getLoadedByIdMap().get(fresh.getId()); if(entry!=null) cached=entry.user(); }
            if(cached!=null) cached.acceptBalances(fresh);
        }
    }
    private void refreshFromStore(CoinsUser fresh) {
        applyCommitted(List.of(fresh));
        var entry=repository.getLoadedByIdMap().get(fresh.getId());
        if(entry!=null) entry.user().acceptMetadata(fresh);
    }
    public Executor ioExecutor() { return lookups; }

    private void dirty(CoinsUser user) {
        dirtyUsers.put(user.getId(),user);
        // A dirty account remains canonical even if an offline cache entry expired during an outage.
        var old=repository.getLoadedByIdMap().get(user.getId());
        if(old==null || old.user()!=user || old.expireDate()!=-1L) {
            var pinned=new CachedUser<>(user,-1L);
            repository.getLoadedByIdMap().put(user.getId(),pinned);
            repository.getLoadedByNameMap().put(user.getName().toLowerCase(Locale.ROOT),pinned);
        }
    }
    void cleanCache() {
        synchronized(repository) {
            synchronized(UserBalance.class) {
                var retained=new HashMap<UUID,CachedUser<CoinsUser>>();
                repository.getLoadedByIdMap().values().forEach(entry -> { if(!entry.isExpired() || entry.user().isDirty() || entry.user().isOnline()) retained.put(entry.user().getId(),entry); });
                dirtyUsers.values().forEach(user -> retained.put(user.getId(),new CachedUser<>(user,-1L)));
                repository.clear();
                retained.values().forEach(entry -> {
                    repository.getLoadedByIdMap().put(entry.user().getId(),entry);
                    repository.getLoadedByNameMap().put(entry.user().getName().toLowerCase(Locale.ROOT),entry);
                    repository.addNameIdMapping(entry.user().getName(),entry.user().getId());
                });
            }
        }
    }
    @Override public void saveDirty() {
        if(Thread.holdsLock(UserBalance.class) || su.nightexpress.excellenteconomy.data.postgres.AccountTransaction.active()) return;
        if(!writer.tryLock()) return;
        try { flushDirty(); } finally { writer.unlock(); }
    }
    private void flushDirty() {
        // One immutable snapshot per dirty account; edits during JDBC I/O remain dirty for the next flush.
        var batch=new ArrayList<CoinsUser>(64);
        var originals=new ArrayList<CoinsUser>(64);
        var versions=new ArrayList<Long>(64);
        long deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(stopping?2000:250);
        for(var user:dirtyUsers.values()) {
            synchronized(UserBalance.class) {
                if(!user.isDirty()) { dirtyUsers.remove(user.getId(),user); continue; }
                originals.add(user); versions.add(user.revision()); batch.add(user.snapshot());
            }
            if(batch.size()==64) { if(!writeBatch(batch,originals,versions)) return; batch.clear(); originals.clear(); versions.clear(); if(System.nanoTime()>deadline) return; }
        }
        if(!batch.isEmpty()) writeBatch(batch,originals,versions);
    }
    private boolean writeBatch(List<CoinsUser> snapshots,List<CoinsUser> users,List<Long> versions) {
        try { dataAccessor.update(snapshots); }
        catch(RuntimeException e) {
            if(System.currentTimeMillis()-lastSaveWarning>30000) { plugin.error("Economy save failed; dirty accounts retained for retry."); lastSaveWarning=System.currentTimeMillis(); }
            return false;
        }
        synchronized(UserBalance.class) {
            for(int i=0;i<users.size();i++) {
                var user=users.get(i);
                if(storage!=null && storage.isPostgres()) user.acknowledgeMetadata(snapshots.get(i));
                if(user.revision()!=versions.get(i)) continue;
                user.markClean(); dirtyUsers.remove(user.getId(),user);
            }
        }
        for(var user:users) if(!user.isOnline() && !user.isDirty()) cacheTemporary(user);
        return true;
    }
    @Override protected void onShutdown() {
        stopping=true;
        lookups.shutdown();
        try {
            if(!lookups.awaitTermination(15,TimeUnit.SECONDS)) {
                lookups.shutdownNow().forEach(task -> { if(task instanceof UserManager.Work<?> work) work.discard(); });
                lookups.awaitTermination(10,TimeUnit.SECONDS);
            }
        } catch(InterruptedException e) {
            lookups.shutdownNow().forEach(task -> { if(task instanceof UserManager.Work<?> work) work.discard(); });
            Thread.currentThread().interrupt();
        }
        writer.lock();
        try {
            flushDirty();
            if(!dirtyUsers.isEmpty()) saveRecovery();
            repository.forEach(user -> user.trackChanges(null));
            repository.clear(); dirtyUsers.clear();
        } finally { writer.unlock(); }
    }
    private void saveRecovery() {
        try {
            var path=plugin.getDataFolder().toPath().resolve(".recovery");
            if(java.nio.file.Files.isSymbolicLink(path)) throw new java.io.IOException("Unsafe recovery directory");
            java.nio.file.Files.createDirectories(path);
            java.nio.file.Files.setPosixFilePermissions(path,java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
            var file=java.nio.file.Files.createTempFile(path,"unsaved-",".json");
            java.nio.file.Files.setPosixFilePermissions(file,java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
            var snapshots=new ArrayList<Map<String,Object>>();
            dirtyUsers.values().forEach(user -> { var copy=user.snapshot(); snapshots.add(Map.of("uuid",copy.getId().toString(),"name",copy.getName(),"balances",copy.getBalance().getBalanceMap(),"settings",copy.getSettingsMap(),"last_seen",copy.getLastSeen(),"hiddenFromTops",copy.isHiddenFromTops())); });
            try(var channel=java.nio.channels.FileChannel.open(file,java.nio.file.StandardOpenOption.WRITE)) { var buffer=java.nio.charset.StandardCharsets.UTF_8.encode(DataHandler.GSON.toJson(snapshots)); while(buffer.hasRemaining()) channel.write(buffer); channel.force(true); }
            plugin.error("Unsaved accounts exported to private .recovery file; manual reconciliation required.");
        } catch(java.io.IOException e) { throw new java.io.UncheckedIOException("Could not preserve unsaved accounts",e); }
    }

    @Override
    @NonNull
    protected CoinsUser create(@NonNull UUID uuid, @NonNull String name, @NonNull InetAddress address) {
        return this.create(uuid, name);
    }

    @NonNull
    public CoinsUser create(@NonNull UUID uuid, @NonNull String name) {
        UserBalance balance = new UserBalance();
        Map<String, CurrencySettings> settingsMap = new HashMap<>();
        long lastSeen = System.currentTimeMillis();
        boolean hiddenFromTops = false;

        this.registry.getCurrencies().forEach(currency -> balance.set(currency, currency.getStartValue()));

        return new CoinsUser(uuid, name, balance, settingsMap, lastSeen, hiddenFromTops);
    }

    public Optional<CoinsUser> cachedId(UUID uuid) {
        var tx=su.nightexpress.excellenteconomy.data.postgres.AccountTransaction.current(uuid);
        if(tx!=null) return Optional.of(tx);
        var dirty=dirtyUsers.get(uuid);
        if(dirty!=null) return Optional.of(dirty);
        var entry=repository.getLoadedByIdMap().get(uuid);
        return entry!=null && !entry.isExpired()?Optional.of(entry.user()):Optional.empty();
    }
    public Optional<CoinsUser> cachedName(String name) {
        var tx=su.nightexpress.excellenteconomy.data.postgres.AccountTransaction.named(name);
        if(tx!=null) return Optional.of(tx);
        var id=repository.getAssociatedId(name); var dirty=id==null?null:dirtyUsers.get(id);
        if(dirty!=null && dirty.getName().equalsIgnoreCase(name)) return Optional.of(dirty);
        var entry=repository.getLoadedByNameMap().get(name.toLowerCase(Locale.ROOT));
        return entry!=null && !entry.isExpired() && entry.user().getName().equalsIgnoreCase(name)?Optional.of(entry.user()):Optional.empty();
    }
    private void allowColdLookup() {
        if(stopping || Thread.holdsLock(UserBalance.class) || su.nightexpress.excellenteconomy.data.postgres.AccountTransaction.active())
            throw new IllegalStateException("Cold/nested account lookup is unavailable during a balance event or shutdown");
    }
    @NonNull public Optional<CoinsUser> findById(UUID uuid) {
        var cached=cachedId(uuid); if(cached.isPresent()) return cached;
        allowColdLookup();
        return loadOnce("id:"+uuid,()->cachedId(uuid).or(()->dataAccessor.loadById(uuid)));
    }
    @NonNull public Optional<CoinsUser> findByName(String name) {
        var player=Bukkit.getPlayerExact(name); if(player!=null) return findById(player.getUniqueId());
        var cached=cachedName(name); if(cached.isPresent()) return cached;
        allowColdLookup();
        return loadOnce("name:"+name.toLowerCase(Locale.ROOT),()->cachedName(name).or(()->dataAccessor.loadByName(name)));
    }

    private Optional<CoinsUser> loadOnce(String key, java.util.function.Supplier<Optional<CoinsUser>> query) {
        var promise=new CompletableFuture<Optional<CoinsUser>>();
        var existing=inFlight.putIfAbsent(key,promise);
        if(existing!=null) return existing.join();
        try {
            for(int attempt=0;attempt<2;attempt++) {
                if(stopping) throw new RejectedExecutionException("Economy is closing");
                long epoch=storageEpoch;
                Optional<CoinsUser> loaded=query.get();
                synchronized(repository) {
                    if(stopping) throw new RejectedExecutionException("Economy is closing");
                    if(epoch!=storageEpoch || ((epoch&1L)!=0 && !writer.isHeldByCurrentThread())) continue;
                    if(loaded.isPresent()) {
                        var dirty=dirtyUsers.get(loaded.get().getId());
                        var current=dirty!=null?Optional.of(dirty):repository.getById(loaded.get().getId());
                        if(current.isPresent()) loaded=current;
                        else cacheTemporary(loaded.get());
                    }
                }
                promise.complete(loaded); return loaded;
            }
            throw new IllegalStateException("Account changed during maintenance; retry the lookup");
        } catch(RuntimeException e) { promise.completeExceptionally(e); throw e; }
        finally { inFlight.remove(key,promise); }
    }

    public CoinsUser requireLoaded(org.bukkit.entity.Player player) {
        var transaction=su.nightexpress.excellenteconomy.data.postgres.AccountTransaction.current(player.getUniqueId());
        if(transaction!=null) return transaction;
        var dirty=dirtyUsers.get(player.getUniqueId());
        if(dirty!=null) return dirty;
        var entry=repository.getLoadedByIdMap().get(player.getUniqueId());
        if(entry!=null && (!entry.isExpired() || entry.user().isOnline())) return entry.user();
        throw new IllegalStateException("Economy account is still loading; retry shortly or use asynchronous lookup");
    }

    public CompletableFuture<Optional<CoinsUser>> findByIdAsync(UUID uuid) {
        if(stopping) return CompletableFuture.failedFuture(new RejectedExecutionException("Economy is closing"));
        var cached=cachedId(uuid); if(cached.isPresent()) return CompletableFuture.completedFuture(cached);
        return workAsync(()->findById(uuid));
    }
    public CompletableFuture<Optional<CoinsUser>> findByNameAsync(String name) {
        if(stopping) return CompletableFuture.failedFuture(new RejectedExecutionException("Economy is closing"));
        var player=Bukkit.getPlayerExact(name); if(player!=null) return findByIdAsync(player.getUniqueId());
        var cached=cachedName(name); if(cached.isPresent()) return CompletableFuture.completedFuture(cached);
        return workAsync(()->findByName(name));
    }

    public void withUser(CommandSender sender,String name,Consumer<Optional<CoinsUser>> action) {
        var found=findByNameAsync(name);
        CompletableFuture<Void> work;
        if(storage!=null && storage.isPostgres()) work=found.thenCompose(user -> workAsync(() -> { if(!stopping) action.accept(user); return null; }));
        else work=found.thenAccept(user->{ if(!stopping) plugin.runForSender(sender,()->action.accept(user)); });
        work.exceptionally(error->{
            if(!stopping) { plugin.error("Could not load or update economy account"); plugin.runForSender(sender,()->Lang.USER_LOOKUP_FAILED.message().send(sender)); }
            return null;
        });
    }

    private boolean validName(String name) {
        return !name.isBlank() && name.length()<=DataColumns.USER_NAME_LENGTH && name.chars().noneMatch(Character::isISOControl);
    }
    public boolean createAccount(UUID uuid, String name) {
        if(!validName(name) || stopping || Thread.holdsLock(UserBalance.class) || su.nightexpress.excellenteconomy.data.postgres.AccountTransaction.active()) return false;
        writer.lock();
        try {
            if(findById(uuid).isPresent()) return true;
            advanceEpoch();
            try {
                dataAccessor.insert(create(uuid,name));
                dataAccessor.loadById(uuid).ifPresent(this::cacheTemporary);
            } finally { advanceEpoch(); }
            return true;
        } finally { writer.unlock(); }
    }
    public boolean renameAccount(UUID uuid,String name) {
        if(!validName(name) || stopping || Thread.holdsLock(UserBalance.class) || su.nightexpress.excellenteconomy.data.postgres.AccountTransaction.active()) return false;
        writer.lock();
        try {
            var user=findById(uuid).orElse(null);
            if(user==null || user.isOnline()) return false;
            var other=findByName(name);
            if(other.isPresent() && !other.get().getId().equals(uuid)) return false;
            var snapshot=user.snapshot(); snapshot.setName(name); snapshot.setLastSeen(System.currentTimeMillis());
            advanceEpoch();
            try {
                dataAccessor.update(snapshot);
                synchronized(repository) {
                    repository.getLoadedByNameMap().remove(user.getName().toLowerCase(Locale.ROOT));
                    user.setName(name); cacheTemporary(user);
                }
            } finally { advanceEpoch(); }
            return true;
        } finally { writer.unlock(); }
    }
    public boolean deleteAccount(UUID uuid) {
        if(stopping || Thread.holdsLock(UserBalance.class) || su.nightexpress.excellenteconomy.data.postgres.AccountTransaction.active()) return false;
        writer.lock();
        try {
            var user=findById(uuid).orElse(null);
            if(user==null || user.isOnline()) return false;
            advanceEpoch();
            try {
                dataAccessor.deleteById(uuid);
                synchronized(repository) {
                    repository.getLoadedByNameMap().remove(user.getName().toLowerCase(Locale.ROOT));
                    repository.remove(uuid); dirtyUsers.remove(uuid); user.trackChanges(null);
                }
            } finally { advanceEpoch(); }
            return true;
        } finally { writer.unlock(); }
    }
    private void advanceEpoch() { synchronized(repository) { storageEpoch++; } }
    public void maintenance(Runnable operation) {
        allowColdLookup();
        writer.lock();
        try { advanceEpoch(); try { operation.run(); } finally { advanceEpoch(); } }
        finally { writer.unlock(); }
    }
    public void synchronizeAccounts(Runnable operation) {
        writer.lock();
        try { operation.run(); } finally { writer.unlock(); }
    }

    public Map<UUID, String> getAccountNames() {
        Map<UUID, String> names = new HashMap<>();
        this.dataAccessor.loadProfiles().forEach(profile -> names.put(profile.id(), profile.name()));
        this.repository.forEach(user -> names.put(user.getId(), user.getName()));
        return Map.copyOf(names);
    }

    @Override
    protected void handleJoin(@NonNull CoinsUser user) {
        user.setLastSeen(System.currentTimeMillis());
    }

    @Override
    protected void handleQuit(@NonNull CoinsUser user) {
        user.setLastSeen(System.currentTimeMillis());
    }

    @Override
    protected void synchronize(@NonNull CoinsUser fetched, @NonNull CoinsUser cached) {
        synchronized (UserBalance.class) {
            if (cached.isDirty()) return;
            for (ExcellentCurrency currency : this.registry.getCurrencies()) {
                if (!currency.isSynchronizable()) continue;
                double balance = fetched.getBalance(currency);
                cached.getBalance().set(currency, balance); // Bypass balance event call.
            }
        }
    }
}
