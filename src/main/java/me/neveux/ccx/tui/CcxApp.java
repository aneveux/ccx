package me.neveux.ccx.tui;

import dev.tamboui.layout.Constraint;
import dev.tamboui.layout.Layout;
import dev.tamboui.layout.Rect;
import dev.tamboui.style.Style;
import dev.tamboui.terminal.Frame;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import dev.tamboui.text.Text;
import dev.tamboui.tui.TuiRunner;
import dev.tamboui.tui.event.Event;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.event.TickEvent;
import dev.tamboui.widgets.block.Block;
import dev.tamboui.widgets.block.BorderType;
import dev.tamboui.widgets.block.Borders;
import dev.tamboui.widgets.paragraph.Paragraph;
import dev.tamboui.widgets.tabs.Tabs;
import dev.tamboui.widgets.tabs.TabsState;
import me.neveux.ccx.cli.CcxCommand;
import me.neveux.ccx.model.ProfileDriftState;
import me.neveux.ccx.tui.CatppuccinTheme;
import me.neveux.ccx.tui.Quips;
import me.neveux.ccx.service.CcxContextService;
import me.neveux.ccx.service.ClaudeMdService;
import me.neveux.ccx.service.ClaudePluginInstallService;
import me.neveux.ccx.service.ClaudePluginRegistryService;
import me.neveux.ccx.service.MarketplaceCloneService;
import me.neveux.ccx.service.PluginMapService;
import me.neveux.ccx.service.ProfileApplyService;
import me.neveux.ccx.service.ProfileService;
import me.neveux.ccx.service.SettingsService;
import me.neveux.ccx.tui.tabs.ClaudeMdTab;
import me.neveux.ccx.tui.tabs.McpTab;
import me.neveux.ccx.tui.tabs.OverviewTab;
import me.neveux.ccx.tui.tabs.PluginsTab;
import me.neveux.ccx.tui.tabs.ProfilesTab;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * Root TUI application for ccx. Wraps Tamboui's {@link TuiRunner} lifecycle and owns
 * the top-level event loop: key dispatch, tab navigation, filter-bar focus, and
 * footer rendering. The five {@code *Tab} instances hold their own list state; this
 * class wires them together with {@link TuiState} and drives them via
 * {@link TuiActions} on mutations.
 */
public final class CcxApp {

    private static final Logger log = LoggerFactory.getLogger(CcxApp.class);

    private static final String APP_VERSION = resolveAppVersion();
    private static final String FOOTER_BRAND = Glyphs.BRAND + " ccx " + APP_VERSION;

    private static String resolveAppVersion() {
        return CcxCommand.version();
    }
    private static final String FOOTER_LEFT = FOOTER_BRAND + "  ? Help";

    private static final String[] TAB_TITLES = Arrays.stream(Tab.values())
        .map(Tab::title)
        .toArray(String[]::new);

    private final TuiDataService tuiDataService;
    private final Path projectDir;
    private final TuiState state;
    private final TuiActions tuiActions;
    private final MarketplaceFetchController fetch;
    private final PluginInstallController pluginInstall;

    private final OverviewTab overviewTab = new OverviewTab();
    private final ProfilesTab profilesTab = new ProfilesTab();
    private final PluginsTab pluginsTab = new PluginsTab();
    private final McpTab mcpTab = new McpTab();
    private final ClaudeMdTab claudeMdTab = new ClaudeMdTab();

    public CcxApp(TuiDataService tuiDataService, SettingsService settingsService, ClaudeMdService claudeMdService,
                  ProfileApplyService profileApplyService, CcxContextService ccxContextService, ProfileService profileService,
                  MarketplaceCloneService marketplaceCloneService, PluginMapService pluginMapService,
                  Path projectDir) {
        this.tuiDataService = tuiDataService;
        this.projectDir = projectDir;
        this.tuiActions = new TuiActions(settingsService, claudeMdService, tuiDataService,
            profileApplyService, ccxContextService, profileService);
        var snapshot = tuiDataService.snapshot(projectDir);
        this.state = new TuiState(snapshot);
        this.fetch = new MarketplaceFetchController(settingsService, marketplaceCloneService,
            pluginMapService, projectDir);
        // Not injected: CcxApp is built by hand rather than by CDI, and both services are stateless.
        this.pluginInstall = new PluginInstallController(settingsService, new ClaudePluginRegistryService(),
            new ClaudePluginInstallService(), projectDir, fetch::setStatus);

        fetch.start("marketplace-refresh", false);
        fetch.loadCacheInfo();
    }

