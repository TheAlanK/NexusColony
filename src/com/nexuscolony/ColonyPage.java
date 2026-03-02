package com.nexuscolony;

import com.nexusui.api.NexusPage;
import com.nexusui.overlay.NexusFrame;
import com.nexusui.bridge.GameDataBridge;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CargoAPI;
import com.fs.starfarer.api.campaign.econ.*;
import com.fs.starfarer.api.campaign.CargoStackAPI;
import com.fs.starfarer.api.campaign.SpecialItemData;
import com.fs.starfarer.api.campaign.SpecialItemSpecAPI;
import com.fs.starfarer.api.impl.campaign.econ.impl.BaseIndustry;
import com.fs.starfarer.api.impl.campaign.econ.impl.ConstructionQueue;
import com.fs.starfarer.api.loading.IndustrySpecAPI;

import javax.swing.*;
import javax.swing.border.*;
import javax.swing.event.*;
import java.awt.*;
import java.awt.event.*;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * ColonyPage - Interactive colony management UI built on NexusUI.
 *
 * Provides full remote control: build/cancel/upgrade/remove industries,
 * manage construction queues, toggle settings, install AI cores.
 * Works with Industrial Evolution and all colony mods via standard APIs.
 */
public class ColonyPage implements NexusPage {

    // ========================================================================
    // State
    // ========================================================================

    private volatile ColonyTracker.GlobalSnapshot data;
    private String selectedColonyId;
    private boolean showBuildView = false;
    private long lastTimestamp = 0;
    private String lastSidebarShape = "";
    private String lastDetailShape = "";

    // Available industries (fetched on demand for build dialog)
    private JSONArray availableIndustries;
    private String buildFetchCommandId;

    // Upgrade view state
    private boolean showUpgradeView = false;
    private String upgradeViewMarketId;
    private String upgradeViewIndustryId;
    private String upgradeViewCurrentName;
    private String upgradeViewTargetName;
    private int upgradeViewCost;
    private int upgradeViewBuildTime;

    // Command tracking
    private final LinkedHashMap<String, String> pendingCommands = new LinkedHashMap<String, String>();

    // UI refs
    private JPanel sidebarContent;
    private JPanel detailPanel;
    private JScrollPane detailScroll;
    private JLabel feedbackLabel;
    private Timer liveTimer;
    private Timer pollTimer;
    private Timer feedbackClearTimer;
    private final Map<String, JPanel> sidebarButtons = new LinkedHashMap<String, JPanel>();
    private final Map<String, JLabel> sidebarIncomeLabels = new LinkedHashMap<String, JLabel>();
    private JLabel creditsLiveLabel;
    private String creditsLivePrefix = "Credits: ";
    private JPanel buildListPanel;
    private String buildSearchQuery = "";
    private long lastBuildViewCredits;

    // ========================================================================
    // NexusPage interface
    // ========================================================================

    public String getId() { return "nexus_colony"; }
    public String getTitle() { return "Colonies"; }

