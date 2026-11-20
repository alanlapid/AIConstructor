package io.github.alanlapid.aiconstructor.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.bungeecord.BungeeComponentSerializer;
import net.md_5.bungee.api.ChatMessageType;
import org.bukkit.entity.Player;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** Loads lang/&lt;language&gt;.yml from the data folder, falling back to the bundled English file per key. */
public final class Messages {

    private static final String[] BUNDLED = {"en", "es"};

    private final YamlConfiguration messages;
    private final YamlConfiguration fallback;

    public Messages(JavaPlugin plugin, String language) {
        for (String lang : BUNDLED) {
            if (!new File(plugin.getDataFolder(), "lang/" + lang + ".yml").exists()) {
                plugin.saveResource("lang/" + lang + ".yml", false);
            }
        }
        fallback = loadBundled(plugin, "en");
        File file = new File(plugin.getDataFolder(), "lang/" + language + ".yml");
        if (file.exists()) {
            messages = YamlConfiguration.loadConfiguration(file);
        } else {
            plugin.getLogger().warning("Language file lang/" + language + ".yml not found, using English.");
            messages = fallback;
        }
    }

    private static YamlConfiguration loadBundled(JavaPlugin plugin, String lang) {
        try (InputStream in = plugin.getResource("lang/" + lang + ".yml")) {
            if (in == null) {
                return new YamlConfiguration();
            }
            return YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (java.io.IOException e) {
            return new YamlConfiguration();
        }
    }

    /** A placeholder value that is already MiniMessage and must not be escaped. */
    public record Raw(String value) {
    }

    /** Raw MiniMessage string with {placeholders} replaced. Values are escaped so they cannot inject tags. */
    public String raw(String key, Object... placeholders) {
        String text = messages.getString(key, fallback.getString(key, key));
        for (int i = 0; i + 1 < placeholders.length; i += 2) {
            Object value = placeholders[i + 1];
            String replacement = value instanceof Raw r ? r.value() : esc(String.valueOf(value));
            text = text.replace("{" + placeholders[i] + "}", replacement);
        }
        return text;
    }

    public Component component(String key, Object... placeholders) {
        return MiniMessage.miniMessage().deserialize(raw(key, placeholders));
    }

    /** Sends a prefixed chat message. */
    public void send(CommandSender to, String key, Object... placeholders) {
        sendComponent(to, MiniMessage.miniMessage().deserialize(raw("prefix") + raw(key, placeholders)));
    }

    /** Sends a chat message without prefix. */
    public void sendPlain(CommandSender to, String key, Object... placeholders) {
        sendComponent(to, component(key, placeholders));
    }

    // Spigot has no Adventure API, so components go out through the BungeeCord chat API that both Spigot and
    // Paper ship. Colours, hover and click events are kept.

    /** Sends a component as a chat message. */
    public void sendComponent(CommandSender to, Component component) {
        to.spigot().sendMessage(BungeeComponentSerializer.get().serialize(component));
    }

    /** Shows a component on the player's action bar. */
    public void sendActionBar(Player to, Component component) {
        to.spigot().sendMessage(ChatMessageType.ACTION_BAR, BungeeComponentSerializer.get().serialize(component));
    }

    public static String esc(String text) {
        return MiniMessage.miniMessage().escapeTags(text == null ? "" : text);
    }
}