    public void run() throws Exception {
        try (var tui = TuiRunner.create()) {
            tui.run(this::handleEvent, this::renderFrame);
        }
    }

    boolean handleEvent(Event event, TuiRunner runner) {
        if (event instanceof TickEvent) {
            return fetch.active() || pluginInstall.installing();
        }
        if (!(event instanceof KeyEvent k)) {
            return false;
        }

        if (k.isQuit() && !state.filterFocused()) {
            runner.quit();
            return true;
        }

        if (k.isChar('?') && !state.filterFocused()) {
            state.toggleHelp();
            return true;
        }

        // Escape closes help overlay (takes priority over filter-blur)
        if (k.code() == KeyCode.ESCAPE && state.helpVisible()) {
            state.toggleHelp();
            return true;
        }

        // Tab/Right = next tab; Shift-Tab/Left = prev tab (Left/Right blocked in filter mode)
        if ((k.code() == KeyCode.TAB && !k.hasShift()) || (k.code() == KeyCode.RIGHT && !state.filterFocused())) {
            state.nextTab();
            syncListState();
            return true;
        }
        if ((k.code() == KeyCode.TAB && k.hasShift()) || (k.code() == KeyCode.LEFT && !state.filterFocused())) {
            state.prevTab();
            syncListState();
            return true;
        }

        // Number keys 1-N → jump to tab (derived from Tab enum), not when filter is focused
        if (!state.filterFocused()) {
            for (var tab : Tab.values()) {
                if (k.isChar((char) ('1' + tab.index()))) {
                    state.setTab(tab.index());
                    syncListState();
                    return true;
                }
            }
        }

        // / key — focus filter on filterable tabs
        if (k.isChar('/') && state.hasFilter() && !state.filterFocused()) {
            state.focusFilter();
            return true;
        }

        // Filter-focused routing — BEFORE Up/Down/Enter
        if (state.filterFocused()) {
            if (k.code() == KeyCode.ESCAPE) {
                state.blurFilter();
                state.currentFilter().clear();
                return true;
            }
            if (k.code() == KeyCode.BACKSPACE) {
                state.currentFilter().backspace();
                return true;
            }
            if (k.isUp()) {
                var f = state.currentFilter();
                f.setSelectedIndex(Math.max(0, f.selectedIndex() - 1));
                return true;
            }
            if (k.isDown()) {
                var f = state.currentFilter();
                f.setSelectedIndex(f.selectedIndex() + 1);
                return true;
            }
            // printable char — append to filter
            var c = k.character();
            if (k.code() == KeyCode.CHAR && c >= 32) {
                state.currentFilter().appendChar(c);
                state.currentFilter().setSelectedIndex(0);
                return true;
            }
            // let Tab, Enter, q fall through
        }

        // Profile tab actions — Enter toggles by state; r reinstalls
        if (k.isChar('r') && activeTab() == Tab.PROFILES) {
            return handleProfileAction("reinstall");
        }
        if (k.isChar('r') && activeTab() == Tab.PLUGINS) {
            return handleMarketplaceRefresh();
        }

        if (k.isChar('j') && activeTab() == Tab.OVERVIEW && !state.filterFocused()) {
            state.scrollOverview(1);
            return true;
        }

        if (k.isChar('k') && activeTab() == Tab.OVERVIEW && !state.filterFocused()) {
            state.scrollOverview(-1);
            return true;
        }

        if (k.isUp()) {
            if (activeTab() == Tab.OVERVIEW) {
                state.scrollOverview(-1);
            } else {
                state.prevRow();
                syncListState();
            }
            return true;
        }

        if (k.isDown()) {
            if (activeTab() == Tab.OVERVIEW) {
                state.scrollOverview(1);
            } else {
                state.nextRow(currentTabItemCount());
                syncListState();
            }
            return true;
        }

        if (k.code() == KeyCode.ENTER) {
            return handleEnter();
        }

        return false;
    }