    public JPanel createPanel(int port) {
        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(NexusFrame.BG_PRIMARY);

        // Sidebar
        root.add(createSidebar(), BorderLayout.WEST);

        // Detail area
        detailPanel = new JPanel();
        detailPanel.setLayout(new BoxLayout(detailPanel, BoxLayout.Y_AXIS));
        detailPanel.setBackground(NexusFrame.BG_PRIMARY);

        detailScroll = new JScrollPane(detailPanel);
        styleScrollPane(detailScroll);
        root.add(detailScroll, BorderLayout.CENTER);

        // Feedback bar
        feedbackLabel = new JLabel(" ");
        feedbackLabel.setFont(NexusFrame.FONT_SMALL);
        feedbackLabel.setForeground(NexusFrame.TEXT_MUTED);
        feedbackLabel.setBorder(new EmptyBorder(6, 14, 6, 14));
        feedbackLabel.setBackground(NexusFrame.BG_SECONDARY);
        feedbackLabel.setOpaque(true);
        root.add(feedbackLabel, BorderLayout.SOUTH);

        // Live data timer (500ms)
        liveTimer = new Timer(500, new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                onLiveTimer();
            }
        });
        liveTimer.start();

        // Command poll timer (200ms)
        pollTimer = new Timer(200, new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                pollResults();
            }
        });
        pollTimer.start();

        // Show initial state
        showNoColonySelected();

        return root;
    }

    public void refresh() {
        data = ColonyTracker.getSnapshot();
    }

    // ========================================================================
    // Shape computation (for change detection - prevents UI flicker)
    // ========================================================================

    private String computeSidebarShape(ColonyTracker.GlobalSnapshot snap) {
        if (snap == null || snap.colonies.length == 0) return "empty";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < snap.colonies.length; i++) {
            ColonyTracker.ColonySnapshot col = snap.colonies[i];
            sb.append(col.id).append(':').append(col.size).append(',');
        }
        return sb.toString();
    }

    private String computeDetailShape(ColonyTracker.ColonySnapshot col) {
        if (col == null) return "null";
        StringBuilder sb = new StringBuilder();
        sb.append(col.id).append('|');
        sb.append((int)(col.stability * 10)).append('|');
        sb.append((int)(col.hazard * 100)).append('|');
        sb.append((int)col.netIncome).append('|');
        sb.append(col.freePort ? '1' : '0');
        sb.append(col.immigrationClosed ? '1' : '0');
        sb.append(col.immigrationIncentives ? '1' : '0').append('|');
        for (int i = 0; i < col.industries.length; i++) {
            ColonyTracker.IndustrySnapshot ind = col.industries[i];
            sb.append(ind.id).append(':');
            sb.append(ind.building ? '1' : '0');
            sb.append(ind.upgrading ? '1' : '0');
            sb.append((int)(ind.buildProgress * 100)).append(':');
            sb.append(ind.disrupted ? '1' : '0');
            sb.append((int)ind.disruptedDays).append(':');
            sb.append(ind.aiCoreId != null ? ind.aiCoreId : "n").append(':');
            sb.append(ind.canUpgrade ? '1' : '0');
            sb.append(ind.canDowngrade ? '1' : '0').append(':');
            sb.append(ind.income).append(':').append(ind.upkeep).append(':');
            sb.append(ind.specialItemId != null ? ind.specialItemId : "n").append(':');
            sb.append(ind.improved ? '1' : '0');
            sb.append(ind.canImprove ? '1' : '0');
            sb.append(ind.canAcceptItems ? '1' : '0');
            sb.append(ind.currentSpecializationName != null ? ind.currentSpecializationName : "n");
            sb.append(',');
        }
        sb.append('|');
        for (int i = 0; i < col.constructionQueue.length; i++) {
            sb.append(col.constructionQueue[i].id).append(',');
        }
        sb.append('|');
        sb.append(col.availableAlpha).append(':');
        sb.append(col.availableBeta).append(':');
        sb.append(col.availableGamma);
        return sb.toString();
    }

    // ========================================================================
    // Sidebar
    // ========================================================================

    private JPanel createSidebar() {
        JPanel sidebar = new JPanel(new BorderLayout());
        sidebar.setBackground(NexusFrame.BG_SECONDARY);
        sidebar.setPreferredSize(new Dimension(180, 0));
        sidebar.setBorder(new MatteBorder(0, 0, 0, 1, NexusFrame.BORDER));

        // Header
        JLabel header = new JLabel("  COLONIES");
        header.setFont(NexusFrame.FONT_HEADER);
        header.setForeground(NexusFrame.CYAN);
        header.setPreferredSize(new Dimension(180, 32));
        header.setBorder(new MatteBorder(0, 0, 1, 0, NexusFrame.BORDER));
        sidebar.add(header, BorderLayout.NORTH);

        // Scrollable colony list
        sidebarContent = new JPanel();
        sidebarContent.setLayout(new BoxLayout(sidebarContent, BoxLayout.Y_AXIS));
        sidebarContent.setBackground(NexusFrame.BG_SECONDARY);

        JScrollPane sideScroll = new JScrollPane(sidebarContent);
        sideScroll.setBackground(NexusFrame.BG_SECONDARY);
        sideScroll.getViewport().setBackground(NexusFrame.BG_SECONDARY);
        sideScroll.setBorder(null);
        sideScroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        sideScroll.getVerticalScrollBar().setUnitIncrement(16);
        sidebar.add(sideScroll, BorderLayout.CENTER);

        return sidebar;
    }

    private void rebuildSidebar() {
        sidebarContent.removeAll();
        sidebarButtons.clear();
        sidebarIncomeLabels.clear();

        ColonyTracker.GlobalSnapshot snap = data;
        if (snap == null || snap.colonies.length == 0) {
            JLabel noCol = new JLabel("  No colonies");
            noCol.setFont(NexusFrame.FONT_SMALL);
            noCol.setForeground(NexusFrame.TEXT_MUTED);
            noCol.setAlignmentX(Component.LEFT_ALIGNMENT);
            sidebarContent.add(Box.createVerticalStrut(12));
            sidebarContent.add(noCol);
            sidebarContent.revalidate();
            sidebarContent.repaint();
            return;
        }

        sidebarContent.add(Box.createVerticalStrut(4));

        for (int i = 0; i < snap.colonies.length; i++) {
            final ColonyTracker.ColonySnapshot col = snap.colonies[i];
            final String colId = col.id;
            boolean selected = colId.equals(selectedColonyId);

            final JPanel btn = new JPanel(new BorderLayout()) {
                protected void paintComponent(Graphics g) {
                    super.paintComponent(g);
                    if (colId.equals(selectedColonyId)) {
                        g.setColor(NexusFrame.CYAN);
                        g.fillRect(0, 0, 3, getHeight());
                    }
                }
            };
            btn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 52));
            btn.setPreferredSize(new Dimension(180, 52));
            btn.setBackground(selected ? NexusFrame.BG_CARD : NexusFrame.BG_SECONDARY);
            btn.setBorder(new EmptyBorder(6, 14, 6, 8));
            btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

            // Top line: colony name
            JLabel nameLabel = new JLabel(NexusFrame.truncate(col.name, 18));
            nameLabel.setFont(NexusFrame.FONT_BODY);
            nameLabel.setForeground(selected ? NexusFrame.TEXT_PRIMARY : NexusFrame.TEXT_SECONDARY);

            // Bottom line: size + income
            String incomeStr = formatCredits(col.netIncome);
            JLabel infoLabel = new JLabel("Size " + col.size + "   " + incomeStr);
            infoLabel.setFont(NexusFrame.FONT_SMALL);
            infoLabel.setForeground(col.netIncome >= 0 ? NexusFrame.GREEN : NexusFrame.RED);

            JPanel textPanel = new JPanel();
            textPanel.setLayout(new BoxLayout(textPanel, BoxLayout.Y_AXIS));
            textPanel.setOpaque(false);
            textPanel.add(nameLabel);
            textPanel.add(Box.createVerticalStrut(2));
            textPanel.add(infoLabel);
            btn.add(textPanel, BorderLayout.CENTER);

            btn.addMouseListener(new MouseAdapter() {
                public void mouseClicked(MouseEvent e) {
                    selectedColonyId = colId;
                    showBuildView = false;
                    availableIndustries = null;
                    rebuildSidebar();
                    rebuildDetail();
                    lastDetailShape = computeDetailShape(getSelectedColony());
                }
                public void mouseEntered(MouseEvent e) {
                    if (!colId.equals(selectedColonyId)) {
                        btn.setBackground(NexusFrame.BG_CARD);
                    }
                }
                public void mouseExited(MouseEvent e) {
                    if (!colId.equals(selectedColonyId)) {
                        btn.setBackground(NexusFrame.BG_SECONDARY);
                    }
                }
            });

            sidebarButtons.put(colId, btn);
            sidebarIncomeLabels.put(colId, infoLabel);
            sidebarContent.add(btn);
        }

        sidebarContent.add(Box.createVerticalGlue());
        sidebarContent.revalidate();
        sidebarContent.repaint();
    }

    private void updateSidebarValues() {
        ColonyTracker.GlobalSnapshot snap = data;
        if (snap == null) return;
        for (int i = 0; i < snap.colonies.length; i++) {
            ColonyTracker.ColonySnapshot col = snap.colonies[i];
            JLabel incLabel = sidebarIncomeLabels.get(col.id);
            if (incLabel != null) {
                String incomeStr = formatCredits(col.netIncome);
                incLabel.setText("Size " + col.size + "   " + incomeStr);
                incLabel.setForeground(col.netIncome >= 0 ? NexusFrame.GREEN : NexusFrame.RED);
            }
            JPanel btn = sidebarButtons.get(col.id);
            if (btn != null) {
                boolean selected = col.id.equals(selectedColonyId);
                btn.setBackground(selected ? NexusFrame.BG_CARD : NexusFrame.BG_SECONDARY);
                btn.repaint();
            }
        }
    }

    // ========================================================================
    // Detail view
    // ========================================================================

    private void showNoColonySelected() {
        detailPanel.removeAll();
        detailPanel.add(Box.createVerticalStrut(80));
        JLabel msg = new JLabel("Select a colony");
        msg.setFont(NexusFrame.FONT_TITLE);
        msg.setForeground(NexusFrame.TEXT_MUTED);
        msg.setAlignmentX(Component.CENTER_ALIGNMENT);
        detailPanel.add(msg);
        detailPanel.revalidate();
        detailPanel.repaint();
    }

    private ColonyTracker.ColonySnapshot getSelectedColony() {
        ColonyTracker.GlobalSnapshot snap = data;
        if (snap == null || selectedColonyId == null) return null;
        for (int i = 0; i < snap.colonies.length; i++) {
            if (snap.colonies[i].id.equals(selectedColonyId)) return snap.colonies[i];
        }
        return null;
    }

    private void rebuildDetail() {
        if (showUpgradeView) {
            rebuildUpgradeView();
            return;
        }
        if (showBuildView) {
            rebuildBuildView();
            return;
        }

        ColonyTracker.ColonySnapshot colony = getSelectedColony();
        if (colony == null) {
            showNoColonySelected();
            return;
        }

        // Save scroll position
        int scrollPos = detailScroll != null
                ? detailScroll.getVerticalScrollBar().getValue() : 0;

        detailPanel.removeAll();
        int pad = 14;

        // Header section
        detailPanel.add(createHeaderSection(colony, pad));
        detailPanel.add(createSeparator(pad));

        // Industries section
        detailPanel.add(createIndustriesSection(colony, pad));

        // Construction queue
        if (colony.constructionQueue.length > 0) {
            detailPanel.add(createSeparator(pad));
            detailPanel.add(createQueueSection(colony, pad));
        }

        // Settings
        detailPanel.add(createSeparator(pad));
        detailPanel.add(createSettingsSection(colony, pad));

        // Conditions
        if (colony.conditions.length > 0) {
            detailPanel.add(createSeparator(pad));
            detailPanel.add(createConditionsSection(colony, pad));
        }

        detailPanel.add(Box.createVerticalGlue());

        // Constrain section panels so BoxLayout doesn't stretch them
        constrainChildHeights(detailPanel);

        detailPanel.revalidate();
        detailPanel.repaint();

        // Restore scroll position
        final int savedPos = scrollPos;
        SwingUtilities.invokeLater(new Runnable() {
            public void run() {
                if (detailScroll != null) {
                    detailScroll.getVerticalScrollBar().setValue(savedPos);
                }
            }
        });
    }

    /** Constrain each JPanel child to its preferred height so BoxLayout gives extra space only to glue. */
    private static void constrainChildHeights(JPanel parent) {
        for (int c = 0; c < parent.getComponentCount(); c++) {
            Component comp = parent.getComponent(c);
            if (comp instanceof JPanel) {
                Dimension pref = comp.getPreferredSize();
                comp.setMaximumSize(new Dimension(Integer.MAX_VALUE, pref.height));
            }
        }
    }

    // ── Header ──

    private JPanel createHeaderSection(ColonyTracker.ColonySnapshot col, int pad) {
        JPanel section = new JPanel();
        section.setLayout(new BoxLayout(section, BoxLayout.Y_AXIS));
        section.setBackground(NexusFrame.BG_PRIMARY);
        section.setBorder(new EmptyBorder(pad, pad, 4, pad));
        section.setAlignmentX(Component.LEFT_ALIGNMENT);

        // Row 1: Colony name + system
        JPanel row1 = flowPanel();
        JLabel nameLabel = new JLabel(col.name);
        nameLabel.setFont(NexusFrame.FONT_TITLE);
        nameLabel.setForeground(NexusFrame.CYAN);
        row1.add(nameLabel);
        row1.add(Box.createHorizontalStrut(16));

        JLabel sizeLabel = new JLabel("Size " + col.size);
        sizeLabel.setFont(NexusFrame.FONT_BODY);
        sizeLabel.setForeground(NexusFrame.TEXT_PRIMARY);
        row1.add(sizeLabel);
        row1.add(Box.createHorizontalStrut(16));

        JLabel sysLabel = new JLabel(col.systemName);
        sysLabel.setFont(NexusFrame.FONT_BODY);
        sysLabel.setForeground(NexusFrame.TEXT_SECONDARY);
        row1.add(sysLabel);

        section.add(row1);
        section.add(Box.createVerticalStrut(6));

        // Row 2: Stats
        JPanel row2 = flowPanel();
        row2.add(statLabel("Stability: " + col.stability, NexusFrame.GREEN));
        row2.add(Box.createHorizontalStrut(16));
        row2.add(statLabel("Hazard: " + (int)(col.hazard * 100) + "%",
                col.hazard <= 1f ? NexusFrame.GREEN : NexusFrame.ORANGE));
        row2.add(Box.createHorizontalStrut(16));
        row2.add(statLabel("Net: " + formatCredits(col.netIncome),
                col.netIncome >= 0 ? NexusFrame.GREEN : NexusFrame.RED));
        row2.add(Box.createHorizontalStrut(16));
        row2.add(statLabel("Admin: " + NexusFrame.truncate(col.adminName, 14),
                NexusFrame.TEXT_SECONDARY));

        section.add(row2);
        section.add(Box.createVerticalStrut(4));

        // Row 3: AI core availability + credits
        JPanel row3 = flowPanel();
        row3.add(statLabel("Storage Cores:", NexusFrame.TEXT_MUTED));
        row3.add(Box.createHorizontalStrut(6));
        row3.add(statLabel("A:" + col.availableAlpha,
                col.availableAlpha > 0 ? NexusFrame.CYAN : NexusFrame.TEXT_MUTED));
        row3.add(Box.createHorizontalStrut(6));
        row3.add(statLabel("B:" + col.availableBeta,
                col.availableBeta > 0 ? NexusFrame.YELLOW : NexusFrame.TEXT_MUTED));
        row3.add(Box.createHorizontalStrut(6));
        row3.add(statLabel("G:" + col.availableGamma,
                col.availableGamma > 0 ? NexusFrame.GREEN : NexusFrame.TEXT_MUTED));

        ColonyTracker.GlobalSnapshot snap = data;
        if (snap != null) {
            row3.add(Box.createHorizontalStrut(20));
            JLabel cLabel = statLabel("Credits: " + formatCredits(snap.playerCredits),
                    NexusFrame.YELLOW);
            row3.add(cLabel);
            creditsLiveLabel = cLabel;
            creditsLivePrefix = "Credits: ";
        }

        section.add(row3);

        return section;
    }

    // ── Industries ──

    private JPanel createIndustriesSection(final ColonyTracker.ColonySnapshot col, int pad) {
        JPanel section = new JPanel();
        section.setLayout(new BoxLayout(section, BoxLayout.Y_AXIS));
        section.setBackground(NexusFrame.BG_PRIMARY);
        section.setBorder(new EmptyBorder(8, pad, 4, pad));
        section.setAlignmentX(Component.LEFT_ALIGNMENT);

        // Title row with [+ Build] button
        JPanel titleRow = flowPanel();
        JLabel title = new JLabel("INDUSTRIES (" + col.numIndustries + "/" + col.maxIndustries + ")");
        title.setFont(NexusFrame.FONT_HEADER);
        title.setForeground(NexusFrame.TEXT_PRIMARY);
        titleRow.add(title);
        titleRow.add(Box.createHorizontalStrut(16));

        JButton buildBtn = createActionButton("+ Build", NexusFrame.GREEN, new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                requestAvailableIndustries(col.id);
            }
        });
        titleRow.add(buildBtn);

        section.add(titleRow);
        section.add(Box.createVerticalStrut(8));

        // Industry rows
        for (int i = 0; i < col.industries.length; i++) {
            section.add(createIndustryRow(col, col.industries[i]));
            if (i < col.industries.length - 1) {
                section.add(Box.createVerticalStrut(4));
            }
        }

        return section;
    }

    private JPanel createIndustryRow(final ColonyTracker.ColonySnapshot col,
            final ColonyTracker.IndustrySnapshot ind) {

        boolean isPopulation = "population".equals(ind.id);

        JPanel row = new JPanel(new BorderLayout(8, 0));
        row.setBackground(NexusFrame.BG_CARD);
        row.setBorder(new CompoundBorder(
            new LineBorder(NexusFrame.BORDER, 1),
            new EmptyBorder(8, 10, 8, 10)
        ));
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 62));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);

        // Left: name + status
        JPanel left = new JPanel();
        left.setLayout(new BoxLayout(left, BoxLayout.Y_AXIS));
        left.setOpaque(false);

        // Show star for improved industries, specialization name if applicable
        String displayName = ind.improved ? "\u2605 " + ind.name : ind.name;
        if (ind.currentSpecializationName != null && !ind.currentSpecializationName.equals(ind.name)) {
            displayName += " [" + ind.currentSpecializationName + "]";
        }
        JLabel nameLabel = new JLabel(displayName);
        nameLabel.setFont(NexusFrame.FONT_BODY);
        nameLabel.setForeground(ind.improved ? NexusFrame.YELLOW : NexusFrame.TEXT_PRIMARY);
        left.add(nameLabel);

        // Status line
        JPanel statusRow = flowPanel();
        if (ind.building) {
            String progText;
            if (isPopulation) {
                progText = "GROWING ";
            } else {
                progText = ind.upgrading ? "UPGRADING " : "BUILDING ";
            }
            progText += ind.buildProgressText;
            if (ind.buildDaysText.length() > 0) progText += "  " + ind.buildDaysText;
            statusRow.add(statLabel(progText, NexusFrame.YELLOW));
        } else if (ind.disrupted) {
            statusRow.add(statLabel("DISRUPTED " + (int) ind.disruptedDays + "d", NexusFrame.RED));
        } else {
            statusRow.add(statLabel("OK", NexusFrame.GREEN));
        }

        if (ind.income > 0 || ind.upkeep > 0) {
            statusRow.add(Box.createHorizontalStrut(10));
            if (ind.income > 0) {
                statusRow.add(statLabel("+" + formatCredits(ind.income), NexusFrame.GREEN));
                statusRow.add(Box.createHorizontalStrut(6));
            }
            if (ind.upkeep > 0) {
                statusRow.add(statLabel("-" + formatCredits(ind.upkeep), NexusFrame.RED));
            }
        }

        if (ind.aiCoreId != null) {
            statusRow.add(Box.createHorizontalStrut(10));
            statusRow.add(statLabel("AI: " + prettifyCore(ind.aiCoreId), coreColor(ind.aiCoreId)));
        }

        if (ind.specialItemName != null) {
            statusRow.add(Box.createHorizontalStrut(10));
            statusRow.add(statLabel("Item: " + ind.specialItemName, NexusFrame.PURPLE));
        }

        left.add(statusRow);
        row.add(left, BorderLayout.CENTER);

        // Tooltip from tracker snapshot
        if (ind.tooltipHtml != null && !ind.tooltipHtml.isEmpty()) {
            row.setToolTipText(ind.tooltipHtml);
        }

        // Right: action buttons
        JPanel buttons = flowPanel();
        buttons.setBorder(new EmptyBorder(0, 0, 0, 0));

        final String marketId = col.id;
        final String indId = ind.id;
        long credits = data != null ? data.playerCredits : 0;
        int storyPoints = data != null ? data.playerStoryPoints : 0;

        // For P&I: always show management buttons even when building (population growth)
        // For other industries: only show Cancel when building
        boolean showManagementButtons = !ind.building || isPopulation;

        if (ind.building && !isPopulation) {
            // Regular industry building: show Cancel
            buttons.add(createActionButton("Cancel", NexusFrame.RED, new ActionListener() {
                public void actionPerformed(ActionEvent e) {
                    cancelBuild(marketId, indId);
                }
            }));
        }

        if (showManagementButtons) {
            // Upgrade button - show when upgrade path exists, disable if can't currently upgrade
            if (ind.upgradeName != null) {
                final String upgTargetName = ind.upgradeName;
                final int upgCost = ind.upgradeCost;
                final int upgBuildT = ind.upgradeBuildTime;
                final String curName = ind.name;
                boolean canAfford = credits >= ind.upgradeCost;
                boolean canDoUpgrade = ind.canUpgrade && canAfford;
                JButton upBtn = createActionButton("Upgrade",
                        canDoUpgrade ? NexusFrame.CYAN : NexusFrame.TEXT_MUTED,
                        canDoUpgrade ? new ActionListener() {
                    public void actionPerformed(ActionEvent e) {
                        showUpgradeInfo(marketId, indId, curName, upgTargetName, upgCost, upgBuildT);
                    }
                } : null);
                if (!ind.canUpgrade) {
                    upBtn.setToolTipText("Cannot upgrade right now");
                    upBtn.setEnabled(false);
                } else if (!canAfford) {
                    upBtn.setToolTipText("Not enough credits (" + formatCredits(ind.upgradeCost) + ")");
                    upBtn.setEnabled(false);
                } else {
                    upBtn.setToolTipText("Upgrade to " + ind.upgradeName);
                }
                buttons.add(upBtn);
                buttons.add(Box.createHorizontalStrut(4));
            }

            // Downgrade button - show when downgrade path exists, disable if can't currently downgrade
            if (ind.downgradeName != null) {
                JButton downBtn = createActionButton("Downgrade",
                        ind.canDowngrade ? NexusFrame.ORANGE : NexusFrame.TEXT_MUTED,
                        ind.canDowngrade ? new ActionListener() {
                    public void actionPerformed(ActionEvent e) {
                        downgradeIndustry(marketId, indId);
                    }
                } : null);
                if (!ind.canDowngrade) {
                    downBtn.setToolTipText("Cannot downgrade right now");
                    downBtn.setEnabled(false);
                } else {
                    downBtn.setToolTipText("Downgrade to " + ind.downgradeName);
                }
                buttons.add(downBtn);
                buttons.add(Box.createHorizontalStrut(4));
            }

            // Specialization button (Industrial Evolution)
            if (ind.specializations != null && ind.specializations.length > 1) {
                buttons.add(createSpecializationButton(marketId, indId, ind));
                buttons.add(Box.createHorizontalStrut(4));
            }

            // Install Item button - show if industry can accept items or has one installed
            if (ind.canAcceptItems || ind.specialItemId != null) {
                buttons.add(createItemButton(marketId, indId, ind.specialItemId,
                        ind.specialItemName, col));
                buttons.add(Box.createHorizontalStrut(4));
            }

            // AI Core button
            buttons.add(createAICoreButton(marketId, indId, ind.aiCoreId, col));
            buttons.add(Box.createHorizontalStrut(4));

            // Improve button (vanilla feature - story points)
            if (ind.canImprove && !ind.improved) {
                boolean canAffordSP = storyPoints >= ind.improveStoryCost;
                String improveText = "Improve (" + ind.improveStoryCost + " SP)";
                JButton impBtn = createActionButton(improveText,
                        canAffordSP ? NexusFrame.YELLOW : NexusFrame.TEXT_MUTED,
                        canAffordSP ? new ActionListener() {
                    public void actionPerformed(ActionEvent e) {
                        improveIndustry(marketId, indId);
                    }
                } : null);
                if (!canAffordSP) {
                    impBtn.setToolTipText("Not enough story points (" + ind.improveStoryCost + " needed)");
                    impBtn.setEnabled(false);
                }
                buttons.add(impBtn);
                buttons.add(Box.createHorizontalStrut(4));
            }

            // Remove button (not for Population & Infrastructure)
            if (!isPopulation) {
                buttons.add(createActionButton("Remove", NexusFrame.RED, new ActionListener() {
                    public void actionPerformed(ActionEvent e) {
                        removeIndustry(marketId, indId);
                    }
                }));
            }
        }

        row.add(buttons, BorderLayout.EAST);
        return row;
    }

    /** Check if storage has any special items compatible with this industry. */
    private boolean hasCompatibleStorageItems(ColonyTracker.ColonySnapshot col, String industryId) {
        if (col.storageSpecialItems == null) return false;
        for (int i = 0; i < col.storageSpecialItems.length; i++) {
            if (isItemCompatible(col.storageSpecialItems[i].params, industryId)) {
                return true;
            }
        }
        return false;
    }

    /** Check if a special item's params include the given industry ID. */
    private boolean isItemCompatible(String params, String industryId) {
        if (params == null || params.isEmpty()) return false;
        String[] ids = params.split(",");
        for (int i = 0; i < ids.length; i++) {
            if (ids[i].trim().equals(industryId)) return true;
        }
        return false;
    }

    // ── Construction Queue ──

    private JPanel createQueueSection(final ColonyTracker.ColonySnapshot col, int pad) {
        JPanel section = new JPanel();
        section.setLayout(new BoxLayout(section, BoxLayout.Y_AXIS));
        section.setBackground(NexusFrame.BG_PRIMARY);
        section.setBorder(new EmptyBorder(8, pad, 4, pad));
        section.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel title = new JLabel("CONSTRUCTION QUEUE");
        title.setFont(NexusFrame.FONT_HEADER);
        title.setForeground(NexusFrame.TEXT_PRIMARY);
        section.add(title);
        section.add(Box.createVerticalStrut(6));

        for (int i = 0; i < col.constructionQueue.length; i++) {
            final ColonyTracker.QueueItemSnapshot item = col.constructionQueue[i];
            final String marketId = col.id;
            final String itemId = item.id;

            JPanel row = flowPanel();
            row.setBorder(new EmptyBorder(3, 4, 3, 4));

            JLabel numLabel = new JLabel((i + 1) + ". ");
            numLabel.setFont(NexusFrame.FONT_SMALL);
            numLabel.setForeground(NexusFrame.TEXT_MUTED);
            row.add(numLabel);

            JLabel nameLabel = new JLabel(item.name);
            nameLabel.setFont(NexusFrame.FONT_BODY);
            nameLabel.setForeground(NexusFrame.TEXT_PRIMARY);
            row.add(nameLabel);

            row.add(Box.createHorizontalStrut(8));
            row.add(statLabel("(" + formatCredits(item.cost) + ")", NexusFrame.TEXT_MUTED));
            row.add(Box.createHorizontalStrut(12));

            row.add(createSmallButton("\u25B2", new ActionListener() {
                public void actionPerformed(ActionEvent e) {
                    queueMoveUp(marketId, itemId);
                }
            }));
            row.add(Box.createHorizontalStrut(2));
            row.add(createSmallButton("\u25BC", new ActionListener() {
                public void actionPerformed(ActionEvent e) {
                    queueMoveDown(marketId, itemId);
                }
            }));
            row.add(Box.createHorizontalStrut(2));
            row.add(createSmallButton("X", new ActionListener() {
                public void actionPerformed(ActionEvent e) {
                    queueRemove(marketId, itemId);
                }
            }));

            section.add(row);
        }

        return section;
    }

    // ── Settings ──

    private JPanel createSettingsSection(final ColonyTracker.ColonySnapshot col, int pad) {
        JPanel section = new JPanel();
        section.setLayout(new BoxLayout(section, BoxLayout.Y_AXIS));
        section.setBackground(NexusFrame.BG_PRIMARY);
        section.setBorder(new EmptyBorder(8, pad, 4, pad));
        section.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel title = new JLabel("SETTINGS");
        title.setFont(NexusFrame.FONT_HEADER);
        title.setForeground(NexusFrame.TEXT_PRIMARY);
        section.add(title);
        section.add(Box.createVerticalStrut(6));

        JPanel row = flowPanel();
        final String marketId = col.id;

        row.add(createToggleButton("Free Port", col.freePort, NexusFrame.CYAN, new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                toggleFreePort(marketId);
            }
        }));
        row.add(Box.createHorizontalStrut(8));

        row.add(createToggleButton("Immigration", !col.immigrationClosed, NexusFrame.GREEN, new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                toggleImmigration(marketId);
            }
        }));
        row.add(Box.createHorizontalStrut(8));

        row.add(createToggleButton("Incentives", col.immigrationIncentives, NexusFrame.YELLOW, new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                toggleIncentives(marketId);
            }
        }));

        section.add(row);
        return section;
    }

    // ── Conditions ──

    private JPanel createConditionsSection(ColonyTracker.ColonySnapshot col, int pad) {
        JPanel section = new JPanel();
        section.setLayout(new BoxLayout(section, BoxLayout.Y_AXIS));
        section.setBackground(NexusFrame.BG_PRIMARY);
        section.setBorder(new EmptyBorder(8, pad, 8, pad));
        section.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel title = new JLabel("CONDITIONS");
        title.setFont(NexusFrame.FONT_HEADER);
        title.setForeground(NexusFrame.TEXT_PRIMARY);
        section.add(title);
        section.add(Box.createVerticalStrut(6));

        // Max 5 conditions per row before wrapping to next line
        int perRow = 5;
        JPanel currentRow = null;
        for (int i = 0; i < col.conditions.length; i++) {
            if (i % perRow == 0) {
                if (currentRow != null) {
                    section.add(currentRow);
                    section.add(Box.createVerticalStrut(3));
                }
                currentRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
                currentRow.setOpaque(false);
                currentRow.setAlignmentX(Component.LEFT_ALIGNMENT);
            }
            ColonyTracker.ConditionSnapshot cond = col.conditions[i];
            JLabel condLabel = new JLabel(cond.name);
            condLabel.setFont(NexusFrame.FONT_SMALL);
            condLabel.setForeground(cond.planetary ? NexusFrame.TEXT_SECONDARY : NexusFrame.ORANGE);
            condLabel.setBorder(new CompoundBorder(
                new LineBorder(NexusFrame.BORDER, 1),
                new EmptyBorder(2, 6, 2, 6)
            ));
            currentRow.add(condLabel);
        }
        if (currentRow != null) {
            section.add(currentRow);
        }

        return section;
    }

    // ========================================================================
    // Build industry view
    // ========================================================================

    private void requestAvailableIndustries(final String marketId) {
        GameDataBridge bridge = GameDataBridge.getInstance();
        if (bridge == null) {
            showFeedback("Game not connected", false);
            return;
        }

        showFeedback("Loading available industries...", true);
        buildFetchCommandId = bridge.enqueueCommand(new GameDataBridge.GameCommand() {
            public String execute() {
                MarketAPI market = Global.getSector().getEconomy().getMarket(marketId);
                if (market == null) return "[]";

                java.util.List<IndustrySpecAPI> specs = Global.getSettings().getAllIndustrySpecs();
                JSONArray arr = new JSONArray();

                // Collect queued industry IDs
                java.util.HashSet<String> queuedIds = new java.util.HashSet<String>();
                ConstructionQueue queue = market.getConstructionQueue();
                java.util.List<ConstructionQueue.ConstructionQueueItem> qItems = queue.getItems();
                for (int q = 0; q < qItems.size(); q++) {
                    queuedIds.add(qItems.get(q).id);
                }

                for (int i = 0; i < specs.size(); i++) {
                    IndustrySpecAPI spec = specs.get(i);
                    try {
                        if (spec.hasTag("do_not_show_in_build_dialog")) continue;

                        String status = "available";
                        if (market.hasIndustry(spec.getId())) {
                            status = "built";
                        } else if (queuedIds.contains(spec.getId())) {
                            status = "queued";
                        } else {
                            Industry temp = market.instantiateIndustry(spec.getId());
                            if (!temp.isAvailableToBuild()) continue;
                        }

                        JSONObject o = new JSONObject();
                        o.put("id", spec.getId());
                        o.put("name", spec.getName());
                        o.put("cost", (int) spec.getCost());
                        o.put("buildTime", (int) spec.getBuildTime());
                        o.put("upkeep", (int) spec.getUpkeep());
                        o.put("status", status);
                        o.put("isIndustry", spec.hasTag("industry"));

                        // Build tooltip HTML for the spec
                        String desc = spec.getDesc();
                        if (desc == null) desc = "";
                        if (desc.length() > 250) desc = desc.substring(0, 247) + "...";
                        StringBuilder tip = new StringBuilder();
                        tip.append("<html><div style='width:320px;padding:2px'>");
                        tip.append("<b>").append(spec.getName().replace("&","&amp;").replace("<","&lt;")).append("</b><br/>");
                        if (!desc.isEmpty()) {
                            tip.append(desc.replace("&","&amp;").replace("<","&lt;")).append("<br/>");
                        }
                        tip.append("<br/><b>Cost:</b> ").append(String.format("%,d", (int) spec.getCost()));
                        tip.append("&nbsp;&nbsp;<b>Build time:</b> ").append((int) spec.getBuildTime()).append(" days");
                        tip.append("<br/><b>Monthly upkeep:</b> ").append(String.format("%,d", (int) spec.getUpkeep()));
                        tip.append("</div></html>");
                        o.put("tooltip", tip.toString());

                        arr.put(o);
                    } catch (Exception e) {
                        // Skip problematic specs
                    }
                }
                return arr.toString();
            }
        });
    }

    private void rebuildBuildView() {
        ColonyTracker.ColonySnapshot colony = getSelectedColony();
        if (colony == null) {
            showBuildView = false;
            rebuildDetail();
            return;
        }

        detailPanel.removeAll();
        int pad = 14;

        JPanel header = flowPanel();
        header.setBorder(new EmptyBorder(pad, pad, 8, pad));

        JButton backBtn = createActionButton("\u2190 Back", NexusFrame.TEXT_SECONDARY, new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                showBuildView = false;
                availableIndustries = null;
                buildListPanel = null;
                if (detailScroll != null) {
                    detailScroll.getVerticalScrollBar().setValue(0);
                }
                rebuildDetail();
                lastDetailShape = computeDetailShape(getSelectedColony());
            }
        });
        header.add(backBtn);
        header.add(Box.createHorizontalStrut(12));

        JLabel title = new JLabel("BUILD INDUSTRY - " + colony.name
                + " (" + colony.numIndustries + "/" + colony.maxIndustries + ")");
        title.setFont(NexusFrame.FONT_TITLE);
        title.setForeground(NexusFrame.CYAN);
        header.add(title);

        ColonyTracker.GlobalSnapshot snap = data;
        if (snap != null) {
            header.add(Box.createHorizontalStrut(20));
            JLabel cLabel = statLabel("Credits: " + formatCredits(snap.playerCredits),
                    NexusFrame.YELLOW);
            header.add(cLabel);
            creditsLiveLabel = cLabel;
            creditsLivePrefix = "Credits: ";
        }

        detailPanel.add(header);

        // Search field
        JPanel searchRow = flowPanel();
        searchRow.setBorder(new EmptyBorder(0, pad, 8, pad));
        JLabel searchLabel = new JLabel("Search: ");
        searchLabel.setFont(NexusFrame.FONT_SMALL);
        searchLabel.setForeground(NexusFrame.TEXT_SECONDARY);
        searchRow.add(searchLabel);

        final JTextField searchField = new JTextField(30);
        searchField.setFont(NexusFrame.FONT_BODY);
        searchField.setForeground(NexusFrame.TEXT_PRIMARY);
        searchField.setBackground(NexusFrame.BG_CARD);
        searchField.setCaretColor(NexusFrame.CYAN);
        searchField.setBorder(new CompoundBorder(
            new LineBorder(NexusFrame.BORDER, 1),
            new EmptyBorder(4, 8, 4, 8)
        ));
        searchRow.add(searchField);

        detailPanel.add(searchRow);
        detailPanel.add(createSeparator(pad));

        // Available industries list
        final JPanel listPanel = new JPanel();
        listPanel.setLayout(new BoxLayout(listPanel, BoxLayout.Y_AXIS));
        listPanel.setBackground(NexusFrame.BG_PRIMARY);
        listPanel.setBorder(new EmptyBorder(4, pad, pad, pad));
        listPanel.setAlignmentX(Component.LEFT_ALIGNMENT);

        if (availableIndustries == null || availableIndustries.length() == 0) {
            JLabel loading = new JLabel("Loading...");
            loading.setFont(NexusFrame.FONT_BODY);
            loading.setForeground(NexusFrame.TEXT_MUTED);
            listPanel.add(loading);
        } else {
            populateBuildList(listPanel, availableIndustries, "");
        }

        detailPanel.add(listPanel);
        detailPanel.add(Box.createVerticalGlue());

        // Store refs for live updates
        buildListPanel = listPanel;
        buildSearchQuery = "";
        lastBuildViewCredits = data != null ? data.playerCredits : 0;

        // Search filter
        searchField.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { filterBuildList(listPanel, searchField); }
            public void removeUpdate(DocumentEvent e) { filterBuildList(listPanel, searchField); }
            public void changedUpdate(DocumentEvent e) { filterBuildList(listPanel, searchField); }
        });

        constrainChildHeights(detailPanel);
        detailPanel.revalidate();
        detailPanel.repaint();
    }

    private void filterBuildList(JPanel listPanel, JTextField searchField) {
        if (availableIndustries == null) return;
        buildSearchQuery = searchField.getText().toLowerCase().trim();
        listPanel.removeAll();
        populateBuildList(listPanel, availableIndustries, buildSearchQuery);
        listPanel.revalidate();
        listPanel.repaint();
    }

    private void populateBuildList(JPanel listPanel, JSONArray industries, String query) {
        final String marketId = selectedColonyId;
        long credits = data != null ? data.playerCredits : 0;
        int count = 0;

        for (int i = 0; i < industries.length(); i++) {
            JSONObject ind = industries.optJSONObject(i);
            if (ind == null) continue;

            final String indId = ind.optString("id", "");
            String indName = ind.optString("name", indId);
            int cost = ind.optInt("cost", 0);
            int buildTime = ind.optInt("buildTime", 0);
            int upkeep = ind.optInt("upkeep", 0);
            String tooltip = ind.optString("tooltip", "");
            String status = ind.optString("status", "available");
            boolean isIndustry = ind.optBoolean("isIndustry", false);
            boolean isBuilt = "built".equals(status);
            boolean isQueued = "queued".equals(status);
            boolean canBuild = !isBuilt && !isQueued;
            boolean canAfford = credits >= cost;

            if (query.length() > 0 && !indName.toLowerCase().contains(query)) continue;

            JPanel row = new JPanel(new BorderLayout(8, 0));
            row.setBackground(NexusFrame.BG_CARD);
            row.setBorder(new CompoundBorder(
                new LineBorder(isBuilt || isQueued ? NexusFrame.BORDER : NexusFrame.BORDER, 1),
                new EmptyBorder(6, 10, 6, 10)
            ));
            row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 44));
            row.setAlignmentX(Component.LEFT_ALIGNMENT);

            // Info
            JPanel info = flowPanel();
            JLabel nameLabel = new JLabel(indName);
            nameLabel.setFont(NexusFrame.FONT_BODY);
            nameLabel.setForeground(canBuild && canAfford ? NexusFrame.TEXT_PRIMARY : NexusFrame.TEXT_MUTED);
            info.add(nameLabel);
            info.add(Box.createHorizontalStrut(6));
            info.add(statLabel(isIndustry ? "Industry" : "Structure",
                    isIndustry ? NexusFrame.ORANGE : NexusFrame.TEXT_MUTED));
            info.add(Box.createHorizontalStrut(12));

            if (isBuilt) {
                info.add(statLabel("Built", NexusFrame.GREEN));
            } else if (isQueued) {
                info.add(statLabel("Queued", NexusFrame.YELLOW));
            } else {
                info.add(statLabel("Cost: " + formatCredits(cost),
                        canAfford ? NexusFrame.YELLOW : NexusFrame.RED));
                info.add(Box.createHorizontalStrut(8));
                info.add(statLabel(buildTime + "d", NexusFrame.TEXT_MUTED));
                info.add(Box.createHorizontalStrut(8));
                info.add(statLabel("Upkeep: " + formatCredits(upkeep), NexusFrame.ORANGE));
            }

            row.add(info, BorderLayout.CENTER);

            // Tooltip from spec
            if (tooltip.length() > 0) {
                row.setToolTipText(tooltip);
            }

            // Build button (or status label)
            if (isBuilt) {
                JLabel builtLabel = new JLabel("Built");
                builtLabel.setFont(NexusFrame.FONT_SMALL);
                builtLabel.setForeground(NexusFrame.TEXT_MUTED);
                row.add(builtLabel, BorderLayout.EAST);
            } else if (isQueued) {
                JLabel queuedLabel = new JLabel("In Queue");
                queuedLabel.setFont(NexusFrame.FONT_SMALL);
                queuedLabel.setForeground(NexusFrame.TEXT_MUTED);
                row.add(queuedLabel, BorderLayout.EAST);
            } else {
                JButton btn = createActionButton("Build",
                        canAfford ? NexusFrame.GREEN : NexusFrame.TEXT_MUTED,
                        canAfford ? new ActionListener() {
                    public void actionPerformed(ActionEvent e) {
                        buildIndustry(marketId, indId);
                    }
                } : null);
                if (!canAfford) {
                    btn.setToolTipText("Not enough credits (" + formatCredits(cost) + " needed)");
                    btn.setEnabled(false);
                }
                row.add(btn, BorderLayout.EAST);
            }

            listPanel.add(row);
            if (i < industries.length() - 1) {
                listPanel.add(Box.createVerticalStrut(3));
            }
            count++;
        }

        if (count == 0) {
            JLabel noMatch = new JLabel("No matching industries");
            noMatch.setFont(NexusFrame.FONT_BODY);
            noMatch.setForeground(NexusFrame.TEXT_MUTED);
            listPanel.add(noMatch);
        }
    }

    // ========================================================================
    // Game actions (via GameCommand)
    // ========================================================================

    private void buildIndustry(final String marketId, final String industryId) {
        enqueue(new GameDataBridge.GameCommand() {
            public String execute() {
                MarketAPI market = Global.getSector().getEconomy().getMarket(marketId);
                if (market == null) return "{\"success\":false}";

                IndustrySpecAPI spec = Global.getSettings().getIndustrySpec(industryId);
                if (spec == null) return "{\"success\":false}";
                int cost = (int) spec.getCost();

                // Check credits
                CargoAPI cargo = Global.getSector().getPlayerFleet().getCargo();
                if (cargo.getCredits().get() < cost) return "{\"success\":false}";

                // Deduct credits
                cargo.getCredits().subtract(cost);

                // Check if something is currently being built
                boolean somethingBuilding = false;
                for (Industry ind : market.getIndustries()) {
                    if (ind.isBuilding() && !ind.isUpgrading()) {
                        somethingBuilding = true;
                        break;
                    }
                }

                if (somethingBuilding) {
                    // Add to construction queue
                    market.getConstructionQueue().addToEnd(industryId, cost);
                } else {
                    // Start building immediately
                    market.addIndustry(industryId);
                    Industry ind = market.getIndustry(industryId);
                    if (ind != null) {
                        ind.startBuilding();
                        if (ind instanceof BaseIndustry) {
                            ((BaseIndustry) ind).setBuildCostOverride(cost);
                        }
                    }
                }

                return "{\"success\":true}";
            }
        }, "Industry queued for construction");
        showBuildView = false;
        availableIndustries = null;
        rebuildDetail();
        ColonyTracker.ColonySnapshot col = getSelectedColony();
        lastDetailShape = computeDetailShape(col);
    }

    private void cancelBuild(final String marketId, final String industryId) {
        enqueue(new GameDataBridge.GameCommand() {
            public String execute() {
                MarketAPI market = Global.getSector().getEconomy().getMarket(marketId);
                if (market == null) return "{\"success\":false}";
                Industry ind = market.getIndustry(industryId);
                if (ind == null) return "{\"success\":false}";

                CargoAPI cargo = Global.getSector().getPlayerFleet().getCargo();

                if (ind.isUpgrading()) {
                    // Refund upgrade cost via reverse-lookup (getUpgrade() is broken)
                    String upgradeTargetId = findUpgradeTarget(ind.getId());
                    if (upgradeTargetId != null) {
                        IndustrySpecAPI upSpec = Global.getSettings().getIndustrySpec(upgradeTargetId);
                        if (upSpec != null) {
                            cargo.getCredits().add((int) upSpec.getCost());
                        }
                    }
                    ind.cancelUpgrade();
                } else if (ind.isBuilding()) {
                    // Refund build cost
                    int refund = (int) ind.getBuildCost();
                    cargo.getCredits().add(refund);
                    market.removeIndustry(industryId, null, false);
                }

                return "{\"success\":true}";
            }
        }, "Build cancelled");
    }

    private void removeIndustry(final String marketId, final String industryId) {
        enqueue(new GameDataBridge.GameCommand() {
            public String execute() {
                MarketAPI market = Global.getSector().getEconomy().getMarket(marketId);
                if (market == null) return "{\"success\":false}";
                market.removeIndustry(industryId, null, false);
                return "{\"success\":true}";
            }
        }, "Industry removed");
    }

    private void upgradeIndustry(final String marketId, final String industryId, final int upgradeCost) {
        enqueue(new GameDataBridge.GameCommand() {
            public String execute() {
                MarketAPI market = Global.getSector().getEconomy().getMarket(marketId);
                if (market == null) return "{\"success\":false}";
                Industry ind = market.getIndustry(industryId);
                if (ind == null || !ind.canUpgrade()) return "{\"success\":false}";

                // Check and deduct credits (cost passed from snapshot data)
                CargoAPI cargo = Global.getSector().getPlayerFleet().getCargo();
                if (cargo.getCredits().get() < upgradeCost) return "{\"success\":false}";
                cargo.getCredits().subtract(upgradeCost);

                ind.startUpgrading();
                return "{\"success\":true}";
            }
        }, "Industry upgrading");
    }

    private void downgradeIndustry(final String marketId, final String industryId) {
        enqueue(new GameDataBridge.GameCommand() {
            public String execute() {
                MarketAPI market = Global.getSector().getEconomy().getMarket(marketId);
                if (market == null) return "{\"success\":false}";
                Industry ind = market.getIndustry(industryId);
                if (ind == null || !ind.canDowngrade()) return "{\"success\":false}";
                ind.downgrade();
                return "{\"success\":true}";
            }
        }, "Industry downgraded");
    }

    private void setAICore(final String marketId, final String industryId, final String coreId) {
        String coreName = coreId == null ? "None" : prettifyCore(coreId);
        enqueue(new GameDataBridge.GameCommand() {
            public String execute() {
                MarketAPI market = Global.getSector().getEconomy().getMarket(marketId);
                if (market == null) return "{\"success\":false}";
                Industry ind = market.getIndustry(industryId);
                if (ind == null) return "{\"success\":false}";

                SubmarketAPI storage = market.getSubmarket("storage");
                if (storage == null) return "{\"success\":false}";
                CargoAPI storageCargo = storage.getCargo();

                // Check availability of new core
                if (coreId != null) {
                    float available = storageCargo.getCommodityQuantity(coreId);
                    if (available < 1) return "{\"success\":false}";
                }

                // Return old core to storage
                String oldCore = ind.getAICoreId();
                if (oldCore != null) {
                    storageCargo.addCommodity(oldCore, 1);
                }

                // Remove new core from storage
                if (coreId != null) {
                    storageCargo.removeCommodity(coreId, 1);
                }

                // Set the AI core
                ind.setAICoreId(coreId);

                return "{\"success\":true}";
            }
        }, "AI Core set to " + coreName);
    }

    private void installItem(final String marketId, final String industryId, final String itemId) {
        enqueue(new GameDataBridge.GameCommand() {
            public String execute() {
                MarketAPI market = Global.getSector().getEconomy().getMarket(marketId);
                if (market == null) return "{\"success\":false}";
                Industry ind = market.getIndustry(industryId);
                if (ind == null) return "{\"success\":false}";

                SubmarketAPI storage = market.getSubmarket("storage");
                if (storage == null) return "{\"success\":false}";
                CargoAPI storageCargo = storage.getCargo();

                // Return old item to storage if any
                SpecialItemData oldItem = ind.getSpecialItem();
                if (oldItem != null) {
                    storageCargo.addSpecial(oldItem, 1);
                }

                // Find and remove new item from storage
                boolean found = false;
                java.util.List<CargoStackAPI> stacks = storageCargo.getStacksCopy();
                for (int s = 0; s < stacks.size(); s++) {
                    CargoStackAPI stack = stacks.get(s);
                    if (stack.isSpecialStack()) {
                        SpecialItemData data = stack.getSpecialDataIfSpecial();
                        if (data != null && data.getId().equals(itemId)) {
                            storageCargo.removeItems(CargoAPI.CargoItemType.SPECIAL, data, 1);
                            found = true;
                            break;
                        }
                    }
                }
                if (!found) return "{\"success\":false,\"error\":\"Item not in storage\"}";

                // Install new item
                ind.setSpecialItem(new SpecialItemData(itemId, null));
                return "{\"success\":true}";
            }
        }, "Item installed");
    }

    private void removeItem(final String marketId, final String industryId) {
        enqueue(new GameDataBridge.GameCommand() {
            public String execute() {
                MarketAPI market = Global.getSector().getEconomy().getMarket(marketId);
                if (market == null) return "{\"success\":false}";
                Industry ind = market.getIndustry(industryId);
                if (ind == null) return "{\"success\":false}";

                SpecialItemData oldItem = ind.getSpecialItem();
                if (oldItem == null) return "{\"success\":false,\"error\":\"No item installed\"}";

                SubmarketAPI storage = market.getSubmarket("storage");
                if (storage == null) return "{\"success\":false}";

                // Return item to storage
                storage.getCargo().addSpecial(oldItem, 1);
                ind.setSpecialItem(null);
                return "{\"success\":true}";
            }
        }, "Item removed to storage");
    }

    private void improveIndustry(final String marketId, final String industryId) {
        enqueue(new GameDataBridge.GameCommand() {
            public String execute() {
                MarketAPI market = Global.getSector().getEconomy().getMarket(marketId);
                if (market == null) return "{\"success\":false}";
                Industry ind = market.getIndustry(industryId);
                if (ind == null) return "{\"success\":false}";

                if (!ind.canImprove() || ind.isImproved()) {
                    return "{\"success\":false,\"error\":\"Cannot improve\"}";
                }

                int cost = ind.getImproveStoryPoints();
                int available = Global.getSector().getPlayerPerson().getStats().getStoryPoints();
                if (available < cost) {
                    return "{\"success\":false,\"error\":\"Not enough story points\"}";
                }

                // Spend story points and improve
                Global.getSector().getPlayerPerson().getStats().addStoryPoints(-cost);
                ind.setImproved(true);
                return "{\"success\":true}";
            }
        }, "Industry improved");
    }

    // ── Upgrade selection view ──

    private void showUpgradeInfo(String marketId, String industryId,
            String currentName, String targetName, int cost, int buildTime) {
        upgradeViewMarketId = marketId;
        upgradeViewIndustryId = industryId;
        upgradeViewCurrentName = currentName;
        upgradeViewTargetName = targetName;
        upgradeViewCost = cost;
        upgradeViewBuildTime = buildTime;
        showUpgradeView = true;
        lastDetailShape = "";
        rebuildDetail();
    }

    private void rebuildUpgradeView() {
        detailPanel.removeAll();
        int pad = 14;

        JPanel header = flowPanel();
        header.setBorder(new EmptyBorder(pad, pad, 8, pad));

        JButton backBtn = createActionButton("\u2190 Back", NexusFrame.TEXT_SECONDARY, new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                showUpgradeView = false;
                lastDetailShape = "";
                if (detailScroll != null) {
                    detailScroll.getVerticalScrollBar().setValue(0);
                }
                rebuildDetail();
            }
        });
        header.add(backBtn);
        header.add(Box.createHorizontalStrut(12));

        JLabel title = new JLabel("UPGRADE INDUSTRY");
        title.setFont(NexusFrame.FONT_TITLE);
        title.setForeground(NexusFrame.CYAN);
        header.add(title);
        detailPanel.add(header);

        detailPanel.add(createSeparator(pad));

        // Upgrade info panel
        JPanel infoPanel = new JPanel();
        infoPanel.setLayout(new BoxLayout(infoPanel, BoxLayout.Y_AXIS));
        infoPanel.setBackground(NexusFrame.BG_PRIMARY);
        infoPanel.setBorder(new EmptyBorder(16, pad + 8, 16, pad));
        infoPanel.setAlignmentX(Component.LEFT_ALIGNMENT);

        // Current → Target
        JPanel pathRow = flowPanel();
        JLabel currentLabel = new JLabel(upgradeViewCurrentName);
        currentLabel.setFont(NexusFrame.FONT_BODY);
        currentLabel.setForeground(NexusFrame.TEXT_SECONDARY);
        pathRow.add(currentLabel);

        JLabel arrowLabel = new JLabel("  \u2192  ");
        arrowLabel.setFont(NexusFrame.FONT_TITLE);
        arrowLabel.setForeground(NexusFrame.CYAN);
        pathRow.add(arrowLabel);

        JLabel targetLabel = new JLabel(upgradeViewTargetName);
        targetLabel.setFont(NexusFrame.FONT_TITLE);
        targetLabel.setForeground(NexusFrame.TEXT_PRIMARY);
        pathRow.add(targetLabel);
        infoPanel.add(pathRow);
        infoPanel.add(Box.createVerticalStrut(16));

        // Cost
        JPanel costRow = flowPanel();
        costRow.add(statLabel("Cost: ", NexusFrame.TEXT_SECONDARY));
        long credits = data != null ? data.playerCredits : 0;
        boolean canAfford = credits >= upgradeViewCost;
        costRow.add(statLabel(formatCredits(upgradeViewCost),
                canAfford ? NexusFrame.YELLOW : NexusFrame.RED));
        costRow.add(Box.createHorizontalStrut(24));
        costRow.add(statLabel("Build time: ", NexusFrame.TEXT_SECONDARY));
        costRow.add(statLabel(upgradeViewBuildTime + " days", NexusFrame.TEXT_PRIMARY));
        infoPanel.add(costRow);
        infoPanel.add(Box.createVerticalStrut(8));

        // Credits available
        JPanel creditsRow = flowPanel();
        creditsRow.add(statLabel("Credits available: ", NexusFrame.TEXT_MUTED));
        JLabel cLabel = statLabel(formatCredits(credits),
                canAfford ? NexusFrame.GREEN : NexusFrame.RED);
        creditsRow.add(cLabel);
        creditsLiveLabel = cLabel;
        creditsLivePrefix = "";
        if (!canAfford) {
            creditsRow.add(Box.createHorizontalStrut(8));
            creditsRow.add(statLabel("(insufficient)", NexusFrame.RED));
        }
        infoPanel.add(creditsRow);
        infoPanel.add(Box.createVerticalStrut(24));

        // Buttons
        JPanel btnRow = flowPanel();
        final String mId = upgradeViewMarketId;
        final String iId = upgradeViewIndustryId;
        final int upgCostFinal = upgradeViewCost;
        JButton confirmBtn = createActionButton("Confirm Upgrade", NexusFrame.GREEN,
                canAfford ? new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                showUpgradeView = false;
                upgradeIndustry(mId, iId, upgCostFinal);
                lastDetailShape = "";
                if (detailScroll != null) {
                    detailScroll.getVerticalScrollBar().setValue(0);
                }
                rebuildDetail();
            }
        } : null);
        if (!canAfford) {
            confirmBtn.setEnabled(false);
            confirmBtn.setToolTipText("Not enough credits");
        }
        btnRow.add(confirmBtn);
        btnRow.add(Box.createHorizontalStrut(12));

        JButton cancelBtn = createActionButton("Cancel", NexusFrame.TEXT_MUTED, new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                showUpgradeView = false;
                lastDetailShape = "";
                if (detailScroll != null) {
                    detailScroll.getVerticalScrollBar().setValue(0);
                }
                rebuildDetail();
            }
        });
        btnRow.add(cancelBtn);
        infoPanel.add(btnRow);

        detailPanel.add(infoPanel);
        detailPanel.add(Box.createVerticalGlue());
        constrainChildHeights(detailPanel);
        detailPanel.revalidate();
        detailPanel.repaint();
    }

    // ── Specialization (Industrial Evolution) ──

    private void switchSpecialization(final String marketId, final String industryId,
            final String targetSpecId) {
        enqueue(new GameDataBridge.GameCommand() {
            public String execute() {
                try {
                    MarketAPI market = Global.getSector().getEconomy().getMarket(marketId);
                    if (market == null) return "{\"success\":false}";
                    Industry ind = market.getIndustry(industryId);
                    if (ind == null) return "{\"success\":false}";

                    Class<?> switchableClass = Class.forName(
                            "indevo.industries.changeling.industry.SwitchableIndustryAPI");
                    if (!switchableClass.isInstance(ind))
                        return "{\"success\":false,\"error\":\"Not switchable\"}";

                    // Get the industry list (SubIndustryData objects)
                    java.lang.reflect.Method getList = switchableClass.getMethod("getIndustryList");
                    java.util.List<?> options = (java.util.List<?>) getList.invoke(ind);

                    // Find the target SubIndustryData by ID
                    Object targetData = null;
                    for (int i = 0; i < options.size(); i++) {
                        Object opt = options.get(i);
                        String optId = readReflectField(opt, "id");
                        if (targetSpecId.equals(optId)) {
                            targetData = opt;
                            break;
                        }
                    }
                    if (targetData == null)
                        return "{\"success\":false,\"error\":\"Option not found\"}";

                    // Call newInstance() on SubIndustryData to create SubIndustryAPI
                    java.lang.reflect.Method newInstanceM = targetData.getClass().getMethod("newInstance");
                    Object newSubIndustry = newInstanceM.invoke(targetData);

                    // Call setCurrent(SubIndustryAPI, boolean) on the switchable industry
                    Class<?> subApiClass = Class.forName(
                            "indevo.industries.changeling.industry.SubIndustryAPI");
                    java.lang.reflect.Method setCurrent = switchableClass.getMethod(
                            "setCurrent", subApiClass, boolean.class);
                    setCurrent.invoke(ind, newSubIndustry, Boolean.TRUE);

                    // Deduct cost
                    float cost = 0;
                    try {
                        Class<?> cls = targetData.getClass();
                        while (cls != null) {
                            try {
                                java.lang.reflect.Field f = cls.getDeclaredField("cost");
                                f.setAccessible(true);
                                cost = f.getFloat(targetData);
                                break;
                            } catch (NoSuchFieldException nsf) {
                                cls = cls.getSuperclass();
                            }
                        }
                    } catch (Exception ex) { /* ignore */ }
                    if (cost > 0) {
                        Global.getSector().getPlayerFleet().getCargo().getCredits().subtract(cost);
                    }

                    return "{\"success\":true}";
                } catch (Exception e) {
                    return "{\"success\":false,\"error\":\"" + e.getMessage() + "\"}";
                }
            }
        }, "Specialization changed");
    }

    /** Read a String field from object via reflection, searching superclasses. */
    private static String readReflectField(Object obj, String fieldName) {
        Class<?> cls = obj.getClass();
        while (cls != null) {
            try {
                java.lang.reflect.Field f = cls.getDeclaredField(fieldName);
                f.setAccessible(true);
                return (String) f.get(obj);
            } catch (NoSuchFieldException e) {
                cls = cls.getSuperclass();
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    private JButton createSpecializationButton(final String marketId, final String industryId,
            final ColonyTracker.IndustrySnapshot ind) {
        String label = "Specialize";
        final JButton btn = createActionButton(label, NexusFrame.PURPLE, null);
        btn.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                JPopupMenu popup = new JPopupMenu();
                popup.setBackground(NexusFrame.BG_SECONDARY);
                popup.setBorder(new LineBorder(NexusFrame.BORDER, 1));

                for (int i = 0; i < ind.specializations.length; i++) {
                    final ColonyTracker.SpecializationOption opt = ind.specializations[i];
                    String itemText = opt.name;
                    if (opt.cost > 0) {
                        itemText += " (" + formatCredits(opt.cost) + ")";
                    }
                    JMenuItem mi = new JMenuItem(itemText);
                    mi.setFont(NexusFrame.FONT_SMALL);
                    mi.setBackground(NexusFrame.BG_SECONDARY);
                    if (opt.isCurrent) {
                        mi.setForeground(NexusFrame.CYAN);
                        mi.setText("\u2713 " + itemText);
                        mi.setEnabled(false);
                    } else {
                        mi.setForeground(NexusFrame.PURPLE);
                        mi.addActionListener(new ActionListener() {
                            public void actionPerformed(ActionEvent ae) {
                                switchSpecialization(marketId, industryId, opt.id);
                            }
                        });
                    }
                    popup.add(mi);
                }

                popup.show(btn, 0, btn.getHeight());
            }
        });
        return btn;
    }

    private void toggleFreePort(final String marketId) {
        enqueue(new GameDataBridge.GameCommand() {
            public String execute() {
                MarketAPI market = Global.getSector().getEconomy().getMarket(marketId);
                if (market == null) return "{\"success\":false}";
                market.setFreePort(!market.isFreePort());
                return "{\"success\":true}";
            }
        }, "Free Port toggled");
    }

    private void toggleImmigration(final String marketId) {
        enqueue(new GameDataBridge.GameCommand() {
            public String execute() {
                MarketAPI market = Global.getSector().getEconomy().getMarket(marketId);
                if (market == null) return "{\"success\":false}";
                market.setImmigrationClosed(!market.isImmigrationClosed());
                return "{\"success\":true}";
            }
        }, "Immigration toggled");
    }

    private void toggleIncentives(final String marketId) {
        enqueue(new GameDataBridge.GameCommand() {
            public String execute() {
                MarketAPI market = Global.getSector().getEconomy().getMarket(marketId);
                if (market == null) return "{\"success\":false}";
                market.setImmigrationIncentivesOn(!market.isImmigrationIncentivesOn());
                return "{\"success\":true}";
            }
        }, "Incentives toggled");
    }

    private void queueMoveUp(final String marketId, final String itemId) {
        enqueue(new GameDataBridge.GameCommand() {
            public String execute() {
                MarketAPI market = Global.getSector().getEconomy().getMarket(marketId);
                if (market == null) return "{\"success\":false}";
                market.getConstructionQueue().moveUp(itemId);
                return "{\"success\":true}";
            }
        }, "Queue item moved up");
    }

    private void queueMoveDown(final String marketId, final String itemId) {
        enqueue(new GameDataBridge.GameCommand() {
            public String execute() {
                MarketAPI market = Global.getSector().getEconomy().getMarket(marketId);
                if (market == null) return "{\"success\":false}";
                market.getConstructionQueue().moveDown(itemId);
                return "{\"success\":true}";
            }
        }, "Queue item moved down");
    }

    private void queueRemove(final String marketId, final String itemId) {
        enqueue(new GameDataBridge.GameCommand() {
            public String execute() {
                MarketAPI market = Global.getSector().getEconomy().getMarket(marketId);
                if (market == null) return "{\"success\":false}";

                // Refund credits for the queued item
                ConstructionQueue queue = market.getConstructionQueue();
                java.util.List<ConstructionQueue.ConstructionQueueItem> items = queue.getItems();
                for (int i = 0; i < items.size(); i++) {
                    ConstructionQueue.ConstructionQueueItem qItem = items.get(i);
                    if (qItem.id.equals(itemId)) {
                        Global.getSector().getPlayerFleet().getCargo().getCredits().add(qItem.cost);
                        break;
                    }
                }

                queue.removeItem(itemId);
                return "{\"success\":true}";
            }
        }, "Queue item removed, credits refunded");
    }

    // ========================================================================
    // Command system (enqueue / poll / feedback)
    // ========================================================================

    private void enqueue(GameDataBridge.GameCommand cmd, String description) {
        GameDataBridge bridge = GameDataBridge.getInstance();
        if (bridge == null) {
            showFeedback("Game not connected", false);
            return;
        }
        String id = bridge.enqueueCommand(cmd);
        pendingCommands.put(id, description);
    }

    private void pollResults() {
        GameDataBridge bridge = GameDataBridge.getInstance();
        if (bridge == null) return;

        // Poll build-data fetch
        if (buildFetchCommandId != null) {
            String result = bridge.pollCommandResult(buildFetchCommandId);
            if (result != null) {
                buildFetchCommandId = null;
                try {
                    availableIndustries = new JSONArray(result);
                } catch (Exception e) {
                    availableIndustries = new JSONArray();
                }
                showBuildView = true;
                lastDetailShape = "";
                SwingUtilities.invokeLater(new Runnable() {
                    public void run() {
                        rebuildDetail();
                        showFeedback(availableIndustries.length() + " industries available", true);
                    }
                });
            }
        }

        // Poll action commands
        Iterator<Map.Entry<String, String>> it = pendingCommands.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, String> entry = it.next();
            String result = bridge.pollCommandResult(entry.getKey());
            if (result != null) {
                final String desc = entry.getValue();
                final boolean success = !result.contains("\"success\":false");
                it.remove();
                SwingUtilities.invokeLater(new Runnable() {
                    public void run() {
                        showFeedback(desc, success);
                    }
                });
            }
        }
    }

    private void showFeedback(String msg, boolean success) {
        feedbackLabel.setForeground(success ? NexusFrame.GREEN : NexusFrame.RED);
        feedbackLabel.setText(success ? "\u2713 " + msg : "\u2717 " + msg);

        if (feedbackClearTimer != null) feedbackClearTimer.stop();
        feedbackClearTimer = new Timer(4000, new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                feedbackLabel.setText(" ");
                feedbackClearTimer.stop();
            }
        });
        feedbackClearTimer.setRepeats(false);
        feedbackClearTimer.start();
    }

    // ========================================================================
    // Live update timer
    // ========================================================================

    private void onLiveTimer() {
        ColonyTracker.GlobalSnapshot snap = ColonyTracker.getSnapshot();
        if (snap == null) return;
        if (snap.timestamp == lastTimestamp) return;

        lastTimestamp = snap.timestamp;
        data = snap;

        // Live-update credits label (works across all views)
        if (creditsLiveLabel != null) {
            creditsLiveLabel.setText(creditsLivePrefix + formatCredits(snap.playerCredits));
        }

        // Auto-select first colony if none selected
        if (selectedColonyId == null && snap.colonies.length > 0) {
            selectedColonyId = snap.colonies[0].id;
        }

        // Check if selected colony still exists
        if (selectedColonyId != null && getSelectedColony() == null) {
            selectedColonyId = snap.colonies.length > 0 ? snap.colonies[0].id : null;
            lastDetailShape = "";
        }

        // Shape-based sidebar update
        String sidebarShape = computeSidebarShape(snap);
        if (!sidebarShape.equals(lastSidebarShape)) {
            lastSidebarShape = sidebarShape;
            rebuildSidebar();
        } else {
            updateSidebarValues();
        }

        // Shape-based detail update
        if (showBuildView) {
            // Live-refresh build list when credits change (affordability may change)
            if (buildListPanel != null && availableIndustries != null
                    && snap.playerCredits != lastBuildViewCredits) {
                lastBuildViewCredits = snap.playerCredits;
                buildListPanel.removeAll();
                populateBuildList(buildListPanel, availableIndustries, buildSearchQuery);
                buildListPanel.revalidate();
                buildListPanel.repaint();
            }
        } else if (!showUpgradeView) {
            ColonyTracker.ColonySnapshot colony = getSelectedColony();
            String detailShape = computeDetailShape(colony);
            if (!detailShape.equals(lastDetailShape)) {
                lastDetailShape = detailShape;
                if (colony != null) {
                    rebuildDetail();
                } else {
                    showNoColonySelected();
                }
            }
        }
    }

    // ========================================================================
    // UI helpers
    // ========================================================================

    private JPanel flowPanel() {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        p.setOpaque(false);
        p.setAlignmentX(Component.LEFT_ALIGNMENT);
        return p;
    }

    private JLabel statLabel(String text, Color color) {
        JLabel l = new JLabel(text);
        l.setFont(NexusFrame.FONT_SMALL);
        l.setForeground(color);
        return l;
    }

    private JPanel createSeparator(int pad) {
        JPanel sep = new JPanel() {
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                g.setColor(NexusFrame.BORDER);
                g.drawLine(0, getHeight() / 2, getWidth(), getHeight() / 2);
            }
        };
        sep.setOpaque(false);
        sep.setMaximumSize(new Dimension(Integer.MAX_VALUE, 1));
        sep.setPreferredSize(new Dimension(100, 1));
        sep.setAlignmentX(Component.LEFT_ALIGNMENT);
        sep.setBorder(new EmptyBorder(0, pad, 0, pad));
        return sep;
    }

    private JButton createActionButton(String text, Color accent, ActionListener listener) {
        JButton btn = new JButton(text);
        btn.setFont(NexusFrame.FONT_SMALL);
        btn.setForeground(accent);
        btn.setBackground(NexusFrame.BG_SECONDARY);
        btn.setBorder(new CompoundBorder(
            new LineBorder(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 80), 1),
            new EmptyBorder(3, 8, 3, 8)
        ));
        btn.setFocusPainted(false);
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        if (listener != null) btn.addActionListener(listener);

        final Color bg = NexusFrame.BG_SECONDARY;
        final Color hoverBg = NexusFrame.BG_CARD;
        btn.addMouseListener(new MouseAdapter() {
            public void mouseEntered(MouseEvent e) { btn.setBackground(hoverBg); }
            public void mouseExited(MouseEvent e) { btn.setBackground(bg); }
        });

        return btn;
    }

    private JButton createSmallButton(String text, ActionListener listener) {
        JButton btn = new JButton(text);
        btn.setFont(NexusFrame.FONT_SMALL);
        btn.setForeground(NexusFrame.TEXT_SECONDARY);
        btn.setBackground(NexusFrame.BG_SECONDARY);
        btn.setBorder(new EmptyBorder(2, 5, 2, 5));
        btn.setFocusPainted(false);
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btn.addActionListener(listener);
        return btn;
    }

    private JButton createToggleButton(String label, boolean active, Color activeColor,
            ActionListener listener) {
        String text = label + ": " + (active ? "ON" : "OFF");
        Color color = active ? activeColor : NexusFrame.TEXT_MUTED;
        JButton btn = createActionButton(text, color, listener);
        if (active) {
            btn.setBackground(new Color(activeColor.getRed(), activeColor.getGreen(),
                    activeColor.getBlue(), 30));
        }
        return btn;
    }

    private JButton createItemButton(final String marketId, final String industryId,
            final String currentItemId, final String currentItemName,
            final ColonyTracker.ColonySnapshot colony) {
        String label = currentItemId != null
                ? "Item: " + NexusFrame.truncate(currentItemName, 12)
                : "Install Item";
        Color color = currentItemId != null ? NexusFrame.PURPLE : NexusFrame.TEXT_MUTED;

        final JButton btn = createActionButton(label, color, null);
        btn.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                JPopupMenu popup = new JPopupMenu();
                popup.setBackground(NexusFrame.BG_SECONDARY);
                popup.setBorder(new LineBorder(NexusFrame.BORDER, 1));

                // Option to remove current item
                if (currentItemId != null) {
                    JMenuItem removeItem = new JMenuItem("Remove: " + currentItemName);
                    removeItem.setFont(NexusFrame.FONT_SMALL);
                    removeItem.setForeground(NexusFrame.RED);
                    removeItem.setBackground(NexusFrame.BG_SECONDARY);
                    removeItem.addActionListener(new ActionListener() {
                        public void actionPerformed(ActionEvent ae) {
                            removeItem(marketId, industryId);
                        }
                    });
                    popup.add(removeItem);
                    popup.addSeparator();
                }

                // Compatible items from storage
                boolean hasItems = false;
                if (colony.storageSpecialItems != null) {
                    for (int i = 0; i < colony.storageSpecialItems.length; i++) {
                        final ColonyTracker.SpecialItemOption item = colony.storageSpecialItems[i];
                        if (!isItemCompatible(item.params, industryId)) continue;

                        JMenuItem mi = new JMenuItem(item.itemName + " (" + item.count + ")");
                        mi.setFont(NexusFrame.FONT_SMALL);
                        mi.setForeground(NexusFrame.PURPLE);
                        mi.setBackground(NexusFrame.BG_SECONDARY);
                        mi.addActionListener(new ActionListener() {
                            public void actionPerformed(ActionEvent ae) {
                                installItem(marketId, industryId, item.itemId);
                            }
                        });
                        popup.add(mi);
                        hasItems = true;
                    }
                }

                if (!hasItems && currentItemId == null) {
                    JMenuItem noItems = new JMenuItem("No compatible items in storage");
                    noItems.setFont(NexusFrame.FONT_SMALL);
                    noItems.setForeground(NexusFrame.TEXT_MUTED);
                    noItems.setBackground(NexusFrame.BG_SECONDARY);
                    noItems.setEnabled(false);
                    popup.add(noItems);
                }

                popup.show(btn, 0, btn.getHeight());
            }
        });
        return btn;
    }

    private JButton createAICoreButton(final String marketId, final String industryId,
            final String currentCore, final ColonyTracker.ColonySnapshot colony) {
        String label = "AI: " + (currentCore == null ? "None" : prettifyCore(currentCore));
        Color color = currentCore == null ? NexusFrame.TEXT_MUTED : coreColor(currentCore);

        final JButton btn = createActionButton(label, color, null);
        btn.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                JPopupMenu popup = new JPopupMenu();
                popup.setBackground(NexusFrame.BG_SECONDARY);
                popup.setBorder(new LineBorder(NexusFrame.BORDER, 1));

                String[] cores = {null, "gamma_core", "beta_core", "alpha_core"};
                String[] names = {"None", "Gamma Core", "Beta Core", "Alpha Core"};
                Color[] colors = {NexusFrame.TEXT_MUTED, NexusFrame.GREEN,
                        NexusFrame.YELLOW, NexusFrame.CYAN};
                int[] counts = {0, colony.availableGamma, colony.availableBeta,
                        colony.availableAlpha};

                for (int i = 0; i < cores.length; i++) {
                    final String coreId = cores[i];
                    String itemText = names[i];
                    if (i > 0) {
                        itemText += " (" + counts[i] + ")";
                    }

                    JMenuItem item = new JMenuItem(itemText);
                    item.setFont(NexusFrame.FONT_SMALL);
                    item.setForeground(colors[i]);
                    item.setBackground(NexusFrame.BG_SECONDARY);

                    // Disable if no cores available (always allow "None")
                    if (i > 0 && counts[i] <= 0) {
                        item.setEnabled(false);
                        item.setForeground(NexusFrame.TEXT_MUTED);
                    } else {
                        item.addActionListener(new ActionListener() {
                            public void actionPerformed(ActionEvent ae) {
                                setAICore(marketId, industryId, coreId);
                            }
                        });
                    }

                    popup.add(item);
                }

                popup.show(btn, 0, btn.getHeight());
            }
        });
        return btn;
    }

    private void styleScrollPane(JScrollPane scroll) {
        scroll.setBackground(NexusFrame.BG_PRIMARY);
        scroll.getViewport().setBackground(NexusFrame.BG_PRIMARY);
        scroll.setBorder(null);
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        scroll.getVerticalScrollBar().setUnitIncrement(20);
        scroll.getVerticalScrollBar().setBackground(NexusFrame.BG_SECONDARY);
        scroll.getVerticalScrollBar().setUI(new javax.swing.plaf.basic.BasicScrollBarUI() {
            protected void configureScrollBarColors() {
                this.thumbColor = NexusFrame.BORDER;
                this.trackColor = NexusFrame.BG_SECONDARY;
            }
        });
    }

    // ========================================================================
    // Upgrade helper (reverse-lookup since getUpgrade() is broken)
    // ========================================================================

    /**
     * Find the upgrade target for an industry by scanning all specs' downgrade fields.
     * If spec B has getDowngrade() == industryId, then B is the upgrade target.
     * Must be called on the game thread.
     */
    private static String findUpgradeTarget(String industryId) {
        try {
            java.util.List<IndustrySpecAPI> allSpecs = Global.getSettings().getAllIndustrySpecs();
            for (int i = 0; i < allSpecs.size(); i++) {
                IndustrySpecAPI spec = allSpecs.get(i);
                if (spec == null) continue;
                String downId = spec.getDowngrade();
                if (industryId.equals(downId)) {
                    return spec.getId();
                }
            }
        } catch (Throwable t) {
            // ignore
        }
        return null;
    }

    // ========================================================================
    // Formatting helpers
    // ========================================================================

    private static String formatCredits(float amount) {
        if (amount >= 1000000 || amount <= -1000000) {
            return String.format("%.1fM", amount / 1000000f);
        }
        if (amount >= 1000 || amount <= -1000) {
            return String.format("%.1fK", amount / 1000f);
        }
        return String.valueOf((int) amount);
    }

    private static String formatCredits(long amount) {
        if (amount >= 1000000 || amount <= -1000000) {
            return String.format("%.1fM", amount / 1000000f);
        }
        if (amount >= 1000 || amount <= -1000) {
            return String.format("%.1fK", amount / 1000f);
        }
        return String.valueOf(amount);
    }

    private static String prettifyCore(String coreId) {
        if ("alpha_core".equals(coreId)) return "Alpha";
        if ("beta_core".equals(coreId)) return "Beta";
        if ("gamma_core".equals(coreId)) return "Gamma";
        return coreId;
    }

    private static Color coreColor(String coreId) {
        if ("alpha_core".equals(coreId)) return NexusFrame.CYAN;
        if ("beta_core".equals(coreId)) return NexusFrame.YELLOW;
        if ("gamma_core".equals(coreId)) return NexusFrame.GREEN;
        return NexusFrame.TEXT_MUTED;
    }
}
