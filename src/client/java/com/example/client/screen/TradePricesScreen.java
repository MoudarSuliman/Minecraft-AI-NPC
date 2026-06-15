package com.example.client.screen;

import com.example.ai.trade.PriceConfig;
import com.example.ai.trade.TradePriceStore;
import com.example.network.SaveTradePricesPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class TradePricesScreen extends Screen {
    private static final int ROW_HEIGHT = 22;
    private static final int LIST_TOP = 50;
    private static final int EDIT_PANEL_HEIGHT = 32;
    private static final int BUTTON_ROW_HEIGHT = 28;

    // Column offsets from panelLeft
    private static final int COL_ID   = 0;
    private static final int COL_BASE = 159;
    private static final int COL_MAX  = 203;
    private static final int COL_DISC = 247;
    private static final int COL_BTN1 = 291;
    private static final int COL_BTN2 = 350;

    private final List<PriceRow> rows = new ArrayList<>();
    private int selectedIndex = -1;

    private EditBox itemIdBox;
    private EditBox baseBox;
    private EditBox maxBox;
    private EditBox discBox;
    private Button updateRowBtn;
    private Button deleteRowBtn;

    public TradePricesScreen(Map<String, PriceConfig> prices) {
        super(Component.literal("Trade Prices"));
        for (var entry : prices.entrySet()) {
            PriceConfig cfg = entry.getValue();
            rows.add(new PriceRow(
                    entry.getKey(),
                    String.valueOf(cfg.base()),
                    String.valueOf(cfg.max()),
                    String.valueOf(cfg.discountPct())
            ));
        }
    }

    @Override
    protected void init() {
        int panelTop = height - EDIT_PANEL_HEIGHT - BUTTON_ROW_HEIGHT - 12;
        int panelLeft = width / 2 - 200;

        itemIdBox = new EditBox(font, panelLeft + COL_ID, panelTop + 4, 155, 20, Component.literal("Item ID"));
        itemIdBox.setMaxLength(128);
        itemIdBox.setHint(Component.literal("minecraft:oak_log"));
        addRenderableWidget(itemIdBox);

        baseBox = new EditBox(font, panelLeft + COL_BASE, panelTop + 4, 40, 20, Component.literal("Base"));
        baseBox.setMaxLength(5);
        baseBox.setHint(Component.literal("2"));
        addRenderableWidget(baseBox);

        maxBox = new EditBox(font, panelLeft + COL_MAX, panelTop + 4, 40, 20, Component.literal("Max"));
        maxBox.setMaxLength(5);
        maxBox.setHint(Component.literal("4"));
        addRenderableWidget(maxBox);

        discBox = new EditBox(font, panelLeft + COL_DISC, panelTop + 4, 40, 20, Component.literal("Disc%"));
        discBox.setMaxLength(3);
        discBox.setHint(Component.literal("15"));
        addRenderableWidget(discBox);

        updateRowBtn = addRenderableWidget(Button.builder(
                Component.literal("Update"), btn -> applyRowEdit())
                .pos(panelLeft + COL_BTN1, panelTop + 4)
                .size(55, 20)
                .build());

        deleteRowBtn = addRenderableWidget(Button.builder(
                Component.literal("Delete"), btn -> deleteSelectedRow())
                .pos(panelLeft + COL_BTN2, panelTop + 4)
                .size(55, 20)
                .build());

        int btnY = height - BUTTON_ROW_HEIGHT;
        addRenderableWidget(Button.builder(
                Component.literal("Add Row"), btn -> addNewRow())
                .pos(panelLeft, btnY)
                .size(80, 20)
                .build());
        addRenderableWidget(Button.builder(
                Component.literal("Save"), btn -> saveAndClose())
                .pos(panelLeft + 165, btnY)
                .size(80, 20)
                .build());
        addRenderableWidget(Button.builder(
                Component.literal("Cancel"), btn -> onClose())
                .pos(panelLeft + 249, btnY)
                .size(80, 20)
                .build());

        refreshEditPanel();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor gfx, int mouseX, int mouseY, float delta) {
        extractMenuBackground(gfx);
        gfx.centeredText(font, title, width / 2, 14, 0xFFFFFFFF);

        int panelLeft = width / 2 - 200;

        // Column headers
        gfx.text(font, "Item ID",  panelLeft + COL_ID,   LIST_TOP - 14, 0xFFAAAAAA);
        gfx.text(font, "Base",     panelLeft + COL_BASE,  LIST_TOP - 14, 0xFFAAAAAA);
        gfx.text(font, "Max",      panelLeft + COL_MAX,   LIST_TOP - 14, 0xFFAAAAAA);
        gfx.text(font, "Disc%",    panelLeft + COL_DISC,  LIST_TOP - 14, 0xFFAAAAAA);

        int listBottom = height - EDIT_PANEL_HEIGHT - BUTTON_ROW_HEIGHT - 24;
        gfx.enableScissor(0, LIST_TOP, width, listBottom);
        for (int i = 0; i < rows.size(); i++) {
            int rowY = LIST_TOP + i * ROW_HEIGHT;
            if (rowY + ROW_HEIGHT < LIST_TOP || rowY > listBottom) continue;
            boolean selected = i == selectedIndex;
            if (selected) {
                gfx.fill(panelLeft - 2, rowY - 1, panelLeft + 410, rowY + ROW_HEIGHT - 3, 0x44FFFFFF);
            }
            int color = selected ? 0xFFFFFF55 : 0xFFDDDDDD;
            PriceRow row = rows.get(i);
            gfx.text(font, row.itemId,   panelLeft + COL_ID,   rowY + 4, color);
            gfx.text(font, row.base,     panelLeft + COL_BASE,  rowY + 4, color);
            gfx.text(font, row.max,      panelLeft + COL_MAX,   rowY + 4, color);
            gfx.text(font, row.discount, panelLeft + COL_DISC,  rowY + 4, color);
        }
        gfx.disableScissor();

        int divY = height - EDIT_PANEL_HEIGHT - BUTTON_ROW_HEIGHT - 12;
        gfx.fill(panelLeft - 5, divY, panelLeft + 415, divY + 1, 0x66FFFFFF);
        gfx.fill(panelLeft - 5, height - BUTTON_ROW_HEIGHT - 4, panelLeft + 415, height - BUTTON_ROW_HEIGHT - 3, 0x66FFFFFF);

        int panelTop = height - EDIT_PANEL_HEIGHT - BUTTON_ROW_HEIGHT - 12;
        String editLabel = selectedIndex >= 0 ? "Editing row " + (selectedIndex + 1) : "Select a row or add a new one";
        gfx.text(font, editLabel, panelLeft, panelTop - 11, 0xFFAAAAAA);

        if (rows.isEmpty()) {
            gfx.centeredText(font, Component.literal("No prices configured. Press 'Add Row'."), width / 2, LIST_TOP + 20, 0xFF888888);
        }

        super.extractRenderState(gfx, mouseX, mouseY, delta);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean consumed) {
        if (consumed) return super.mouseClicked(event, true);
        int panelLeft = width / 2 - 200;
        int listBottom = height - EDIT_PANEL_HEIGHT - BUTTON_ROW_HEIGHT - 24;
        double mx = event.x();
        double my = event.y();
        if (mx >= panelLeft - 2 && mx <= panelLeft + 412 && my >= LIST_TOP && my <= listBottom) {
            int clicked = (int) ((my - LIST_TOP) / ROW_HEIGHT);
            if (clicked >= 0 && clicked < rows.size()) {
                selectedIndex = clicked;
                populateEditPanel();
                return true;
            }
        }
        return super.mouseClicked(event, consumed);
    }

    private void populateEditPanel() {
        if (selectedIndex >= 0 && selectedIndex < rows.size()) {
            PriceRow row = rows.get(selectedIndex);
            itemIdBox.setValue(row.itemId);
            baseBox.setValue(row.base);
            maxBox.setValue(row.max);
            discBox.setValue(row.discount);
        }
        refreshEditPanel();
    }

    private void refreshEditPanel() {
        boolean hasSelection = selectedIndex >= 0 && selectedIndex < rows.size();
        if (updateRowBtn != null) updateRowBtn.active = hasSelection;
        if (deleteRowBtn != null) deleteRowBtn.active = hasSelection;
    }

    private void applyRowEdit() {
        if (selectedIndex < 0 || selectedIndex >= rows.size()) return;
        String itemId = itemIdBox.getValue().trim();
        if (itemId.isBlank()) return;
        int base = parsePositiveInt(baseBox.getValue(), 2);
        int max  = parsePositiveInt(maxBox.getValue(), Math.max(base, base * 2));
        int disc = Math.max(0, Math.min(50, parsePositiveInt(discBox.getValue(), 15)));
        rows.set(selectedIndex, new PriceRow(itemId, String.valueOf(base), String.valueOf(Math.max(base, max)), String.valueOf(disc)));
    }

    private void deleteSelectedRow() {
        if (selectedIndex < 0 || selectedIndex >= rows.size()) return;
        rows.remove(selectedIndex);
        selectedIndex = Math.min(selectedIndex, rows.size() - 1);
        if (selectedIndex >= 0) populateEditPanel();
        else {
            itemIdBox.setValue("");
            baseBox.setValue("");
            maxBox.setValue("");
            discBox.setValue("");
        }
        refreshEditPanel();
    }

    private void addNewRow() {
        rows.add(new PriceRow("", "2", "4", "15"));
        selectedIndex = rows.size() - 1;
        itemIdBox.setValue("");
        baseBox.setValue("2");
        maxBox.setValue("4");
        discBox.setValue("15");
        itemIdBox.setFocused(true);
        refreshEditPanel();
    }

    private void saveAndClose() {
        // Flush any in-progress edit
        String editId = itemIdBox.getValue().trim();
        if (selectedIndex >= 0 && selectedIndex < rows.size()) {
            applyRowEdit();
        } else if (!editId.isBlank()) {
            int base = parsePositiveInt(baseBox.getValue(), 2);
            int max  = parsePositiveInt(maxBox.getValue(), base * 2);
            int disc = Math.max(0, Math.min(50, parsePositiveInt(discBox.getValue(), 15)));
            rows.add(new PriceRow(editId, String.valueOf(base), String.valueOf(Math.max(base, max)), String.valueOf(disc)));
        }

        Map<String, PriceConfig> configs = new LinkedHashMap<>();
        for (PriceRow row : rows) {
            String id = row.itemId.trim();
            if (id.isBlank()) continue;
            int base = parsePositiveInt(row.base, 2);
            int max  = parsePositiveInt(row.max, base * 2);
            int disc = Math.max(0, Math.min(50, parsePositiveInt(row.discount, 15)));
            configs.put(id, new PriceConfig(base, Math.max(base, max), disc));
        }

        ClientPlayNetworking.send(new SaveTradePricesPayload(TradePriceStore.toJson(configs)));
        onClose();
    }

    private static int parsePositiveInt(String s, int fallback) {
        try {
            int v = Integer.parseInt(s.trim());
            return v > 0 ? v : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private record PriceRow(String itemId, String base, String max, String discount) {}
}