    private boolean handleEnter() {
        var snap = state.snapshot();
        switch (activeTab()) {
            case PROFILES -> resolvedItem(snap.profiles(), TuiSnapshot.ProfileEntry::name).ifPresent(entry -> {
                var action = entry.driftState() == ProfileDriftState.DISABLED
                    ? "enable"
                    : "disable";
                handleProfileAction(action);
            });
            case PLUGINS -> resolvedItem(snap.plugins(), TuiSnapshot.PluginEntry::qualifiedName).ifPresent(plugin -> {
                var pluginName = plugin.qualifiedName();
                var newSnap = tuiActions.togglePlugin(pluginName, projectDir);
                state.setSnapshot(newSnap);
                var err = tuiActions.consumeLastError();
                String message = (err != null)
                    ? Quips.pick(Quips.IMPOSSIBLE, err.hashCode()) + "  " + err
                    : Glyphs.OK + " toggled: " + pluginName + "  " + Quips.pick(Quips.TOGGLE, pluginName.hashCode());
                fetch.setStatus(message, 3);
                if (err == null && isProjectEnabled(newSnap, pluginName)) {
                    pluginInstall.installPlugin(pluginName);
                }
                log.debug("plugin toggled name={}", pluginName);
            });
            case MCP -> resolvedItem(snap.mcps(), TuiSnapshot.McpEntry::name).ifPresent(mcp -> {
                var serverName = mcp.name();
                var newSnap = tuiActions.toggleMcp(serverName, projectDir);
                boolean wasRemoved = snap.mcpServers().containsKey(serverName) && !newSnap.mcpServers().containsKey(serverName);
                state.setSnapshot(newSnap);
                fetch.setStatus(wasRemoved
                    ? Glyphs.OK + " removed: " + serverName + "  " + Quips.pick(Quips.REMOVE, serverName.hashCode())
                    : Glyphs.WARNING + " server not found: " + serverName, 3);
                log.debug("mcp server toggled name={}", serverName);
            });
            case CLAUDEMD -> resolvedItem(snap.claudeMdEntries(), TuiSnapshot.StackEntry::name).ifPresent(entry -> {
                TuiSnapshot newSnap;
                String msg;
                if (entry.source() == TuiSnapshot.StackSource.AVAILABLE) {
                    newSnap = tuiActions.addStack(entry.line(), projectDir);
                    msg = Glyphs.OK + " added: " + entry.name() + "  " + Quips.pick(Quips.ADD, entry.name().hashCode());
                } else {
                    newSnap = tuiActions.removeStack(entry.line(), projectDir);
                    msg = Glyphs.OK + " removed: " + entry.name() + "  " + Quips.pick(Quips.REMOVE, entry.name().hashCode());
                }
                state.setSnapshot(newSnap);
                fetch.setStatus(msg, 3);
                log.debug("stack toggled line={} source={}", entry.line(), entry.source());
            });
            default -> { /* no-op */ }
        }
        return true;
    }

    /**
     * True when {@code snapshot} shows {@code qualifiedName} enabled in the project, which is what
     * distinguishes a toggle that turned a plugin on from one that turned it off.
     */
    private static boolean isProjectEnabled(TuiSnapshot snapshot, String qualifiedName) {
        return snapshot.plugins().stream()
            .anyMatch(plugin -> qualifiedName.equals(plugin.qualifiedName())
                && plugin.status() == TuiSnapshot.PluginStatus.PROJECT_ENABLED);
    }

    private <T> Optional<T> resolvedItem(List<T> items, Function<T, String> label) {
        var filtered = state.hasFilter()
            ? state.currentFilter().filter(items, label)
            : items;
        if (filtered.isEmpty()) return Optional.empty();
        var idx = (state.hasFilter() && !state.currentFilter().isEmpty())
            ? state.currentFilter().clampedIndex(filtered)
            : Math.min(state.selectedRow(), filtered.size() - 1);
        return Optional.of(filtered.get(idx));
    }

