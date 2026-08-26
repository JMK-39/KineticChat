package dev.xyat.kineticchat.chat.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.GuiMessage;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.NotNull;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public class ChatCopyCanvasScreen extends Screen {
    private final List<CanvasLine> lines = new ArrayList<>();
    private float guiScale;
    private int vWidth, vHeight;

    private int scrollPos = 0;
    private int startLine = -1, startCol = -1;
    private int endLine = -1, endCol = -1;
    private boolean isDraggingText = false;
    private boolean isDraggingScrollbar = false;

    private long lastClickTime = 0;
    private int lastClickLine = -1;
    private int lastClickCol = -1;

    private boolean firstInit = true;

    private EditBox searchBox;
    private final List<SearchMatch> matches = new ArrayList<>();
    private int currentMatchIdx = -1;
    private String lastSearchQuery = "";
    private long flashStartTime = 0;

    private String toastText = "";
    private long toastEndTime = 0;
    private boolean showCopyMenu = false;
    private double menuX, menuY;

    private static final int FRAME_W = 360;
    private static final int SCROLLBAR_WIDTH = 6;
    private static final int SCROLLBAR_MARGIN = 2;
    private static final int LINE_H = 10;
    private static final int MARGIN = 2;
    private static final int PADDING = 2;

    private static final int INNER_PADDING = 2;

    private static final int BOTTOM_EXPAND = 30;
    private static final int GOLDEN_COLOR = 0xFFFFD700;
    private static final int SEARCH_HIGHLIGHT = 0x88FFFF00;

    public ChatCopyCanvasScreen(List<GuiMessage.Line> chatHistory) {
        super(Component.translatable("gui.kineticchat.canvas_title"));
        List<GuiMessage.Line> reversed = new ArrayList<>(chatHistory);
        Collections.reverse(reversed);
        for (GuiMessage.Line line : reversed) {
            this.lines.add(new CanvasLine(line));
        }
    }

    private int getMaxScroll() {
        int cH = vHeight - 75 + BOTTOM_EXPAND;
        int totalH = lines.size() * LINE_H;
        int visibleInnerH = Math.max(0, cH - INNER_PADDING * 2);
        int visibleLines = visibleInnerH / LINE_H;
        int visiblePixels = visibleLines * LINE_H;
        return Math.max(0, totalH - visiblePixels);
    }

    @Override
    protected void init() {
        float vTargetW = 640f;
        float scaleX = (float) this.width / vTargetW;
        float vTargetH = 360f;
        float scaleY = (float) this.height / vTargetH;
        this.guiScale = Math.max(1.0f, Math.min(scaleX, scaleY));
        this.vWidth = (int) (this.width / guiScale);
        this.vHeight = (int) (this.height / guiScale);

        if (this.firstInit) {
            this.scrollPos = this.getMaxScroll();
            this.firstInit = false;
        }

        int cX = (vWidth - FRAME_W) / 2;
        int cY = 25;

        this.searchBox = new EditBox(this.font, (int)((cX + 2) * guiScale), (int)((cY - 18) * guiScale), (int)(120 * guiScale), (int)(12 * guiScale), Component.translatable("gui.kineticchat.search"));
        this.searchBox.setResponder(this::onSearchChanged);
        this.addRenderableWidget(this.searchBox);

        this.addRenderableWidget(Button.builder(Component.translatable("gui.kineticchat.previous"), b -> navigateMatch(-1))
                .bounds((int)((cX + 125) * guiScale), (int)((cY - 18) * guiScale), (int)(15 * guiScale), (int)(12 * guiScale))
                .build());
        this.addRenderableWidget(Button.builder(Component.translatable("gui.kineticchat.next"), b -> navigateMatch(1))
                .bounds((int)((cX + 142) * guiScale), (int)((cY - 18) * guiScale), (int)(15 * guiScale), (int)(12 * guiScale))
                .build());

        this.addRenderableWidget(Button.builder(
                        Component.translatable("gui.kineticchat.chat.back"),
                        b -> this.onClose())
                .bounds((int)((cX + FRAME_W - 50) * guiScale), (int)((cY - 18) * guiScale), (int)(50 * guiScale), (int)(14 * guiScale))
                .tooltip(Tooltip.create(Component.translatable("gui.kineticchat.chat.back.desc")))
                .build());
    }

    private void onSearchChanged(String query) {
        this.matches.clear();
        this.currentMatchIdx = -1;
        this.lastSearchQuery = query.toLowerCase(Locale.ROOT);

        if (!lastSearchQuery.isEmpty()) {
            for (int i = 0; i < lines.size(); i++) {
                String text = lines.get(i).rawText.toLowerCase(Locale.ROOT);
                int index = text.indexOf(lastSearchQuery);
                while (index >= 0) {
                    matches.add(new SearchMatch(i, index, index + lastSearchQuery.length()));
                    index = text.indexOf(lastSearchQuery, index + 1);
                }
            }
            if (!matches.isEmpty()) {
                currentMatchIdx = matches.size() - 1;
                flashStartTime = System.currentTimeMillis();
                scrollToMatch(matches.get(currentMatchIdx));
            }
        }
    }

    private void navigateMatch(int direction) {
        if (matches.isEmpty()) return;
        currentMatchIdx = (currentMatchIdx + direction + matches.size()) % matches.size();
        flashStartTime = System.currentTimeMillis();
        scrollToMatch(matches.get(currentMatchIdx));
    }

    private void scrollToMatch(SearchMatch match) {
        int cH = vHeight - 75 + BOTTOM_EXPAND;
        int visibleInnerH = Math.max(0, cH - INNER_PADDING * 2);
        int visiblePixels = (visibleInnerH / LINE_H) * LINE_H;
        int targetY = (match.lineIdx * LINE_H);

        if (targetY < scrollPos || targetY > scrollPos + visiblePixels - LINE_H) {
            scrollPos = Mth.clamp(targetY - visiblePixels / 2, 0, getMaxScroll());

            scrollPos = (scrollPos / LINE_H) * LINE_H;
        }
    }

    @Override
    public void render(@NotNull GuiGraphics g, int mx, int my, float pt) {
        this.renderBackground(g);
        g.fill(0, 0, this.width, this.height, 0xCC000000);

        g.pose().pushPose();
        g.pose().scale(guiScale, guiScale, 1.0f);

        int cX = (vWidth - FRAME_W) / 2;
        int cY = 25;
        int cH = vHeight - 75 + BOTTOM_EXPAND;
        int gutter = SCROLLBAR_WIDTH + SCROLLBAR_MARGIN + 2;
        int innerY = cY + INNER_PADDING;
        int innerH = Math.max(0, cH - INNER_PADDING * 2);
        int visibleLines = innerH / LINE_H;
        int visiblePixels = visibleLines * LINE_H;
        long now = System.currentTimeMillis();

        if (!lastSearchQuery.isEmpty()) {
            String countText = (matches.isEmpty() ? 0 : currentMatchIdx + 1) + "/" + matches.size();
            g.drawString(this.font, countText, cX + 162, cY - 16, 0xFFAAAAAA, false);
        }

        g.fill(cX, cY, cX + FRAME_W, cY + cH, 0xEE000000);
        drawOutwardBorder(g, cX, cY, cH);

        enableVirtualScissor(g, cX + INNER_PADDING, innerY, cX + FRAME_W - gutter - INNER_PADDING, innerY + visiblePixels);
        for (int i = 0; i < lines.size(); i++) {
            int lineY = innerY + (i * LINE_H) - scrollPos;

            if (lineY + LINE_H <= innerY || lineY >= innerY + visiblePixels) continue;

            CanvasLine line = lines.get(i);

            for (SearchMatch m : matches) {
                if (m.lineIdx == i) {
                    int xStart = cX + PADDING + line.getOffset(m.startCol);
                    int xEnd = cX + PADDING + line.getOffset(m.endCol);
                    boolean isCurrent = (matches.indexOf(m) == currentMatchIdx);

                    int boxColor = SEARCH_HIGHLIGHT;
                    if (isCurrent) {
                        boxColor = 0xAAFF8800;
                        long elapsed = now - flashStartTime;
                        if (elapsed < 400 && (elapsed / 100) % 2 == 0) {
                            boxColor = 0xFFFFFFFF;
                        }
                    }
                    g.fill(xStart, lineY - 1, xEnd, lineY + 9, boxColor);
                }
            }

            renderLineSelection(g, i, cX + PADDING, lineY, line);

            g.drawString(this.font, line.visual, cX + PADDING, lineY, 0xFFFFFFFF, true);

            for (SearchMatch m : matches) {
                if (m.lineIdx == i) {
                    boolean isCurrent = (matches.indexOf(m) == currentMatchIdx);
                    long elapsed = now - flashStartTime;
                    if (isCurrent && elapsed < 400 && (elapsed / 100) % 2 == 0) {
                        int xStart = cX + PADDING + line.getOffset(m.startCol);
                        String snippet = line.rawText.substring(m.startCol, m.endCol);
                        g.drawString(this.font, snippet, xStart, lineY, 0xFF000000, false);
                    }
                }
            }
        }
        g.disableScissor();

        renderThickScrollbar(g, cX + FRAME_W - (SCROLLBAR_WIDTH + SCROLLBAR_MARGIN), innerY, visiblePixels);

        if (showCopyMenu) {
            g.pose().pushPose();
            g.pose().translate(0, 0, 1000f);
            renderCopyMenu(g, cX, innerY, visiblePixels);
            g.pose().popPose();
        }

        if (System.currentTimeMillis() < toastEndTime) {
            g.drawCenteredString(this.font, toastText, vWidth / 2, cY + cH + 10, GOLDEN_COLOR);
        }

        g.pose().popPose();

        super.render(g, mx, my, pt);

        if (this.searchBox != null && !this.searchBox.isFocused() && this.searchBox.getValue().isEmpty()) {
            g.drawString(this.font, Component.translatable("gui.kineticchat.search_hint"),
                    this.searchBox.getX() + 4,
                    this.searchBox.getY() + (this.searchBox.getHeight() - 8) / 2,
                    0xFF888888, false);
        }
    }


    private void enableVirtualScissor(GuiGraphics g, int x1, int y1, int x2, int y2) {
        int left = Mth.clamp((int) Math.floor(Math.min(x1, x2) * this.guiScale), 0, this.width);
        int top = Mth.clamp((int) Math.floor(Math.min(y1, y2) * this.guiScale), 0, this.height);
        int right = Mth.clamp((int) Math.ceil(Math.max(x1, x2) * this.guiScale), 0, this.width);
        int bottom = Mth.clamp((int) Math.ceil(Math.max(y1, y2) * this.guiScale), 0, this.height);
        g.enableScissor(left, top, right, bottom);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int btn) {

        if (this.searchBox != null && !this.searchBox.isMouseOver(mx, my)) {
            if (this.getFocused() == this.searchBox) {
                this.setFocused(null);
            }
            this.searchBox.setFocused(false);
        }

        if (super.mouseClicked(mx, my, btn)) return true;

        double vMx = mx / guiScale, vMy = my / guiScale;
        int cX = (vWidth - FRAME_W) / 2;
        int cY = 25, cH = vHeight - 75 + BOTTOM_EXPAND;
        int gutter = SCROLLBAR_WIDTH + SCROLLBAR_MARGIN + 2;
        int innerY = cY + INNER_PADDING;
        int innerH = Math.max(0, cH - INNER_PADDING * 2);
        int visiblePixels = (innerH / LINE_H) * LINE_H;

        if (btn == 0 && showCopyMenu) {
            int mw = 55, mh = 14;
            int dx = (int) Mth.clamp(menuX, cX + INNER_PADDING, cX + FRAME_W - INNER_PADDING - mw);
            int dy = (int) Mth.clamp(menuY, innerY, innerY + visiblePixels - mh);
            if (vMx >= dx && vMx <= dx + mw && vMy >= dy && vMy <= dy + mh) {
                doCopy(); return true;
            }
        }

        if (btn == 1 && hasSelection()) {
            showCopyMenu = true; menuX = vMx; menuY = vMy; return true;
        }
        showCopyMenu = false;

        if (btn == 0 && vMx >= cX + FRAME_W - gutter && vMx <= cX + FRAME_W && vMy >= innerY && vMy <= innerY + visiblePixels) {
            isDraggingScrollbar = true;
            updateScrollFromMouse(vMy);
            return true;
        }

        int idx = getLineIndexAt(vMx, vMy);
        if (idx != -1) {
            int col = getColAt(idx, vMx);
            long currentTime = System.currentTimeMillis();

            if (btn == 0 && (currentTime - lastClickTime < 300) && lastClickLine == idx && Math.abs(lastClickCol - col) <= 3) {
                int[] bounds = getWordBoundaries(lines.get(idx).rawText, col);
                startLine = endLine = idx;
                startCol = bounds[0];
                endCol = bounds[1];
                isDraggingText = false;
            } else {
                startLine = endLine = idx;
                startCol = endCol = col;
                isDraggingText = true;
            }

            lastClickTime = currentTime;
            lastClickLine = idx;
            lastClickCol = col;
            return true;
        }

        startLine = -1;
        return false;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (this.searchBox.isFocused()) {
            if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                navigateMatch(1);
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                this.setFocused(null);
                this.searchBox.setFocused(false);
                return true;
            }
        }

        if (Screen.hasControlDown() && keyCode == GLFW.GLFW_KEY_C) {
            if (hasSelection()) {
                doCopy();
                return true;
            }
        }
        if (Screen.hasControlDown() && keyCode == GLFW.GLFW_KEY_F) {
            this.setFocused(this.searchBox);
            this.searchBox.setFocused(true);
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private void renderThickScrollbar(GuiGraphics g, int x, int y, int h) {
        int totalH = lines.size() * LINE_H + 4;
        if (totalH <= h) return;
        float ratio = (float) h / totalH;
        int handleH = Math.max(15, (int) (h * ratio));
        int maxScroll = getMaxScroll();
        int handleY = y + (int) ((h - handleH) * ((float) scrollPos / Math.max(1, maxScroll)));

        g.fill(x, y, x + SCROLLBAR_WIDTH, y + h, 0x33FFFFFF);
        g.fill(x, handleY, x + SCROLLBAR_WIDTH, handleY + handleH, 0xCCFFFFFF);
    }

    private void renderCopyMenu(GuiGraphics g, int cX, int cY, int cH) {
        int mw = 55, mh = 14;
        int dx = (int) Mth.clamp(menuX, cX, cX + FRAME_W - mw);
        int dy = (int) Mth.clamp(menuY, cY, cY + cH - mh);

        g.fill(dx, dy, dx + mw, dy + mh, 0xFF222222);
        g.renderOutline(dx, dy, mw, mh, 0xFFFFFFFF);
        g.drawCenteredString(this.font, Component.translatable("gui.kineticchat.copy"), dx + mw / 2, dy + 3, GOLDEN_COLOR);
    }

    private void renderLineSelection(GuiGraphics g, int idx, int x, int y, CanvasLine line) {
        if (startLine == -1 || endLine == -1) return;
        int l1 = startLine, c1 = startCol, l2 = endLine, c2 = endCol;
        if (l1 > l2 || (l1 == l2 && c1 > c2)) { int t=l1; l1=l2; l2=t; t=c1; c1=c2; c2=t; }
        if (idx < l1 || idx > l2) return;

        int s = (idx == l1) ? c1 : 0;
        int e = (idx == l2) ? c2 : line.rawText.length();

        int xStart = x + line.getOffset(s);
        int xEnd = x + line.getOffset(e);

        g.fill(xStart, y - 1, xEnd, y + 9, 0x6600AAFF);
    }

    private void drawOutwardBorder(GuiGraphics g, int x, int y, int h) {
        g.fill(x - MARGIN, y - MARGIN, x + FRAME_W + MARGIN, y - MARGIN + 1, GOLDEN_COLOR);
        g.fill(x - MARGIN, y + h + MARGIN - 1, x + FRAME_W + MARGIN, y + h + MARGIN, GOLDEN_COLOR);
        g.fill(x - MARGIN, y - MARGIN, x - MARGIN + 1, y + h + MARGIN, GOLDEN_COLOR);
        g.fill(x + FRAME_W + MARGIN - 1, y - MARGIN, x + FRAME_W + MARGIN, y + h + MARGIN, GOLDEN_COLOR);
    }

    private boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '.' || c == ':' || c == '/' || c == '-' || c == '?' || c == '=' || c == '&' || c == '%';
    }

    private int[] getWordBoundaries(String text, int col) {
        if (text == null || text.isEmpty()) return new int[]{0, 0};
        if (col >= text.length()) col = text.length() - 1;
        if (col < 0) col = 0;

        char c = text.charAt(col);
        boolean isAlphanumeric = isWordChar(c);
        boolean isWhitespace = Character.isWhitespace(c);

        int start = col;
        while (start > 0) {
            char prev = text.charAt(start - 1);
            if (isWhitespace && Character.isWhitespace(prev)) start--;
            else if (isAlphanumeric && isWordChar(prev)) start--;
            else if (!isWhitespace && !isAlphanumeric && !Character.isWhitespace(prev) && !isWordChar(prev)) start--;
            else break;
        }

        int end = col;
        while (end < text.length() - 1) {
            char next = text.charAt(end + 1);
            if (isWhitespace && Character.isWhitespace(next)) end++;
            else if (isAlphanumeric && isWordChar(next)) end++;
            else if (!isWhitespace && !isAlphanumeric && !Character.isWhitespace(next) && !isWordChar(next)) end++;
            else break;
        }
        return new int[]{start, end + 1};
    }

    @Override
    public boolean mouseDragged(double mx, double my, int btn, double dx, double dy) {
        double vMx = mx / guiScale, vMy = my / guiScale;
        if (isDraggingScrollbar) {
            updateScrollFromMouse(vMy);
            return true;
        }
        if (isDraggingText) {
            int idx = getLineIndexAt(vMx, vMy);
            if (idx != -1) {
                endLine = idx;
                endCol = getColAt(idx, vMx);
            }
            return true;
        }
        return super.mouseDragged(mx, my, btn, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int btn) {
        isDraggingText = isDraggingScrollbar = false;
        return super.mouseReleased(mx, my, btn);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        scrollPos = Mth.clamp(scrollPos - (int)(delta * 20), 0, getMaxScroll());
        return true;
    }

    private void updateScrollFromMouse(double vMy) {
        int cY = 25, cH = vHeight - 75 + BOTTOM_EXPAND;
        int innerY = cY + INNER_PADDING;
        int innerH = Math.max(0, cH - INNER_PADDING * 2);
        int visiblePixels = (innerH / LINE_H) * LINE_H;
        float progress = (float)(vMy - innerY) / (float) visiblePixels;
        progress = Mth.clamp(progress, 0.0f, 1.0f);
        scrollPos = (int) (progress * getMaxScroll());

        scrollPos = (scrollPos / LINE_H) * LINE_H;
    }

    private int getLineIndexAt(double vMx, double vMy) {
        int cX = (vWidth - FRAME_W) / 2;
        int cY = 25, cH = vHeight - 75 + BOTTOM_EXPAND;
        int gutter = SCROLLBAR_WIDTH + SCROLLBAR_MARGIN + 2;
        if (vMx < cX || vMx > cX + FRAME_W - gutter || vMy < cY || vMy > cY + cH) return -1;
        double relativeY = vMy - (cY + 2) + scrollPos;
        int idx = (int) Math.floor(relativeY / LINE_H);
        return (idx >= 0 && idx < lines.size()) ? idx : -1;
    }

    private int getColAt(int idx, double vMx) {
        CanvasLine line = lines.get(idx);
        double lx = vMx - ((vWidth - FRAME_W) / 2.0 + PADDING);
        if (lx <= 0) return 0;
        int bestCol = 0;
        double minDiff = Double.MAX_VALUE;
        for (int i = 0; i < line.splitOffsets.length; i++) {
            double diff = Math.abs(line.splitOffsets[i] - lx);
            if (diff < minDiff) { minDiff = diff; bestCol = i; }
        }
        return bestCol;
    }

    private void doCopy() {
        StringBuilder sb = new StringBuilder();
        int l1 = startLine, c1 = startCol, l2 = endLine, c2 = endCol;
        if (l1 > l2 || (l1 == l2 && c1 > c2)) { int t=l1; l1=l2; l2=t; t=c1; c1=c2; c2=t; }
        for (int i = l1; i <= l2; i++) {
            CanvasLine line = lines.get(i);
            int s = (i == l1) ? c1 : 0;
            int e = (i == l2) ? c2 : line.rawText.length();
            if (s < e) sb.append(line.rawText, s, e);
            if (i < l2) sb.append("\n");
        }
        Minecraft.getInstance().keyboardHandler.setClipboard(sb.toString());
        this.toastText = Component.translatable("msg.kineticchat.copy_success").getString();
        this.toastEndTime = System.currentTimeMillis() + 2500;
        showCopyMenu = false;
    }

    private boolean hasSelection() { return startLine != -1 && (startLine != endLine || startCol != endCol); }

    private static class CanvasLine {
        final net.minecraft.util.FormattedCharSequence visual;
        final String rawText;
        final int[] splitOffsets;

        CanvasLine(GuiMessage.Line line) {
            this.visual = line.content();
            StringBuilder sb = new StringBuilder();
            List<Integer> boundaries = new ArrayList<>();
            int[] currentX = {0};
            var font = Minecraft.getInstance().font;
            boundaries.add(0);
            this.visual.accept((index, style, cp) -> {
                String s = new String(Character.toChars(cp));
                int w = font.width(net.minecraft.util.FormattedCharSequence.forward(s, style));
                int startLen = sb.length();
                sb.append(s);
                int endLen = sb.length();
                for (int k = startLen; k < endLen; k++) {
                    currentX[0] += (k == startLen ? w : 0);
                    boundaries.add(currentX[0]);
                }
                return true;
            });
            this.rawText = sb.toString();
            this.splitOffsets = boundaries.stream().mapToInt(i -> i).toArray();
        }

        int getOffset(int col) {
            if (col <= 0 || splitOffsets.length == 0) return 0;
            if (col >= splitOffsets.length) return splitOffsets[splitOffsets.length - 1];
            return splitOffsets[col];
        }
    }

    private record SearchMatch(int lineIdx, int startCol, int endCol) {}
}
