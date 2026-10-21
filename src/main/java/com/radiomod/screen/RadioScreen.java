package com.radiomod.screen;

import com.radiomod.network.ModPayloads;
import com.radiomod.radio.RadioPlayer;
import com.radiomod.radio.RadioStation;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.List;

@OnlyIn(Dist.CLIENT)
public class RadioScreen extends AbstractContainerScreen<RadioMenu> {

    // ── GUI size ─────────────────────────────────────────────────────
    private static final int GUI_W = 256;
    private static final int GUI_H = 248;

    // ── Colour palette ───────────────────────────────────────────────
    private static final int COL_BG = 0xFF1E1B14;
    private static final int COL_BG_BORDER = 0xFF4A4230;
    private static final int COL_PANEL = 0xFF2D2920;
    private static final int COL_PANEL_HL = 0xFF3A3528;
    private static final int COL_TITLE_BG = 0xFF8B7732;
    private static final int COL_TITLE_BG2 = 0xFF6B5A22;
    private static final int COL_ACCENT = 0xFFB26430;
    private static final int COL_SELECTED = 0xFF6E3A12;
    private static final int COL_TEXT = 0xFFE8DCC8;
    private static final int COL_TEXT_DIM = 0xFF8A7E60;
    private static final int COL_TEXT_TITLE = 0xFFFFF2D6;
    private static final int COL_GREEN = 0xFF32C83E;
    private static final int COL_GREEN_DK = 0xFF1E7A26;
    private static final int COL_BTN_BG = 0xFF3E3828;
    private static final int COL_BTN_BORDER = 0xFF695A20;
    private static final int COL_DIVIDER = 0xFF4A4230;
    private static final int COL_TUNER_BG = 0xFF2A2518;
    private static final int COL_TUNER_EDGE = 0xFF5A4E2A;

    // ── Layout constants (all distances from leftPos / topPos) ───────
    // Horizontal
    private static final int MARGIN = 8;   // standard left/right margin from GUI edge
    private static final int SCROLL_W = 8;   // scrollbar track width
    private static final int SCROLL_GAP = 2;   // gap between list right edge and scrollbar
    // list inner content width so that: MARGIN + LIST_W + SCROLL_GAP + SCROLL_W + MARGIN == GUI_W
    private static final int LIST_W = GUI_W - 2 * MARGIN - SCROLL_GAP - SCROLL_W; // 230
    // Derived x offsets (relative to leftPos):
    private static final int LIST_X_REL = MARGIN;                                     // 8
    private static final int SCROLL_X_REL = MARGIN + LIST_W + SCROLL_GAP;              // 240

    // Vertical (relative to topPos):
    private static final int TITLE_Y = 4;
    private static final int TITLE_H = 24;   // two rows (title + subtitle)
    private static final int TUNER_Y = TITLE_Y + TITLE_H + 2;   // 30
    private static final int TUNER_H = 16;
    private static final int LIST_Y = TUNER_Y + TUNER_H + 3;   // 49
    private static final int STATION_H = 20;
    private static final int STATIONS_VIS = 7;
    private static final int LIST_H = STATIONS_VIS * STATION_H; // 140
    // Volume section must be below LIST_Y + LIST_H = 189, so we can safely place it at 194 with some gap.
    private static final int VOL_Y = LIST_Y + LIST_H + 5;     // 194
    // Minimal HEIGHT check: VOL_Y + 50 (label+buttons+bar) = 244 < 248 ✓

    // ── Tuner selector ───────────────────────────────────────────────
    private static final int TUNER_LABEL_W = 52;
    private static final int TUNER_ARROW_W = 14;
    private static final int TUNER_NAME_W = LIST_W - TUNER_LABEL_W - TUNER_ARROW_W * 2;

    // ── Marquee ──────────────────────────────────────────────────────
    private int marqueeOffset = 0;
    private long marqueeLastTick = 0;
    private static final int MARQUEE_DELAY_MS = 2000;
    private static final int MARQUEE_SPEED_MS = 60;

    // ── State ────────────────────────────────────────────────────────
    private int scrollOffset = 0;
    private int selectedCityIndex = 0;
    private boolean draggingVolume = false;

    // ─────────────────────────────────────────────────────────────────

    public RadioScreen(RadioMenu menu, Inventory inv, Component title) {
        super(menu, inv, title);
        this.imageWidth = GUI_W;
        this.imageHeight = GUI_H;
    }

    @Override
    public void init() {
        super.init();
    }