    private boolean handleProfileAction(String action) {
        var snap = state.snapshot();
        var profileName = resolvedItem(snap.profiles(), TuiSnapshot.ProfileEntry::name)
            .map(TuiSnapshot.ProfileEntry::name)
            .orElse(null);
        if (profileName == null) return true;
        TuiSnapshot newSnap = switch (action) {
            case "enable" -> tuiActions.enableProfile(profileName, projectDir);
            case "disable" -> tuiActions.disableProfile(profileName, projectDir);
            case "reinstall" -> tuiActions.reinstallProfile(profileName, projectDir);
            default -> snap;
        };
        state.setSnapshot(newSnap);
        var err = tuiActions.consumeLastError();
        String message;
        if (err != null) {
            message = Quips.pick(Quips.ERROR, err.hashCode()) + "  " + err;
        } else {
            message = switch (action) {
                case "enable"    -> Glyphs.ENABLED + " enabled: " + profileName + "  " + Quips.pick(Quips.ENABLE, profileName.hashCode());
                case "disable"   -> Glyphs.AVAILABLE + " disabled: " + profileName + "  " + Quips.pick(Quips.DISABLE, profileName.hashCode());
                case "reinstall" -> Glyphs.BRAND + " reinstalled: " + profileName + "  " + Quips.pick(Quips.REINSTALL, profileName.hashCode());
                default          -> action + ": " + profileName;
            };
        }
        fetch.setStatus(message, 3);
        // A profile enable writes enabledPlugins for the whole extends closure; those plugins
        // still need installing before Claude Code can load them.
        if (err == null && (action.equals("enable") || action.equals("reinstall"))) {
            pluginInstall.installEnabledPlugins();
        }
        log.debug("profile action={} name={}", action, profileName);
        return true;
    }

    private boolean handleMarketplaceRefresh() {
        // start() claims the in-flight guard itself, so a repeated keypress is a no-op here.
        fetch.start("marketplace-refresh-manual", true);
        return true;
    }

    private Tab activeTab() {
        return Tab.of(state.activeTab());
    }

    private void syncListState() {
        var row = state.selectedRow();
        switch (activeTab()) {
            case PROFILES -> profilesTab.listState().select(row);
            case PLUGINS -> pluginsTab.listState().select(row);
            case MCP -> mcpTab.listState().select(row);
            case CLAUDEMD -> claudeMdTab.listState().select(row);
            default -> { /* Overview has no list */ }
        }
    }

    private int currentTabItemCount() {
        var snap = state.snapshot();
        return switch (activeTab()) {
            case PROFILES -> filteredSize(snap.profiles(), state.currentFilter(), TuiSnapshot.ProfileEntry::name);
            case PLUGINS -> filteredSize(snap.plugins(), state.currentFilter(), TuiSnapshot.PluginEntry::qualifiedName);
            case MCP -> filteredSize(snap.mcps(), state.currentFilter(), TuiSnapshot.McpEntry::name);
            case CLAUDEMD -> filteredSize(snap.claudeMdEntries(), state.currentFilter(), TuiSnapshot.StackEntry::name);
            default -> 0;
        };
    }

    private <T> int filteredSize(List<T> items, FilterState f, Function<T, String> label) {
        return f != null ? f.filter(items, label).size() : items.size();
    }

    void renderFrame(Frame frame) {
        if (fetch.consumeRefreshPending()) {
            var newSnap = tuiDataService.snapshot(projectDir);
            state.setSnapshot(newSnap);
            log.debug("snapshot rebuilt after background marketplace refresh");
        }
        if (pluginInstall.consumeRefreshPending()) {
            state.setSnapshot(tuiDataService.snapshot(projectDir));
            log.debug("snapshot rebuilt after background plugin install");
        }

        var fullArea = frame.area();
        var areas = Layout.vertical()
            .constraints(Constraint.length(1), Constraint.fill(), Constraint.length(1))
            .split(fullArea);

        var headerArea = areas.get(0);
        var bodyArea = areas.get(1);
        var footerArea = areas.get(2);

        renderHeader(frame, headerArea);
        renderBody(frame, bodyArea);
        renderFooter(frame, footerArea);

        if (state.helpVisible()) {
            renderHelp(frame, fullArea);
        }
    }

    private void renderHeader(Frame frame, Rect area) {
        var tabs = Tabs.builder()
            .titles(TAB_TITLES)
            .highlightStyle(Style.EMPTY.fg(CatppuccinTheme.MAUVE).bold())
            .build();

        var tabsState = new TabsState(state.activeTab());
        frame.renderStatefulWidget(tabs, area, tabsState);
    }

