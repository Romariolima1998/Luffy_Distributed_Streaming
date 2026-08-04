package dev.lufi.infrastructure;

import java.time.Duration;

/** Configuração central da Swarm Assist List, sem limites espalhados pelo motor P2P. */
public final class SwarmAssistSettings {
    public static final String MAX_ASSIST_SWARMS_KEY = "swarm.assist.max";
    public static final String MAX_ASSIST_SWARMS_PROPERTY = "luffy.swarm.assist.max";
    public static final int DEFAULT_MAX_ASSIST_SWARMS = 25;
    public static final String MIN_ASSIST_RESIDENCE_MINUTES_KEY = "swarm.assist.min.residence.minutes";
    public static final String MIN_ASSIST_RESIDENCE_MINUTES_PROPERTY = "luffy.swarm.assist.min.residence.minutes";
    public static final Duration DEFAULT_MIN_ASSIST_RESIDENCE = Duration.ofMinutes(30);
    public static final String REPLACEMENT_THRESHOLD_KEY = "swarm.assist.replacement.threshold";
    public static final String REPLACEMENT_THRESHOLD_PROPERTY = "luffy.swarm.assist.replacement.threshold";
    public static final double DEFAULT_REPLACEMENT_THRESHOLD = 0.20d;
    /** Swarms com esta população ou menos recebem prioridade forte de assistência. */
    public static final String CRITICAL_SWARM_PEER_COUNT_KEY = "swarm.assist.critical.peer.count";
    public static final String CRITICAL_SWARM_PEER_COUNT_PROPERTY = "luffy.swarm.assist.critical.peer.count";
    public static final int DEFAULT_CRITICAL_SWARM_PEER_COUNT = 3;
    /** Após este prazo, população persistida só pode ser usada novamente depois de nova observação DHT/PEX. */
    public static final String SWARM_STATS_TTL_MINUTES_KEY = "swarm.assist.stats.ttl.minutes";
    public static final String SWARM_STATS_TTL_MINUTES_PROPERTY = "luffy.swarm.assist.stats.ttl.minutes";
    public static final Duration SWARM_STATS_TTL = Duration.ofMinutes(10);
    /** Um swarm sem nenhum peer confirmado recebe uma janela curta antes de liberar a vaga Assist. */
    public static final String EMPTY_SWARM_DECAY_MINUTES_KEY = "swarm.assist.empty.decay.minutes";
    public static final String EMPTY_SWARM_DECAY_MINUTES_PROPERTY = "luffy.swarm.assist.empty.decay.minutes";
    public static final Duration DEFAULT_EMPTY_SWARM_DECAY = Duration.ofHours(6);
    /** Sem usuário, peer ou rendezvous útil recente, a entrada Assist libera a vaga. */
    public static final String INACTIVE_SWARM_DECAY_MINUTES_KEY = "swarm.assist.inactive.decay.minutes";
    public static final String INACTIVE_SWARM_DECAY_MINUTES_PROPERTY = "luffy.swarm.assist.inactive.decay.minutes";
    public static final Duration DEFAULT_INACTIVE_SWARM_DECAY = Duration.ofDays(7);
    public static final String MAX_ASSIST_CONNECTIONS_PER_SWARM_KEY = "swarm.assist.connections.per.swarm";
    public static final String MAX_ASSIST_CONNECTIONS_PER_SWARM_PROPERTY = "luffy.swarm.assist.connections.per.swarm";
    public static final String MAX_ASSIST_CONNECTIONS_TOTAL_KEY = "swarm.assist.connections.total";
    public static final String MAX_ASSIST_CONNECTIONS_TOTAL_PROPERTY = "luffy.swarm.assist.connections.total";

    private final SettingsRepository settings;

    public SwarmAssistSettings(SettingsRepository settings) { this.settings = settings; }