    private List<RadioStation> getCurrentStations() {
        return RadioStation.COUNTRIES.get(selectedCityIndex).getStations();
    }

    private void sendVol(int delta) {
        String key = menu.getRadioKey();
        int v = Math.max(0, Math.min(100, RadioPlayer.getVolume(key) + delta));
        RadioPlayer.setVolume(key, v);
        // Volume is per-radio and purely client-side
    }

    private void scroll(int dir) {
        int max = Math.max(0, getCurrentStations().size() - STATIONS_VIS);
        scrollOffset = Math.max(0, Math.min(max, scrollOffset + dir));
    }

    private void cycleCountry(int dir) {
        int n = RadioStation.COUNTRIES.size();
        selectedCityIndex = (selectedCityIndex + dir + n) % n;
        scrollOffset = 0;
        marqueeOffset = 0;
        marqueeLastTick = System.currentTimeMillis() + MARQUEE_DELAY_MS;
    }

    // ── Rendering ────────────────────────────────────────────────────

    @Override
    public void render(GuiGraphics gfx, int mouseX, int mouseY, float partial) {
        super.render(gfx, mouseX, mouseY, partial);
        tickMarquee();
        drawFrame(gfx);
        drawTitleBar(gfx);
        drawTunerBar(gfx, mouseX, mouseY);
        drawStationList(gfx, mouseX, mouseY);
        drawScrollIndicator(gfx, mouseX, mouseY);
        drawVolumeSection(gfx, mouseX, mouseY);
    }

    private void tickMarquee() {
        long now = System.currentTimeMillis();
        if (marqueeLastTick == 0) marqueeLastTick = now + MARQUEE_DELAY_MS;
        if (now - marqueeLastTick >= MARQUEE_SPEED_MS) {
            String name = I18n.get(RadioStation.COUNTRIES.get(selectedCityIndex).getLangKey());
            int tw = font.width(name);
            if (tw > TUNER_NAME_W) {
                if (++marqueeOffset > tw + 10) marqueeOffset = 0;
            } else {
                marqueeOffset = 0;
            }
            marqueeLastTick = now;
        }
    }

    private void drawFrame(GuiGraphics gfx) {
        int x = leftPos, y = topPos;
        gfx.fill(x - 2, y - 2, x + GUI_W + 2, y + GUI_H + 2, COL_BG_BORDER);
        gfx.fill(x - 1, y - 1, x + GUI_W + 1, y + GUI_H + 1, COL_ACCENT);
        gfx.fill(x, y, x + GUI_W, y + GUI_H, COL_BG);
        int r = 0xFFB49A40;
        gfx.fill(x, y, x + 3, y + 3, r);
        gfx.fill(x + GUI_W - 3, y, x + GUI_W, y + 3, r);
        gfx.fill(x, y + GUI_H - 3, x + 3, y + GUI_H, r);
        gfx.fill(x + GUI_W - 3, y + GUI_H - 3, x + GUI_W, y + GUI_H, r);
    }

    private void drawTitleBar(GuiGraphics gfx) {
        int x = leftPos + MARGIN, y = topPos + TITLE_Y;
        int w = GUI_W - 2 * MARGIN;                           // same width as list+scrollbar area
        gfx.fill(x, y, x + w, y + 13, COL_TITLE_BG);
        gfx.fill(x, y + 13, x + w, y + 24, COL_TITLE_BG2);
        gfx.fill(x, y, x + w, y + 1, 0xFFAA9840);
        gfx.drawCenteredString(font, I18n.get("gui.radiomod.title"),
                leftPos + GUI_W / 2, y + 3, COL_TEXT_TITLE);
        gfx.drawCenteredString(font, I18n.get("gui.radiomod.subtitle"),
                leftPos + GUI_W / 2, y + 14, 0xFFD4C090);
    }

