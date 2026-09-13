package io.tapdata.it.tpcc;

public final class TpccConfig {

    private final String mode;
    private final int warehouses;
    private final int loadWorkers;
    private final int terminals;
    private final int transactionsPerTerminal;
    private final int runMinutes;
    private final int timeoutSeconds;
    private final int minimumEvents;
    private final int minimumChangedTables;
    private final boolean cleanup;

    private TpccConfig(String mode, int warehouses, int loadWorkers, int terminals,
                       int transactionsPerTerminal, int runMinutes, int timeoutSeconds,
                       int minimumEvents, int minimumChangedTables, boolean cleanup) {
        this.mode = mode;
        this.warehouses = warehouses;
        this.loadWorkers = loadWorkers;
        this.terminals = terminals;
        this.transactionsPerTerminal = transactionsPerTerminal;
        this.runMinutes = runMinutes;
        this.timeoutSeconds = timeoutSeconds;
        this.minimumEvents = minimumEvents;
        this.minimumChangedTables = minimumChangedTables;
        this.cleanup = cleanup;
    }

    public static TpccConfig fromSystemProperties() {
        String mode = value("tpcc.mode", "TPCC_MODE", "smoke");
        boolean full = "full".equalsIgnoreCase(mode);
        return new TpccConfig(
                mode,
                integer("tpcc.warehouses", "TPCC_WAREHOUSES", full ? 10 : 1),
                integer("tpcc.loadWorkers", "TPCC_LOAD_WORKERS", full ? 4 : 1),
                integer("tpcc.terminals", "TPCC_TERMINALS", full ? 20 : 1),
                integer("tpcc.transactionsPerTerminal", "TPCC_TRANSACTIONS_PER_TERMINAL", full ? 0 : 20),
                integer("tpcc.runMinutes", "TPCC_RUN_MINUTES", full ? 10 : 0),
                integer("tpcc.timeoutSeconds", "TPCC_TIMEOUT_SECONDS", full ? 1800 : 600),
                integer("tpcc.minimumEvents", "TPCC_MINIMUM_EVENTS", full ? 1000 : 20),
                integer("tpcc.minimumChangedTables", "TPCC_MINIMUM_CHANGED_TABLES", 3),
                bool("tpcc.cleanup", "TPCC_CLEANUP", true));
    }

    private static String value(String property, String environment, String defaultValue) {
        String value = System.getProperty(property);
        if (value == null || value.trim().isEmpty()) {
            value = System.getenv(environment);
        }
        return value == null || value.trim().isEmpty() ? defaultValue : value.trim();
    }

    private static int integer(String property, String environment, int defaultValue) {
        return Integer.parseInt(value(property, environment, String.valueOf(defaultValue)));
    }

    private static boolean bool(String property, String environment, boolean defaultValue) {
        return Boolean.parseBoolean(value(property, environment, String.valueOf(defaultValue)));
    }

    public String getMode() {
        return mode;
    }

    public int getWarehouses() {
        return warehouses;
    }

    public int getLoadWorkers() {
        return loadWorkers;
    }

    public int getTerminals() {
        return terminals;
    }

    public int getTransactionsPerTerminal() {
        return transactionsPerTerminal;
    }

    public int getRunMinutes() {
        return runMinutes;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public int getMinimumEvents() {
        return minimumEvents;
    }

    public int getMinimumChangedTables() {
        return minimumChangedTables;
    }

    public boolean isCleanup() {
        return cleanup;
    }
}