    /** Prioriza a configuração persistida; a propriedade Java permite configuração de distribuição/diagnóstico. */
    public int maxAssistSwarms() {
        String configured = settings.get(MAX_ASSIST_SWARMS_KEY).orElse(System.getProperty(MAX_ASSIST_SWARMS_PROPERTY, ""));
        try {
            int value = Integer.parseInt(configured.trim());
            return value > 0 ? value : DEFAULT_MAX_ASSIST_SWARMS;
        } catch (RuntimeException ignored) {
            return DEFAULT_MAX_ASSIST_SWARMS;
        }
    }

    public void setMaxAssistSwarms(int value) {
        if (value < 1) throw new IllegalArgumentException("O limite de Swarm Assist deve ser maior que zero.");
        settings.put(MAX_ASSIST_SWARMS_KEY, Integer.toString(value));
    }

    public Duration minAssistResidence() {
        String configured = settings.get(MIN_ASSIST_RESIDENCE_MINUTES_KEY).orElse(System.getProperty(MIN_ASSIST_RESIDENCE_MINUTES_PROPERTY, ""));
        try {
            long minutes = Long.parseLong(configured.trim());
            return minutes >= 0 ? Duration.ofMinutes(minutes) : DEFAULT_MIN_ASSIST_RESIDENCE;
        } catch (RuntimeException ignored) {
            return DEFAULT_MIN_ASSIST_RESIDENCE;
        }
    }

    public double replacementThreshold() {
        String configured = settings.get(REPLACEMENT_THRESHOLD_KEY).orElse(System.getProperty(REPLACEMENT_THRESHOLD_PROPERTY, ""));
        try {
            double threshold = Double.parseDouble(configured.trim());
            return threshold >= 0d && threshold < 1d ? threshold : DEFAULT_REPLACEMENT_THRESHOLD;
        } catch (RuntimeException ignored) {
            return DEFAULT_REPLACEMENT_THRESHOLD;
        }
    }

    /**
     * Não é o limite de conexões: é a população na qual um swarm se torna
     * frágil. Um único peer perdido pode inviabilizar a descoberta e o
     * rendezvous de um swarm com 1, 2 ou 3 participantes.
     */
    public int criticalSwarmPeerCount() {
        String configured = settings.get(CRITICAL_SWARM_PEER_COUNT_KEY)
                .orElse(System.getProperty(CRITICAL_SWARM_PEER_COUNT_PROPERTY, ""));
        try {
            int value = Integer.parseInt(configured.trim());
            return value >= 0 ? value : DEFAULT_CRITICAL_SWARM_PEER_COUNT;
        } catch (RuntimeException ignored) {
            return DEFAULT_CRITICAL_SWARM_PEER_COUNT;
        }
    }

    public Duration swarmStatsTtl() {
        String configured = settings.get(SWARM_STATS_TTL_MINUTES_KEY)
                .orElse(System.getProperty(SWARM_STATS_TTL_MINUTES_PROPERTY, ""));
        try {
            long minutes = Long.parseLong(configured.trim());
            return minutes >= 0 ? Duration.ofMinutes(minutes) : SWARM_STATS_TTL;
        } catch (RuntimeException ignored) {
            return SWARM_STATS_TTL;
        }
    }

    /** Prazo máximo de retenção de um swarm confirmado vazio, contado desde a primeira observação zero. */
    public Duration emptySwarmDecay() {
        String configured = settings.get(EMPTY_SWARM_DECAY_MINUTES_KEY)
                .orElse(System.getProperty(EMPTY_SWARM_DECAY_MINUTES_PROPERTY, ""));
        try {
            long minutes = Long.parseLong(configured.trim());
            return minutes >= 0 ? Duration.ofMinutes(minutes) : DEFAULT_EMPTY_SWARM_DECAY;
        } catch (RuntimeException ignored) {
            return DEFAULT_EMPTY_SWARM_DECAY;
        }
    }