    private void drawTunerBar(GuiGraphics gfx, int mouseX, int mouseY) {
        int cy = topPos + TUNER_Y;
        int cx = leftPos + MARGIN;
        int cw = GUI_W - 2 * MARGIN;   // same total width as title bar

        gfx.fill(cx, cy, cx + cw, cy + TUNER_H, COL_TUNER_BG);
        gfx.fill(cx, cy, cx + cw, cy + 1, COL_TUNER_EDGE);
        gfx.fill(cx, cy + TUNER_H - 1, cx + cw, cy + TUNER_H, COL_TUNER_EDGE);

        // Label
        gfx.drawString(font, I18n.get("gui.radiomod.tuner") + ":",
                cx + 4, cy + 4, COL_TEXT_DIM, false);

        // Fixed arrow positions
        int arrowLX = cx + TUNER_LABEL_W;
        int nameBoxX = arrowLX + TUNER_ARROW_W;
        int arrowRX = nameBoxX + TUNER_NAME_W;

        boolean hL = mouseX >= arrowLX && mouseX < arrowLX + TUNER_ARROW_W
                && mouseY >= cy + 2 && mouseY < cy + TUNER_H - 2;
        boolean hR = mouseX >= arrowRX && mouseX < arrowRX + TUNER_ARROW_W
                && mouseY >= cy + 2 && mouseY < cy + TUNER_H - 2;

        gfx.drawCenteredString(font, "<", arrowLX + TUNER_ARROW_W / 2, cy + 4, hL ? COL_ACCENT : COL_BTN_BORDER);
        gfx.drawCenteredString(font, ">", arrowRX + TUNER_ARROW_W / 2, cy + 4, hR ? COL_ACCENT : COL_BTN_BORDER);

        // Country name with marquee if too long
        String name = I18n.get(RadioStation.COUNTRIES.get(selectedCityIndex).getLangKey());
        int tw = font.width(name);
        gfx.enableScissor(nameBoxX, cy, nameBoxX + TUNER_NAME_W, cy + TUNER_H);
        int drawX = tw <= TUNER_NAME_W
                ? nameBoxX + (TUNER_NAME_W - tw) / 2
                : nameBoxX - marqueeOffset;
        gfx.drawString(font, name, drawX, cy + 4, COL_TEXT, false);
        gfx.disableScissor();
    }

    private void drawStationList(GuiGraphics gfx, int mouseX, int mouseY) {
        int lx = leftPos + LIST_X_REL;
        int ly = topPos + LIST_Y;
        String active = menu.blockEntity != null ? menu.blockEntity.getCurrentStation() : "off";
        List<RadioStation> stations = getCurrentStations();
        // 1-px border + background — border stays within [lx-1 .. lx+LIST_W] x [ly-1 .. ly+LIST_H]
        gfx.fill(lx - 1, ly - 1, lx + LIST_W + 1, ly + LIST_H + 1, COL_DIVIDER);
        gfx.fill(lx, ly, lx + LIST_W, ly + LIST_H, COL_BG);

        // Loading indicator
        RadioStation.Country cur = RadioStation.COUNTRIES.get(selectedCityIndex);
        if (!cur.isFetched() && stations.size() <= 2) {
            gfx.drawCenteredString(font, I18n.get("gui.radiomod.loading"),
                    lx + LIST_W / 2, ly + LIST_H / 2 - 4, COL_TEXT_DIM);
            return;
        }
        // Custom tab with no user stations yet (issues #2/#6) — show where to add them.
        if (cur.isCustom() && stations.size() <= 1) {
            gfx.drawCenteredString(font, I18n.get("gui.radiomod.custom.empty"),
                    lx + LIST_W / 2, ly + LIST_H / 2 - 9, COL_TEXT_DIM);
            gfx.drawCenteredString(font, I18n.get("gui.radiomod.custom.hint"),
                    lx + LIST_W / 2, ly + LIST_H / 2 + 3, COL_TEXT_DIM);
        }

        for (int i = 0; i < STATIONS_VIS && (scrollOffset + i) < stations.size(); i++) {
            RadioStation st = stations.get(scrollOffset + i);
            int y = ly + i * STATION_H;
            boolean sel = st.getId().equals(active);
            boolean hov = mouseX >= lx && mouseX < lx + LIST_W
                    && mouseY >= y && mouseY < y + STATION_H - 1;

            gfx.fill(lx, y, lx + LIST_W, y + STATION_H - 1,
                    sel ? COL_SELECTED : (hov ? COL_PANEL_HL : COL_PANEL));

            if (sel) gfx.fill(lx, y, lx + 2, y + STATION_H - 1, COL_GREEN);

            String dn = st.getId().equals("off")
                    ? I18n.get("gui.radiomod.station_off")
                    : st.getDisplayName();
            String label = sel ? "> " + dn : "  " + dn;
            gfx.drawString(font, label, lx + 6, y + 5,
                    sel ? COL_GREEN : (hov ? COL_TEXT : COL_TEXT_DIM), false);

            if (i < STATIONS_VIS - 1 && (scrollOffset + i + 1) < stations.size())
                gfx.fill(lx + 2, y + STATION_H - 1, lx + LIST_W - 2, y + STATION_H, 0xFF2A2620);
        }
    }

