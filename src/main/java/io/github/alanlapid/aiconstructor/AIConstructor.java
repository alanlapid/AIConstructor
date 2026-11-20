package io.github.alanlapid.aiconstructor;

import com.google.gson.JsonParser;
import io.github.alanlapid.aiconstructor.ai.AIProvider;
import io.github.alanlapid.aiconstructor.ai.AnthropicProvider;
import io.github.alanlapid.aiconstructor.ai.GeminiProvider;
import io.github.alanlapid.aiconstructor.ai.OllamaProvider;
import io.github.alanlapid.aiconstructor.ai.OpenAICompatibleProvider;
import io.github.alanlapid.aiconstructor.ai.PlanGenerator;
import io.github.alanlapid.aiconstructor.ai.ProviderSettings;
import io.github.alanlapid.aiconstructor.build.BuildManager;
import io.github.alanlapid.aiconstructor.command.AIBuildCommand;
import io.github.alanlapid.aiconstructor.util.Messages;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public final class AIConstructor extends JavaPlugin {

    private Messages messages;
    private PlanGenerator generator;
    private BuildManager builds;
    private boolean providerConfigured;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        if (!Files.exists(dataPath().resolve("system_prompt.txt"))) {
            saveResource("system_prompt.txt", false);
        }
        builds = new BuildManager(this);
        getServer().getPluginManager().registerEvents(builds, this);
        reloadAll();

        AIBuildCommand command = new AIBuildCommand(this);
        PluginCommand pc = getCommand("aibuild");
        if (pc != null) {
            pc.setExecutor(command);
            pc.setTabCompleter(command);
        }
    }

    @Override
    public void onDisable() {
        if (builds != null) {
            builds.shutdown();
        }
        if (generator != null) {
            generator.provider().close();
        }
    }

    /** (Re)loads config.yml, the language file, the system prompt and the AI provider. */
    public void reloadAll() {
        reloadConfig();
        FileConfiguration cfg = getConfig();
        messages = new Messages(this, cfg.getString("language", "en"));

        String systemPrompt;
        try {
            systemPrompt = Files.readString(dataPath().resolve("system_prompt.txt"), StandardCharsets.UTF_8);
        } catch (IOException e) {
            getLogger().warning("Could not read system_prompt.txt, using the bundled one: " + e.getMessage());
            systemPrompt = readResource("system_prompt.txt");
        }

        if (generator != null) {
            generator.provider().close();
        }
        String type = cfg.getString("provider.type", "gemini").toLowerCase(Locale.ROOT);
        ProviderSettings settings = providerSettings(cfg);
        providerConfigured = settings.hasKey() || type.equals("ollama") || (type.equals("openai") && isLocal(settings.baseUrl()));
        AIProvider provider = switch (type) {
            case "anthropic", "claude" -> new AnthropicProvider(settings);
            case "openai", "openai-compatible" -> new OpenAICompatibleProvider(settings);
            case "ollama" -> new OllamaProvider(settings);
            default -> {
                if (!type.equals("gemini")) {
                    getLogger().warning("Unknown provider.type '" + type + "', using gemini.");
                }
                yield new GeminiProvider(settings);
            }
        };
        generator = new PlanGenerator(provider, cfg.getStringList("provider.models"),
                cfg.getInt("provider.rounds", 3), cfg.getLong("provider.retry-delay-seconds", 4) * 1000L,
                systemPrompt, getLogger());
        builds.reload();

        if (!providerConfigured) {
            getLogger().warning("No API key configured: set provider.api-key in config.yml and run /aibuild reload");
        }
        getLogger().info("Provider: " + provider.name() + " | models: " + generator.models());
    }

    private ProviderSettings providerSettings(FileConfiguration cfg) {
        Map<String, String> extraHeaders = new LinkedHashMap<>();
        ConfigurationSection headers = cfg.getConfigurationSection("provider.openai.extra-headers");
        if (headers != null) {
            for (String key : headers.getKeys(false)) {
                extraHeaders.put(key, headers.getString(key, ""));
            }
        }
        return new ProviderSettings(
                resolveKey(cfg.getString("provider.api-key", "")),
                cfg.getString("provider.base-url", ""),
                Duration.ofSeconds(Math.max(30, cfg.getLong("provider.timeout-seconds", 180))),
                cfg.getDouble("provider.temperature", 0.6),
                Math.max(1024, cfg.getInt("provider.max-output-tokens", 16000)),
                JsonParser.parseString(readResource("plan-schema.json")).getAsJsonObject(),
                cfg.getString("provider.openai.response-format", "json_schema"),
                Map.copyOf(extraHeaders),
                cfg.getBoolean("provider.anthropic.refusal-fallback", true),
                cfg.getString("provider.ollama.think", "false"));
    }

    /** "env:NAME" reads the key from an environment variable; the placeholder value means "not set". */
    private static String resolveKey(String value) {
        if (value == null) {
            return "";
        }
        String v = value.trim();
        if (v.startsWith("env:")) {
            String env = System.getenv(v.substring(4));
            return env == null ? "" : env.trim();
        }
        return v.equals("PASTE_YOUR_KEY_HERE") ? "" : v;
    }

    private static boolean isLocal(String baseUrl) {
        return baseUrl != null && (baseUrl.contains("localhost") || baseUrl.contains("127.0.0.1") || baseUrl.contains("0.0.0.0"));
    }

    private String readResource(String name) {
        try (InputStream in = getResource(name)) {
            if (in == null) {
                throw new IllegalStateException("Resource " + name + " missing from the jar");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public Path dataPath() {
        return getDataFolder().toPath();
    }

    public Messages messages() {
        return messages;
    }

    public PlanGenerator generator() {
        return generator;
    }

    public BuildManager builds() {
        return builds;
    }

    public boolean isProviderConfigured() {
        return providerConfigured;
    }
}