    /** Prazo máximo para um swarm antigo sem interação, peer visto ou utilidade de rendezvous. */
    public Duration inactiveSwarmDecay() {
        String configured = settings.get(INACTIVE_SWARM_DECAY_MINUTES_KEY)
                .orElse(System.getProperty(INACTIVE_SWARM_DECAY_MINUTES_PROPERTY, ""));
        try {
            long minutes = Long.parseLong(configured.trim());
            return minutes >= 0 ? Duration.ofMinutes(minutes) : DEFAULT_INACTIVE_SWARM_DECAY;
        } catch (RuntimeException ignored) {
            return DEFAULT_INACTIVE_SWARM_DECAY;
        }
    }

    public int maxAssistConnectionsPerSwarm() {
        return positiveInt(MAX_ASSIST_CONNECTIONS_PER_SWARM_KEY, MAX_ASSIST_CONNECTIONS_PER_SWARM_PROPERTY,
                SwarmAssistPolicy.DEFAULT_MAXIMUM_CONNECTIONS_PER_SWARM);
    }

    public int maxAssistConnectionsTotal() {
        return Math.max(maxAssistConnectionsPerSwarm(), positiveInt(MAX_ASSIST_CONNECTIONS_TOTAL_KEY,
                MAX_ASSIST_CONNECTIONS_TOTAL_PROPERTY, SwarmAssistPolicy.DEFAULT_MAXIMUM_CONNECTIONS_TOTAL));
    }

    public void setMinAssistResidence(Duration value) {
        if (value == null || value.isNegative()) throw new IllegalArgumentException("A permanência mínima não pode ser negativa.");
        settings.put(MIN_ASSIST_RESIDENCE_MINUTES_KEY, Long.toString(value.toMinutes()));
    }

    public void setReplacementThreshold(double value) {
        if (value < 0d || value >= 1d) throw new IllegalArgumentException("A margem de substituição deve estar entre 0 e 1.");
        settings.put(REPLACEMENT_THRESHOLD_KEY, Double.toString(value));
    }

    public void setCriticalSwarmPeerCount(int value) {
        if (value < 0) throw new IllegalArgumentException("A população crítica não pode ser negativa.");
        settings.put(CRITICAL_SWARM_PEER_COUNT_KEY, Integer.toString(value));
    }

    public void setSwarmStatsTtl(Duration value) {
        if (value == null || value.isNegative()) throw new IllegalArgumentException("O prazo das estatísticas não pode ser negativo.");
        settings.put(SWARM_STATS_TTL_MINUTES_KEY, Long.toString(value.toMinutes()));
    }

    public void setEmptySwarmDecay(Duration value) {
        if (value == null || value.isNegative()) throw new IllegalArgumentException("O prazo de um swarm vazio não pode ser negativo.");
        settings.put(EMPTY_SWARM_DECAY_MINUTES_KEY, Long.toString(value.toMinutes()));
    }

    public void setInactiveSwarmDecay(Duration value) {
        if (value == null || value.isNegative()) throw new IllegalArgumentException("O prazo de inatividade do swarm não pode ser negativo.");
        settings.put(INACTIVE_SWARM_DECAY_MINUTES_KEY, Long.toString(value.toMinutes()));
    }

    public void setMaxAssistConnectionsPerSwarm(int value) {
        if (value < 1) throw new IllegalArgumentException("O limite de conexoes por swarm deve ser maior que zero.");
        settings.put(MAX_ASSIST_CONNECTIONS_PER_SWARM_KEY, Integer.toString(value));
    }

    public void setMaxAssistConnectionsTotal(int value) {
        if (value < 1) throw new IllegalArgumentException("O limite total de conexoes deve ser maior que zero.");
        settings.put(MAX_ASSIST_CONNECTIONS_TOTAL_KEY, Integer.toString(value));
    }

    private int positiveInt(String key, String property, int defaultValue) {
        String configured = settings.get(key).orElse(System.getProperty(property, ""));
        try {
            int value = Integer.parseInt(configured.trim());
            return value > 0 ? value : defaultValue;
        } catch (RuntimeException ignored) {
            return defaultValue;
        }
    }
}