    private void drawScrollIndicator(GuiGraphics gfx, int mouseX, int mouseY) {
        List<RadioStation> stations = getCurrentStations();
        int maxScroll = Math.max(0, stations.size() - STATIONS_VIS);
        if (maxScroll <= 0) return;

        int sx = leftPos + SCROLL_X_REL;
        int sy = topPos + LIST_Y;

        // Track — exactly the same height as the list (no overflow)
        gfx.fill(sx, sy, sx + SCROLL_W, sy + LIST_H, COL_PANEL);

        int thumbH = Math.max(10, LIST_H * STATIONS_VIS / stations.size());
        int thumbY = sy + (int) ((LIST_H - thumbH) * scrollOffset / (float) maxScroll);
        gfx.fill(sx + 1, thumbY, sx + SCROLL_W - 1, thumbY + thumbH, COL_BTN_BORDER);
        gfx.fill(sx + 2, thumbY + 1, sx + SCROLL_W - 2, thumbY + thumbH - 1, COL_ACCENT);

        gfx.drawCenteredString(font, "^", sx + SCROLL_W / 2, sy + 2,
                scrollOffset > 0 ? COL_TEXT : COL_TEXT_DIM);
        gfx.drawCenteredString(font, "v", sx + SCROLL_W / 2, sy + LIST_H - 10,
                scrollOffset < maxScroll ? COL_TEXT : COL_TEXT_DIM);
    }

    private void drawVolumeSection(GuiGraphics gfx, int mouseX, int mouseY) {
        int vol = RadioPlayer.getVolume(menu.getRadioKey());
        int secX = leftPos + LIST_X_REL;            // 8     — same left edge as list
        int secY = topPos + VOL_Y;                 // 194   — safely below list
        int secW = LIST_W;                           // 230   — same width as list, stops before scrollbar

        // Divider above volume
        gfx.fill(secX, secY, secX + secW, secY + 1, COL_DIVIDER);

        // Volume label
        gfx.drawString(font, I18n.get("gui.radiomod.volume", vol),
                secX + 2, secY + 8, COL_TEXT_DIM, false);

        // Buttons — centred within secW
        // Total button group: 32+4+24+4+24+4+32 = 124 px
        int btnGroupW = 32 + 4 + 24 + 4 + 24 + 4 + 32;
        int btnX0 = secX + (secW - btnGroupW) / 2;
        int btnY = secY + 22;
        int b1x = btnX0, b2x = b1x + 36, b3x = b2x + 28, b4x = b3x + 28;
        drawStyledButton(gfx, b1x, btnY, 32, 14, "-10", mouseX, mouseY);
        drawStyledButton(gfx, b2x, btnY, 24, 14, "-1", mouseX, mouseY);
        drawStyledButton(gfx, b3x, btnY, 24, 14, "+1", mouseX, mouseY);
        drawStyledButton(gfx, b4x, btnY, 32, 14, "+10", mouseX, mouseY);

        // Volume bar
        int barX = secX, barY = secY + 42, barH = 8;
        gfx.fill(barX - 1, barY - 1, barX + secW + 1, barY + barH + 1, COL_DIVIDER);
        gfx.fill(barX, barY, barX + secW, barY + barH, 0xFF181610);
        int fw = (int) (secW * vol / 100.0);
        if (fw > 0) {
            gfx.fill(barX, barY, barX + fw, barY + barH, COL_GREEN_DK);
            gfx.fill(barX, barY + 1, barX + fw, barY + barH - 1, COL_GREEN);
            if (fw > 2) gfx.fill(barX + fw - 2, barY, barX + fw, barY + barH, 0xFF50FF60);
        }
    }

    private void drawStyledButton(GuiGraphics gfx, int x, int y, int w, int h,
                                  String label, int mx, int my) {
        boolean hov = mx >= x && mx < x + w && my >= y && my < y + h;
        gfx.fill(x - 1, y - 1, x + w + 1, y + h + 1, hov ? COL_ACCENT : COL_BTN_BORDER);
        gfx.fill(x, y, x + w, y + h, hov ? COL_BTN_BORDER : COL_BTN_BG);
        gfx.fill(x, y, x + w, y + 1, hov ? 0xFF9A8430 : 0xFF504828);
        gfx.drawCenteredString(font, label, x + w / 2, y + 3, hov ? COL_TEXT : COL_TEXT_DIM);
    }

    // ── Mouse ─────────────────────────────────────────────────────────