    private void renderBody(Frame frame, Rect area) {
        var snap = state.snapshot();
        switch (activeTab()) {
            case OVERVIEW -> state.setOverviewMaxOffset(overviewTab.render(frame, area, snap, projectDir, state.selectedRow()));
            case PROFILES -> profilesTab.render(frame, area, snap, state.currentFilter(), state.selectedRow());
            case PLUGINS -> pluginsTab.render(frame, area, snap, state.currentFilter(), state.selectedRow());
            case MCP -> mcpTab.render(frame, area, snap, state.currentFilter(), state.selectedRow());
            case CLAUDEMD -> claudeMdTab.render(frame, area, snap, state.currentFilter(), state.selectedRow());
        }
    }

    private void renderFooter(Frame frame, Rect area) {
        String rightText;
        var rightColor = CatppuccinTheme.SUBTEXT;
        if (fetch.fetching()) {
            rightText = fetch.spinnerGlyph() + " fetching plugins from marketplaces… ";
            rightColor = CatppuccinTheme.MAUVE;
        } else if (fetch.statusActive()) {
            rightText = fetch.statusMessage() + " ";
        } else {
            rightText = fetch.formatCacheInfo() + " ";
        }
        var padding = Glyphs.footerPadding(area.width(), FOOTER_LEFT, rightText);
        var line = Line.from(
            Span.styled(FOOTER_LEFT, Style.EMPTY.fg(CatppuccinTheme.MAUVE)),
            Span.styled(" ".repeat(padding) + rightText, Style.EMPTY.fg(rightColor))
        );
        var footer = Paragraph.builder()
            .text(Text.from(line))
            .build();
        frame.renderWidget(footer, area);
    }

    private void renderHelp(Frame frame, Rect fullArea) {
        int width = Math.min(66, fullArea.width());
        int height = Math.min(18, fullArea.height());
        int x = (fullArea.width() - width) / 2;
        int y = (fullArea.height() - height) / 2;
        var helpArea = new Rect(x + fullArea.left(), y + fullArea.top(), width, height);

        // Fill every cell in the popup area — one Line per row so all rows get the bg color
        var blankLine = Line.from(Span.styled(" ".repeat(width), Style.EMPTY.bg(CatppuccinTheme.SURFACE0)));
        var bgFill = Paragraph.builder()
            .text(Text.from(Collections.nCopies(height, blankLine)))
            .build();
        frame.renderWidget(bgFill, helpArea);

        String[][] rows = {
            {"← → Tab S-Tab", "navigate tabs"},
            {"1–5", "jump to tab"},
            {"↑ ↓", "navigate list"},
            {"/", "focus filter"},
            {"Esc", "clear filter · close help"},
            {"Enter", "toggle selected item"},
            {"Enter (MCP)", "remove server (remove-only)"},
            {"r (Profiles)", "reinstall profile"},
            {"r (Plugins)", "refresh marketplace"},
            {"q", "quit"},
            {"?", "toggle this help"},
        };
        int keyWidth = 0;
        for (var row : rows) keyWidth = Math.max(keyWidth, row[0].length());
        keyWidth += 2;

        var keyStyle = Style.EMPTY.fg(CatppuccinTheme.LAVENDER).bold().bg(CatppuccinTheme.SURFACE0);
        var actionStyle = Style.EMPTY.fg(CatppuccinTheme.TEXT).bg(CatppuccinTheme.SURFACE0);
        var lines = new ArrayList<Line>(rows.length);
        for (var row : rows) {
            var key = row[0];
            var padded = "  " + key + " ".repeat(Math.max(0, keyWidth - key.length()));
            lines.add(Line.from(
                Span.styled(padded, keyStyle),
                Span.styled(row[1], actionStyle)));
        }

        var help = Paragraph.builder()
            .text(Text.from(lines))
            .block(Block.builder()
                .title(" ✦ help — ? to close ")
                .borders(Borders.ALL)
                .borderType(BorderType.ROUNDED)
                .borderStyle(Style.EMPTY.fg(CatppuccinTheme.MAUVE))
                .style(Style.EMPTY.bg(CatppuccinTheme.SURFACE0))
                .build())
            .build();

        frame.renderWidget(help, helpArea);
    }
}
