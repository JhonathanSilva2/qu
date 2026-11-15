package com.aicivilization.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.Arrays;
import java.util.List;

public final class StorageQueueScreen extends Screen {
    private static final int PANEL_WIDTH = 340;
    private static final int PANEL_HEIGHT = 260;
    private static final int VISIBLE_LINES = 9;

    private final Runnable refreshAction;
    private List<String> queueLines = List.of("Carregando pedidos...");
    private int scrollOffset;
    private int panelLeft;
    private int panelTop;

    public StorageQueueScreen(Runnable refreshAction) {
        super(Component.literal("Pedidos de craft — Storager"));
        this.refreshAction = refreshAction;
    }

    @Override
    protected void init() {
        panelLeft = (width - PANEL_WIDTH) / 2;
        panelTop = (height - PANEL_HEIGHT) / 2;
        addRenderableWidget(Button.builder(Component.literal("Atualizar"), button -> refreshAction.run())
            .bounds(panelLeft + 65, panelTop + PANEL_HEIGHT - 30, 95, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Fechar"), button -> onClose())
            .bounds(panelLeft + PANEL_WIDTH - 160, panelTop + PANEL_HEIGHT - 30, 95, 20).build());
    }

    public void setQueueText(String content) {
        queueLines = Arrays.stream(content.split("\\R"))
            .filter(line -> !line.isBlank())
            .limit(64)
            .toList();
        if (queueLines.isEmpty()) queueLines = List.of("Nenhum pedido pendente para o Storager.");
        scrollOffset = Math.min(scrollOffset, Math.max(0, queueLines.size() - VISIBLE_LINES));
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Custom background is drawn directly in render() to avoid duplicate frame blur in 1.21.10
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, 0xC0101218);
        graphics.fill(panelLeft, panelTop, panelLeft + PANEL_WIDTH, panelTop + PANEL_HEIGHT, 0xE020242B);
        graphics.fill(panelLeft, panelTop, panelLeft + PANEL_WIDTH, panelTop + 2, 0xFF42D9E8);
        graphics.drawCenteredString(font, title, width / 2, panelTop + 13, 0xFFFFFFFF);
        graphics.drawString(font, "Pedidos abertos desta civilização:", panelLeft + 18, panelTop + 38, 0xFFB8C5D1);

        int end = Math.min(queueLines.size(), scrollOffset + VISIBLE_LINES);
        for (int index = scrollOffset; index < end; index++) {
            graphics.drawString(font, queueLines.get(index), panelLeft + 20,
                panelTop + 62 + (index - scrollOffset) * 18, 0xFFFFFFFF);
        }
        if (queueLines.size() > VISIBLE_LINES) {
            graphics.drawString(font, "Use a roda do mouse para ver mais pedidos.", panelLeft + 18,
                panelTop + PANEL_HEIGHT - 48, 0xFF9AA7B3);
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int maxOffset = Math.max(0, queueLines.size() - VISIBLE_LINES);
        scrollOffset = Math.max(0, Math.min(maxOffset, scrollOffset - (int) Math.signum(scrollY)));
        return true;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