    /**
     * Returns the volume (0-100) corresponding to a mouse X inside the volume bar.
     */
    private int volFromMouseX(double mx) {
        int barX = leftPos + LIST_X_REL;
        int barW = LIST_W;
        double ratio = (mx - barX) / (double) barW;
        return (int) Math.round(Math.max(0, Math.min(1, ratio)) * 100);
    }

    /**
     * True if the mouse is over the volume bar area.
     */
    private boolean isOnVolBar(double mx, double my) {
        int barX = leftPos + LIST_X_REL;
        int barY = topPos + VOL_Y + 42;
        return mx >= barX && mx < barX + LIST_W && my >= barY - 4 && my < barY + 8 + 4;
    }

    @Override
    public boolean mouseClicked(double mx, double my, int btn) {
        // Tuner arrows
        int cy = topPos + TUNER_Y;
        int cx = leftPos + MARGIN;
        int arrowLX = cx + TUNER_LABEL_W;
        int nameBoxX = arrowLX + TUNER_ARROW_W;
        int arrowRX = nameBoxX + TUNER_NAME_W;
        if (my >= cy + 2 && my < cy + TUNER_H - 2) {
            if (mx >= arrowLX && mx < arrowLX + TUNER_ARROW_W) {
                cycleCountry(-1);
                return true;
            }
            if (mx >= arrowRX && mx < arrowRX + TUNER_ARROW_W) {
                cycleCountry(+1);
                return true;
            }
        }

        // Station list
        int lx = leftPos + LIST_X_REL, ly = topPos + LIST_Y;
        List<RadioStation> stations = getCurrentStations();
        for (int i = 0; i < STATIONS_VIS && (scrollOffset + i) < stations.size(); i++) {
            int y = ly + i * STATION_H;
            if (mx >= lx && mx < lx + LIST_W && my >= y && my < y + STATION_H - 1) {
                RadioStation selected = stations.get(scrollOffset + i);
                String id = selected.getId();
                String url = selected.getStreamUrl();
                // Pass radioKey so server can locate this radio even inside a Sable SubLevel
                // or a Create contraption/train (issue #4).
                // Pass streamUrl so all clients receive it directly — no per-client API lookup needed.
                String key = menu.getRadioKey();
                BlockPos pos = menu.getRadioPos();
                ModPayloads.sendSelectStation(pos, id, key, url);
                if (menu.blockEntity != null) menu.blockEntity.setCurrentStation(id);
                return true;
            }
        }

        // Volume buttons
        int vol = RadioPlayer.getVolume(menu.getRadioKey());
        int secX = leftPos + LIST_X_REL;
        int btnGroupW = 32 + 4 + 24 + 4 + 24 + 4 + 32;
        int btnX0 = secX + (LIST_W - btnGroupW) / 2;
        int btnY = topPos + VOL_Y + 22;
        if (isIn(mx, my, btnX0, btnY, 32, 14)) {
            sendVol(-10);
            return true;
        }
        if (isIn(mx, my, btnX0 + 36, btnY, 24, 14)) {
            sendVol(-1);
            return true;
        }
        if (isIn(mx, my, btnX0 + 64, btnY, 24, 14)) {
            sendVol(+1);
            return true;
        }
        if (isIn(mx, my, btnX0 + 92, btnY, 32, 14)) {
            sendVol(+10);
            return true;
        }

        // Scrollbar
        int sx = leftPos + SCROLL_X_REL, sy = topPos + LIST_Y;
        if (mx >= sx && mx < sx + SCROLL_W && my >= sy && my < sy + LIST_H) {
            scroll(my < sy + LIST_H / 2 ? -1 : +1);
            return true;
        }
        // Volume bar – click to set
        if (isOnVolBar(mx, my)) {
            draggingVolume = true;
            sendVol(volFromMouseX(mx) - RadioPlayer.getVolume(menu.getRadioKey()));
            return true;
        }

        return super.mouseClicked(mx, my, btn);
    }

    @Override
    public boolean mouseDragged(double mx, double my, int btn, double dx, double dy) {
        if (draggingVolume) {
            sendVol(volFromMouseX(mx) - RadioPlayer.getVolume(menu.getRadioKey()));
            return true;
        }
        return super.mouseDragged(mx, my, btn, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int btn) {
        draggingVolume = false;
        return super.mouseReleased(mx, my, btn);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double scrollX, double scrollY) {
        scroll(scrollY < 0 ? 1 : -1);
        return true;
    }

    private boolean isIn(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    @Override
    protected void renderLabels(GuiGraphics g, int mx, int my) {
    }

    @Override
    protected void renderBg(GuiGraphics g, float p, int mx, int my) {
    }
}
