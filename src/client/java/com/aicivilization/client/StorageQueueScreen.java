package com.aicivilization.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class StorageQueueScreen extends Screen {
    private static final int MAX_PANEL_WIDTH = 460;
    private static final int MAX_PANEL_HEIGHT = 280;
    private static final int LINE_HEIGHT = 13;

    private final Runnable refreshAction;
    private List<String> storageLines = List.of("Carregando pedidos...");
    private List<String> buildingLines = List.of("Carregando construções...");
    private int activeTab = 0; // 0 = Storager, 1 = Construções
    private int scrollOffset;
    private int panelLeft;
    private int panelTop;
    private int panelWidth;
    private int panelHeight;

    public StorageQueueScreen(Runnable refreshAction) {
        super(Component.literal("Pedidos de craft — Storager"));
        this.refreshAction = refreshAction;
    }

    @Override
    protected void init() {
        panelWidth = Math.min(MAX_PANEL_WIDTH, width - 24);
        panelHeight = Math.min(MAX_PANEL_HEIGHT, height - 24);
        panelLeft = (width - panelWidth) / 2;
        panelTop = (height - panelHeight) / 2;
        addRenderableWidget(Button.builder(Component.literal("<"), button -> switchTab(-1))
            .bounds(panelLeft + 12, panelTop + 8, 22, 18).build());
        addRenderableWidget(Button.builder(Component.literal(">"), button -> switchTab(1))
            .bounds(panelLeft + panelWidth - 34, panelTop + 8, 22, 18).build());
        addRenderableWidget(Button.builder(Component.literal("Atualizar"), button -> refreshAction.run())
            .bounds(panelLeft + panelWidth / 2 - 102, panelTop + panelHeight - 28, 96, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Fechar"), button -> onClose())
            .bounds(panelLeft + panelWidth / 2 + 6, panelTop + panelHeight - 28, 96, 20).build());
    }

    private void switchTab(int delta) {
        activeTab = (activeTab + delta + 2) % 2;
        scrollOffset = 0;
    }

    private List<String> currentLines() {
        return activeTab == 0 ? storageLines : buildingLines;
    }

    public void setQueueText(String content) {
        String[] parts = content.split("\\R---BUILDINGS---\\R", 2);
        storageLines = Arrays.stream(parts[0].split("\\R"))
            .filter(line -> !line.isBlank())
            .limit(64)
            .toList();
        if (storageLines.isEmpty()) storageLines = List.of("Nenhum pedido pendente para o Storager.");
        if (parts.length > 1) {
            buildingLines = Arrays.stream(parts[1].split("\\R"))
                .filter(line -> !line.isBlank())
                .limit(64)
                .toList();
        } else {
            buildingLines = List.of("Nenhuma construção ativa.");
        }
        if (buildingLines.isEmpty()) buildingLines = List.of("Nenhuma construção ativa.");
        scrollOffset = 0;
    }

    private record DisplayLine(FormattedCharSequence text, int color, boolean isHeader) {}

    private List<DisplayLine> buildWrappedLines() {
        int maxTextWidth = Math.max(120, panelWidth - 40);
        List<DisplayLine> result = new ArrayList<>();
        for (String raw : currentLines()) {
            boolean isHeader = raw.startsWith("• Builder:") || raw.startsWith("• ");
            int color = pickLineColor(raw);
            List<FormattedCharSequence> wrapped = font.split(Component.literal(raw), maxTextWidth);
            for (int i = 0; i < wrapped.size(); i++) {
                result.add(new DisplayLine(wrapped.get(i), color, i == 0 && raw.startsWith("• Builder:")));
            }
        }
        return result;
    }

    private int pickLineColor(String line) {
        if (line.startsWith("• Builder:")) return 0xFFFFD166;
        if (line.startsWith("Fase:") || line.startsWith("Progresso")) return 0xFF7DD3FC;
        if (line.startsWith("Materiais:")) return 0xFF6EE7B7;
        if (line.startsWith("• Pedido:") || line.contains("Aguardando")) return 0xFFFDE047;
        if (line.startsWith("Rotas")) return 0xFFCBD5E1;
        if (line.startsWith("• ")) return 0xFFA5F3FC;
        return 0xFFE2E8F0;
    }

    private int visibleLineCount() {
        int listHeight = Math.max(40, panelHeight - 102);
        return Math.max(1, listHeight / LINE_HEIGHT);
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Custom background is drawn directly in render() to avoid duplicate frame blur in 1.21.10
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, 0xB80B0E14);
        graphics.fill(panelLeft - 1, panelTop - 1, panelLeft + panelWidth + 1, panelTop + panelHeight + 1, 0xFF334155);
        graphics.fill(panelLeft, panelTop, panelLeft + panelWidth, panelTop + panelHeight, 0xEC161B22);
        int accent = activeTab == 0 ? 0xFF42D9E8 : 0xFFF59E0B;
        graphics.fill(panelLeft, panelTop, panelLeft + panelWidth, panelTop + 3, accent);
        graphics.fill(panelLeft, panelTop + 32, panelLeft + panelWidth, panelTop + 33, 0xFF283241);

        Component headerTitle = activeTab == 0
            ? Component.literal("Pedidos de Craft — Storager")
            : Component.literal("Fila de Construções — Builders");
        String subtitle = activeTab == 0
            ? "Pedidos abertos desta civilização:"
            : "Obras em andamento, rotas e materiais:";
        graphics.drawCenteredString(font, headerTitle, width / 2, panelTop + 13, 0xFFFFFFFF);
        graphics.drawString(font, subtitle, panelLeft + 16, panelTop + 39, 0xFF94A3B8);

        List<DisplayLine> lines = buildWrappedLines();
        int visible = visibleLineCount();
        scrollOffset = Math.min(scrollOffset, Math.max(0, lines.size() - visible));
        int listTop = panelTop + 54;
        int end = Math.min(lines.size(), scrollOffset + visible);
        for (int index = scrollOffset; index < end; index++) {
            DisplayLine dl = lines.get(index);
            int y = listTop + (index - scrollOffset) * LINE_HEIGHT;
            if (dl.isHeader()) {
                graphics.fill(panelLeft + 12, y - 2, panelLeft + panelWidth - 16, y + LINE_HEIGHT - 2, 0x552A3444);
            }
            graphics.drawString(font, dl.text(), panelLeft + 16, y, dl.color());
        }
        if (lines.size() > visible) {
            graphics.drawCenteredString(font, "Role o mouse para ver mais (" + (scrollOffset + 1) + "-" + end + " de " + lines.size() + ")",
                width / 2, panelTop + panelHeight - 43, 0xFF64748B);
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        List<DisplayLine> lines = buildWrappedLines();
        int maxOffset = Math.max(0, lines.size() - visibleLineCount());
        scrollOffset = Math.max(0, Math.min(maxOffset, scrollOffset - (int) Math.signum(scrollY)));
        return true;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
