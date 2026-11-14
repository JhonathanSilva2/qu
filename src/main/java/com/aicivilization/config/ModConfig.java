package com.aicivilization.config;

import com.aicivilization.AICivilizationMod;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public final class ModConfig {
    private static final String DEFAULT_CITIZEN_NAME = "Cidadão";
    private static String citizenName = DEFAULT_CITIZEN_NAME;

    private ModConfig() {}

    public static void load() {
        Path configPath = FabricLoader.getInstance().getConfigDir().resolve("ai_civilization.properties");
        Properties properties = new Properties();
        try {
            Files.createDirectories(configPath.getParent());
            if (Files.exists(configPath)) {
                try (var reader = Files.newBufferedReader(configPath)) {
                    properties.load(reader);
                }
            } else {
                properties.setProperty("citizen-name", DEFAULT_CITIZEN_NAME);
                try (var writer = Files.newBufferedWriter(configPath)) {
                    properties.store(writer, "AI Civilization settings");
                }
            }

            citizenName = properties.getProperty("citizen-name", DEFAULT_CITIZEN_NAME).trim();
            if (citizenName.isBlank()) {
                citizenName = DEFAULT_CITIZEN_NAME;
            }
            AICivilizationMod.LOGGER.info("Configuração carregada de {}", configPath);
        } catch (IOException exception) {
            citizenName = DEFAULT_CITIZEN_NAME;
            AICivilizationMod.LOGGER.warn("Não foi possível carregar a configuração; usando valores padrão.", exception);
        }
    }

    public static String citizenName() {
        return citizenName;
    }
}
