package com.nexuscolony;

import com.fs.starfarer.api.EveryFrameScript;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CargoAPI;
import com.fs.starfarer.api.campaign.CargoStackAPI;
import com.fs.starfarer.api.campaign.SpecialItemData;
import com.fs.starfarer.api.campaign.SpecialItemSpecAPI;
import com.fs.starfarer.api.campaign.econ.*;
import com.fs.starfarer.api.combat.MutableStat;
import com.fs.starfarer.api.impl.campaign.econ.impl.ConstructionQueue;
import com.fs.starfarer.api.loading.IndustrySpecAPI;

import com.fs.starfarer.api.util.Misc;

import org.apache.log4j.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ColonyTracker - Builds detailed colony snapshots on the game thread.
 *
 * Registered as a transient EveryFrameScript in ColonyModPlugin.
 * Publishes immutable snapshots via static volatile for Swing consumption.
 */
public class ColonyTracker implements EveryFrameScript {

    private static final Logger log = Logger.getLogger(ColonyTracker.class);

    // ========================================================================
    // Immutable snapshot types
    // ========================================================================

    /** Special item available in colony storage with compatibility info. */
    public static final class SpecialItemOption {
        public final String itemId;
        public final String itemName;
        public final int count;
        public final String params; // comma-separated compatible industry IDs from spec

        public SpecialItemOption(String itemId, String itemName, int count, String params) {
            this.itemId = itemId;
            this.itemName = itemName;
            this.count = count;
            this.params = params;
        }
    }

    /** Specialization option for Industrial Evolution switchable industries. */
    public static final class SpecializationOption {
        public final String id;
        public final String name;
        public final int cost;
        public final boolean isCurrent;

        public SpecializationOption(String id, String name, int cost, boolean isCurrent) {
            this.id = id;
            this.name = name;
            this.cost = cost;
            this.isCurrent = isCurrent;
        }
    }

    public static final class IndustrySnapshot {
        public final String id;
        public final String name;
        public final boolean building;
        public final boolean upgrading;
        public final boolean functional;
        public final boolean disrupted;
        public final float buildProgress;
        public final String buildProgressText;
        public final String buildDaysText;
        public final float disruptedDays;
        public final String aiCoreId;
        public final boolean canUpgrade;
        public final boolean canDowngrade;
        public final boolean improved;
        public final int income;
        public final int upkeep;
        public final boolean isIndustry;
        public final int upgradeCost;
        public final String tooltipHtml;
        public final String specialItemId;
        public final String specialItemName;
        public final boolean canImprove;
        public final int improveStoryCost;
        // Upgrade/downgrade target info
        public final String upgradeName;
        public final int upgradeBuildTime;
        public final String downgradeName;
        // Item acceptance
        public final boolean canAcceptItems;
        // Specialization (Industrial Evolution)
        public final SpecializationOption[] specializations;
        public final String currentSpecializationName;

        public IndustrySnapshot(String id, String name,
                boolean building, boolean upgrading, boolean functional, boolean disrupted,
                float buildProgress, String buildProgressText, String buildDaysText,
                float disruptedDays, String aiCoreId,
                boolean canUpgrade, boolean canDowngrade, boolean improved,
                int income, int upkeep, boolean isIndustry, int upgradeCost,
                String tooltipHtml,
                String specialItemId, String specialItemName,
                boolean canImprove, int improveStoryCost,
                String upgradeName, int upgradeBuildTime, String downgradeName,
                boolean canAcceptItems,
                SpecializationOption[] specializations, String currentSpecializationName) {
            this.id = id;
            this.name = name;
            this.building = building;
            this.upgrading = upgrading;
            this.functional = functional;
            this.disrupted = disrupted;
            this.buildProgress = buildProgress;
            this.buildProgressText = buildProgressText;
            this.buildDaysText = buildDaysText;
            this.disruptedDays = disruptedDays;
            this.aiCoreId = aiCoreId;
            this.canUpgrade = canUpgrade;
            this.canDowngrade = canDowngrade;
            this.improved = improved;
            this.income = income;
            this.upkeep = upkeep;
            this.isIndustry = isIndustry;
            this.upgradeCost = upgradeCost;
            this.tooltipHtml = tooltipHtml;
            this.specialItemId = specialItemId;
            this.specialItemName = specialItemName;
            this.canImprove = canImprove;
            this.improveStoryCost = improveStoryCost;
            this.upgradeName = upgradeName;
            this.upgradeBuildTime = upgradeBuildTime;
            this.downgradeName = downgradeName;
            this.canAcceptItems = canAcceptItems;
            this.specializations = specializations;
            this.currentSpecializationName = currentSpecializationName;
        }
    }

    public static final class QueueItemSnapshot {
        public final String id;
        public final String name;
        public final int cost;

        public QueueItemSnapshot(String id, String name, int cost) {
            this.id = id;
            this.name = name;
            this.cost = cost;
        }
    }

    public static final class ConditionSnapshot {
        public final String id;
        public final String name;
        public final boolean planetary;

        public ConditionSnapshot(String id, String name, boolean planetary) {
            this.id = id;
            this.name = name;
            this.planetary = planetary;
        }
    }

    public static final class ColonySnapshot {
        public final String id;
        public final String name;
        public final String systemName;
        public final int size;
        public final float stability;
        public final float hazard;
        public final float income;
        public final float upkeep;
        public final float netIncome;
        public final boolean freePort;
        public final boolean immigrationClosed;
        public final boolean immigrationIncentives;
        public final String adminName;
        public final IndustrySnapshot[] industries;
        public final QueueItemSnapshot[] constructionQueue;
        public final ConditionSnapshot[] conditions;
        public final int availableAlpha;
        public final int availableBeta;
        public final int availableGamma;
        public final int numIndustries;
        public final int maxIndustries;
        public final SpecialItemOption[] storageSpecialItems;

        public ColonySnapshot(String id, String name, String systemName, int size,
                float stability, float hazard,
                float income, float upkeep, float netIncome,
                boolean freePort, boolean immigrationClosed, boolean immigrationIncentives,
                String adminName,
                IndustrySnapshot[] industries,
                QueueItemSnapshot[] constructionQueue,
                ConditionSnapshot[] conditions,
                int availableAlpha, int availableBeta, int availableGamma,
                int numIndustries, int maxIndustries,
                SpecialItemOption[] storageSpecialItems) {
            this.id = id;
            this.name = name;
            this.systemName = systemName;
            this.size = size;
            this.stability = stability;
            this.hazard = hazard;
            this.income = income;
            this.upkeep = upkeep;
            this.netIncome = netIncome;
            this.freePort = freePort;
            this.immigrationClosed = immigrationClosed;
            this.immigrationIncentives = immigrationIncentives;
            this.adminName = adminName;
            this.industries = industries;
            this.constructionQueue = constructionQueue;
            this.conditions = conditions;
            this.availableAlpha = availableAlpha;
            this.availableBeta = availableBeta;
            this.availableGamma = availableGamma;
            this.numIndustries = numIndustries;
            this.maxIndustries = maxIndustries;
            this.storageSpecialItems = storageSpecialItems;
        }
    }

    public static final class GlobalSnapshot {
        public final ColonySnapshot[] colonies;
        public final float totalNetIncome;
        public final long timestamp;
        public final long playerCredits;
        public final int playerStoryPoints;

        public GlobalSnapshot(ColonySnapshot[] colonies, float totalNetIncome,
                long timestamp, long playerCredits, int playerStoryPoints) {
            this.colonies = colonies;
            this.totalNetIncome = totalNetIncome;
            this.timestamp = timestamp;
            this.playerCredits = playerCredits;
            this.playerStoryPoints = playerStoryPoints;
        }
    }

    // ========================================================================
    // Static volatile snapshot
    // ========================================================================

    private static volatile GlobalSnapshot snapshot;

    public static GlobalSnapshot getSnapshot() {
        return snapshot;
    }

    // ========================================================================
    // Script lifecycle
    // ========================================================================

    private float timer = 0f;
    private static final float UPDATE_INTERVAL = 0.5f;
    private boolean loggedUpgradeDiag = false;

    // Reverse-lookup upgrade map: industryId -> upgradeTargetId
    // Built by scanning all specs' getDowngrade() since getUpgrade() is broken
    // in the obfuscated API (always returns null).
    private Map<String, String> upgradeMap = null;

    public boolean isDone() { return false; }
    public boolean runWhilePaused() { return true; }

    public void advance(float amount) {
        timer += amount;
        if (timer < UPDATE_INTERVAL) return;
        timer = 0f;

        try {
            buildSnapshot();
        } catch (Exception e) {
            // Silently handle concurrent modification issues
        }
    }

    // ========================================================================
    // Upgrade map builder (reverse-lookup from downgrade fields)
    // ========================================================================

    /**
     * Builds upgrade map by reversing downgrade relationships.
     * IndustrySpecAPI.getUpgrade() is broken in the obfuscated API (always returns null).
     * Instead, we scan all specs: if spec B has getDowngrade() == "A", then A upgrades to B.
     * Built once and cached for the lifetime of this script.
     */
    private void buildUpgradeMap() {
        upgradeMap = new java.util.HashMap<String, String>();
        try {
            List<IndustrySpecAPI> allSpecs = Global.getSettings().getAllIndustrySpecs();
            for (int i = 0; i < allSpecs.size(); i++) {
                IndustrySpecAPI spec = allSpecs.get(i);
                if (spec == null) continue;
                String downgradeId = spec.getDowngrade();
                if (downgradeId != null && !downgradeId.isEmpty()) {
                    // spec.getId() is the upgrade target for downgradeId
                    upgradeMap.put(downgradeId, spec.getId());
                }
            }
            log.info("NexusColony: Built upgrade map with " + upgradeMap.size() + " entries: " + upgradeMap);
        } catch (Throwable t) {
            log.error("NexusColony: Failed to build upgrade map", t);
        }
    }

    // ========================================================================
    // Snapshot builder
    // ========================================================================

    private void buildSnapshot() {
        // Build upgrade map once (reverse-lookup from downgrade fields)
        if (upgradeMap == null) {
            buildUpgradeMap();
        }

        List<MarketAPI> allMarkets = Global.getSector().getEconomy().getMarketsCopy();
        ArrayList<ColonySnapshot> colonies = new ArrayList<ColonySnapshot>();
        float totalNet = 0;

        // Player credits
        long playerCredits = 0;
        try {
            playerCredits = (long) Global.getSector().getPlayerFleet().getCargo().getCredits().get();
        } catch (Exception e) {
            // ignore
        }

        // Player story points
        int storyPoints = 0;
        try {
            storyPoints = Global.getSector().getPlayerPerson().getStats().getStoryPoints();
        } catch (Exception e) {
            // ignore
        }

        // Precompute set of industry IDs that can accept special items
        java.util.HashSet<String> itemAcceptingIndustries = new java.util.HashSet<String>();
        String[] knownSpecialItems = {
            "pristine_nanoforge", "corrupted_nanoforge", "synchrotron",
            "orbital_fusion_lamp", "mantle_bore", "catalytic_core", "soil_nanites",
            "biofactory_embryo", "fullerene_spool", "plasma_dynamo",
            "cryoarithmetic_engine", "drone_replicator", "dealmaker_holosuite"
        };
        for (int ki = 0; ki < knownSpecialItems.length; ki++) {
            try {
                SpecialItemSpecAPI spec = Global.getSettings().getSpecialItemSpec(knownSpecialItems[ki]);
                if (spec != null && spec.getParams() != null && !spec.getParams().isEmpty()) {
                    String[] pIds = spec.getParams().split(",");
                    for (int pi = 0; pi < pIds.length; pi++) {
                        itemAcceptingIndustries.add(pIds[pi].trim());
                    }
                }
            } catch (Exception e) { /* ignore */ }
        }
        // Also scan all colony storages for modded special items
        for (int m2 = 0; m2 < allMarkets.size(); m2++) {
            MarketAPI market2 = allMarkets.get(m2);
            if (!market2.isPlayerOwned()) continue;
            try {
                SubmarketAPI storage2 = market2.getSubmarket("storage");
                if (storage2 == null) continue;
                List<CargoStackAPI> stacks2 = storage2.getCargo().getStacksCopy();
                for (int s2 = 0; s2 < stacks2.size(); s2++) {
                    CargoStackAPI stack2 = stacks2.get(s2);
                    if (stack2.isSpecialStack()) {
                        SpecialItemData data2 = stack2.getSpecialDataIfSpecial();
                        if (data2 != null) {
                            SpecialItemSpecAPI spec2 = Global.getSettings().getSpecialItemSpec(data2.getId());
                            if (spec2 != null && spec2.getParams() != null && !spec2.getParams().isEmpty()) {
                                String[] pIds = spec2.getParams().split(",");
                                for (int pi = 0; pi < pIds.length; pi++) {
                                    itemAcceptingIndustries.add(pIds[pi].trim());
                                }
                            }
                        }
                    }
                }
            } catch (Exception e) { /* ignore */ }
        }

        for (int m = 0; m < allMarkets.size(); m++) {
            MarketAPI market = allMarkets.get(m);
            if (!market.isPlayerOwned()) continue;
            if (market.isHidden()) continue;

            // Basic info
            String id = market.getId();
            String name = market.getName();
            String systemName = "Unknown";
            try {
                if (market.getStarSystem() != null) {
                    systemName = market.getStarSystem().getName();
                }
            } catch (Exception e) {
                // ignore
            }
            int size = market.getSize();
            float stability = Math.round(market.getStabilityValue() * 10f) / 10f;
            float hazard = market.getHazardValue();
            float marketIncome = market.getIndustryIncome();
            float marketUpkeep = market.getIndustryUpkeep();
            float netIncome = market.getNetIncome();
            boolean freePort = market.isFreePort();
            boolean immigrationClosed = market.isImmigrationClosed();
            boolean immigrationIncentives = market.isImmigrationIncentivesOn();

            // Admin
            String adminName = "None";
            try {
                if (market.getAdmin() != null) {
                    adminName = market.getAdmin().getNameString();
                }
            } catch (Exception e) {
                adminName = "Unknown";
            }

            // AI core availability from storage
            int alphaCount = 0, betaCount = 0, gammaCount = 0;
            try {
                SubmarketAPI storage = market.getSubmarket("storage");
                if (storage != null) {
                    CargoAPI storageCargo = storage.getCargo();
                    alphaCount = (int) storageCargo.getCommodityQuantity("alpha_core");
                    betaCount = (int) storageCargo.getCommodityQuantity("beta_core");
                    gammaCount = (int) storageCargo.getCommodityQuantity("gamma_core");
                }
            } catch (Exception e) {
                // ignore
            }

            // Scan storage for special items (for item installation feature)
            ArrayList<SpecialItemOption> storageItems = new ArrayList<SpecialItemOption>();
            try {
                SubmarketAPI storage = market.getSubmarket("storage");
                if (storage != null) {
                    CargoAPI storageCargo = storage.getCargo();
                    // Accumulate quantities per item ID
                    LinkedHashMap<String, int[]> itemCounts = new LinkedHashMap<String, int[]>();
                    List<CargoStackAPI> stacks = storageCargo.getStacksCopy();
                    for (int s = 0; s < stacks.size(); s++) {
                        CargoStackAPI stack = stacks.get(s);
                        if (stack.isSpecialStack()) {
                            SpecialItemData data = stack.getSpecialDataIfSpecial();
                            if (data != null) {
                                String sid = data.getId();
                                int qty = (int) stack.getSize();
                                int[] existing = itemCounts.get(sid);
                                if (existing == null) {
                                    itemCounts.put(sid, new int[]{qty});
                                } else {
                                    existing[0] += qty;
                                }
                            }
                        }
                    }
                    // Build SpecialItemOption array
                    for (Map.Entry<String, int[]> entry : itemCounts.entrySet()) {
                        String sid = entry.getKey();
                        int count = entry.getValue()[0];
                        String sName = sid;
                        String sParams = "";
                        try {
                            SpecialItemSpecAPI spec = Global.getSettings().getSpecialItemSpec(sid);
                            if (spec != null) {
                                sName = spec.getName() != null ? spec.getName() : sid;
                                sParams = spec.getParams() != null ? spec.getParams() : "";
                            }
                        } catch (Exception ex) { /* ignore */ }
                        // Only include items that have industry params (i.e. installable on buildings)
                        if (sParams.length() > 0) {
                            storageItems.add(new SpecialItemOption(sid, sName, count, sParams));
                        }
                    }
                }
            } catch (Exception e) {
                // ignore
            }

            // Industries
            List<Industry> indList = market.getIndustries();
            ArrayList<IndustrySnapshot> industries = new ArrayList<IndustrySnapshot>();
            for (int i = 0; i < indList.size(); i++) {
                Industry ind = indList.get(i);
                if (ind.isHidden()) continue;

                boolean building = ind.isBuilding();

                // Upgrade path analysis: populate info if upgrade path exists,
                // canUpgrade is separate (controls button enable/disable).
                // Uses reverse-lookup map (from downgrade fields) because
                // IndustrySpecAPI.getUpgrade() is broken (always returns null).
                boolean realCanUpgrade = false;
                String upgName = null;
                int upgBuildTime = 0;
                int upgCost = 0;
                try {
                    // Primary: use reverse-lookup upgrade map
                    String upgradeId = upgradeMap != null ? upgradeMap.get(ind.getId()) : null;
                    // Fallback: try spec.getUpgrade() in case it starts working
                    if (upgradeId == null) {
                        IndustrySpecAPI spec = ind.getSpec();
                        if (spec != null) {
                            upgradeId = spec.getUpgrade();
                        }
                    }
                    if (upgradeId != null) {
                        IndustrySpecAPI upSpec = Global.getSettings().getIndustrySpec(upgradeId);
                        if (upSpec != null) {
                            upgName = upSpec.getName();
                            upgBuildTime = (int) upSpec.getBuildTime();
                            upgCost = (int) upSpec.getCost();
                            realCanUpgrade = ind.canUpgrade();
                        }
                    }
                } catch (Throwable e) {
                    log.error("NexusColony: upgrade detection failed for " + ind.getId() + ": " + e, e);
                }

                // Downgrade path analysis: populate info if downgrade path exists,
                // canDowngrade is separate (controls button enable/disable)
                boolean realCanDowngrade = false;
                String downName = null;
                try {
                    IndustrySpecAPI spec = ind.getSpec();
                    String downgradeId = null;
                    if (spec != null) {
                        downgradeId = spec.getDowngrade();
                    }
                    if (downgradeId == null) {
                        IndustrySpecAPI globalSpec = Global.getSettings().getIndustrySpec(ind.getId());
                        if (globalSpec != null) {
                            downgradeId = globalSpec.getDowngrade();
                        }
                    }
                    if (downgradeId != null) {
                        IndustrySpecAPI downSpec = Global.getSettings().getIndustrySpec(downgradeId);
                        if (downSpec != null) {
                            downName = downSpec.getName();
                            realCanDowngrade = ind.canDowngrade();
                        }
                    }
                } catch (Throwable e) {
                    log.error("NexusColony: downgrade detection failed for " + ind.getId() + ": " + e, e);
                }

                // One-time diagnostic log for upgrade/downgrade paths
                if (!loggedUpgradeDiag) {
                    try {
                        String reverseUpg = upgradeMap != null ? upgradeMap.get(ind.getId()) : "NO_MAP";
                        log.info("NexusColony DIAG [" + ind.getId()
                                + "] reverseUpgrade=" + reverseUpg
                                + " | upgName=" + upgName + " downName=" + downName
                                + " | canUpgrade=" + realCanUpgrade + " canDowngrade=" + realCanDowngrade
                                + " | indClass=" + ind.getClass().getName());
                    } catch (Throwable t) {
                        log.error("NexusColony DIAG error for " + ind.getId(), t);
                    }
                }

                // Special item installed on this industry
                String specItemId = null;
                String specItemName = null;
                try {
                    SpecialItemData specItem = ind.getSpecialItem();
                    if (specItem != null) {
                        specItemId = specItem.getId();
                        try {
                            SpecialItemSpecAPI specItemSpec = Global.getSettings().getSpecialItemSpec(specItemId);
                            specItemName = specItemSpec != null ? specItemSpec.getName() : specItemId;
                        } catch (Exception ex) {
                            specItemName = specItemId;
                        }
                    }
                } catch (Exception e) { /* ignore */ }

                // Improvement support
                boolean canImp = false;
                int impCost = 0;
                try {
                    canImp = ind.canImprove();
                    if (canImp) {
                        impCost = ind.getImproveStoryPoints();
                    }
                } catch (Exception e) { /* ignore */ }

                // Can accept special items (precomputed set + current item)
                boolean canAccept = specItemId != null
                        || itemAcceptingIndustries.contains(ind.getId());

                // Specialization detection (Industrial Evolution - via reflection)
                SpecializationOption[] specOpts = null;
                String currentSpecName = null;
                try {
                    Class<?> switchableClass = Class.forName(
                            "indevo.industries.changeling.industry.SwitchableIndustryAPI");
                    if (switchableClass.isInstance(ind)) {
                        // Get current specialization
                        java.lang.reflect.Method getCurrent = switchableClass.getMethod("getCurrent");
                        Object current = getCurrent.invoke(ind);
                        if (current != null) {
                            java.lang.reflect.Method getNameM = current.getClass().getMethod("getName");
                            currentSpecName = (String) getNameM.invoke(current);
                        }
                        // Get available options
                        java.lang.reflect.Method getList = switchableClass.getMethod("getIndustryList");
                        java.util.List<?> options = (java.util.List<?>) getList.invoke(ind);
                        ArrayList<SpecializationOption> optList = new ArrayList<SpecializationOption>();
                        for (int o = 0; o < options.size(); o++) {
                            Object opt = options.get(o);
                            String optId = readStringField(opt, "id");
                            String optName = readStringField(opt, "name");
                            int optCost = 0;
                            try {
                                optCost = (int) readFloatField(opt, "cost");
                            } catch (Exception ex) { /* ignore */ }
                            boolean isCur = optName != null && optName.equals(currentSpecName);
                            if (optId != null && optName != null) {
                                optList.add(new SpecializationOption(optId, optName, optCost, isCur));
                            }
                        }
                        if (!optList.isEmpty()) {
                            specOpts = optList.toArray(new SpecializationOption[0]);
                        }
                    }
                } catch (ClassNotFoundException cnf) {
                    // Industrial Evolution not installed - ignore
                } catch (Exception e) {
                    // Reflection error - ignore
                }

                // Build tooltip HTML
                String tooltip = buildTooltipHtml(ind);

                industries.add(new IndustrySnapshot(
                    ind.getId(),
                    ind.getCurrentName(),
                    building,
                    ind.isUpgrading(),
                    ind.isFunctional(),
                    ind.isDisrupted(),
                    building ? ind.getBuildOrUpgradeProgress() : 0f,
                    building ? safeStr(ind.getBuildOrUpgradeProgressText()) : "",
                    building ? safeStr(ind.getBuildOrUpgradeDaysText()) : "",
                    ind.isDisrupted() ? ind.getDisruptedDays() : 0f,
                    ind.getAICoreId(),
                    realCanUpgrade,
                    realCanDowngrade,
                    ind.isImproved(),
                    (int) ind.getIncome().getModifiedValue(),
                    (int) ind.getUpkeep().getModifiedValue(),
                    ind.isIndustry(),
                    upgCost,
                    tooltip,
                    specItemId,
                    specItemName,
                    canImp,
                    impCost,
                    upgName,
                    upgBuildTime,
                    downName,
                    canAccept,
                    specOpts,
                    currentSpecName
                ));
            }
            if (!loggedUpgradeDiag) {
                loggedUpgradeDiag = true;
                log.info("NexusColony: Upgrade diagnostic complete for first colony.");
            }

            // Construction queue
            ConstructionQueue queue = market.getConstructionQueue();
            List<ConstructionQueue.ConstructionQueueItem> qItems = queue.getItems();
            ArrayList<QueueItemSnapshot> queueSnaps = new ArrayList<QueueItemSnapshot>();
            for (int q = 0; q < qItems.size(); q++) {
                ConstructionQueue.ConstructionQueueItem item = qItems.get(q);
                String qName = item.id;
                try {
                    IndustrySpecAPI spec = Global.getSettings().getIndustrySpec(item.id);
                    if (spec != null) qName = spec.getName();
                } catch (Exception e) {
                    // keep raw id
                }
                queueSnaps.add(new QueueItemSnapshot(item.id, qName, item.cost));
            }

            // Conditions
            List<MarketConditionAPI> condList = market.getConditions();
            ArrayList<ConditionSnapshot> conditions = new ArrayList<ConditionSnapshot>();
            for (int c = 0; c < condList.size(); c++) {
                MarketConditionAPI cond = condList.get(c);
                conditions.add(new ConditionSnapshot(
                    cond.getId(),
                    safeStr(cond.getName()),
                    cond.isPlanetary()
                ));
            }

            // Industry count / max
            int numInd = Misc.getNumIndustries(market);
            int maxInd = Misc.getMaxIndustries(market);

            colonies.add(new ColonySnapshot(
                id, name, systemName, size,
                stability, hazard,
                marketIncome, marketUpkeep, netIncome,
                freePort, immigrationClosed, immigrationIncentives,
                adminName,
                industries.toArray(new IndustrySnapshot[0]),
                queueSnaps.toArray(new QueueItemSnapshot[0]),
                conditions.toArray(new ConditionSnapshot[0]),
                alphaCount, betaCount, gammaCount,
                numInd, maxInd,
                storageItems.toArray(new SpecialItemOption[0])
            ));

            totalNet += netIncome;
        }

        sortColonies(colonies);

        snapshot = new GlobalSnapshot(
            colonies.toArray(new ColonySnapshot[0]),
            totalNet,
            System.currentTimeMillis(),
            playerCredits,
            storyPoints
        );
    }

    // ========================================================================
    // Tooltip builder
    // ========================================================================

    private String buildTooltipHtml(Industry ind) {
        StringBuilder sb = new StringBuilder();
        sb.append("<html><div style='width:340px;padding:2px'>");

        // Title: Name - Type
        String typeName = ind.isIndustry() ? "Industry" :
                          ind.isStructure() ? "Structure" : "Other";
        sb.append("<b>").append(esc(ind.getCurrentName()));
        sb.append("</b> - ").append(typeName).append("<br/>");

        // Description
        try {
            IndustrySpecAPI spec = ind.getSpec();
            if (spec != null) {
                String desc = spec.getDesc();
                if (desc != null && !desc.isEmpty()) {
                    if (desc.length() > 250) desc = desc.substring(0, 247) + "...";
                    sb.append("<br/>").append(esc(desc)).append("<br/>");
                }
            }
        } catch (Exception e) { /* ignore */ }

        // Upkeep breakdown
        try {
            MutableStat upkeep = ind.getUpkeep();
            int upkeepVal = (int) upkeep.getModifiedValue();
            if (upkeepVal > 0) {
                sb.append("<br/><b>Monthly upkeep: ").append(fmtC(upkeepVal)).append("</b><br/>");
                appendStatBreakdown(sb, upkeep);
            }
        } catch (Exception e) { /* ignore */ }

        // Income breakdown
        try {
            MutableStat income = ind.getIncome();
            int incomeVal = (int) income.getModifiedValue();
            if (incomeVal > 0) {
                sb.append("<br/><b>Monthly income: ").append(fmtC(incomeVal)).append("</b><br/>");
                appendStatBreakdown(sb, income);
            }
        } catch (Exception e) { /* ignore */ }

        // Supply
        try {
            List<MutableCommodityQuantity> supply = ind.getAllSupply();
            boolean hasSupply = false;
            StringBuilder supplySb = new StringBuilder();
            for (int i = 0; i < supply.size(); i++) {
                MutableCommodityQuantity q = supply.get(i);
                int qty = q.getQuantity().getModifiedInt();
                if (qty > 0) {
                    if (hasSupply) supplySb.append(", ");
                    supplySb.append(prettifyCommodityId(q.getCommodityId()));
                    supplySb.append(" (").append(qty).append(")");
                    hasSupply = true;
                }
            }
            if (hasSupply) {
                sb.append("<br/><b>Supply:</b> ").append(supplySb).append("<br/>");
            }
        } catch (Exception e) { /* ignore */ }

        // Demand
        try {
            List<MutableCommodityQuantity> demand = ind.getAllDemand();
            boolean hasDemand = false;
            StringBuilder demandSb = new StringBuilder();
            for (int i = 0; i < demand.size(); i++) {
                MutableCommodityQuantity q = demand.get(i);
                int qty = q.getQuantity().getModifiedInt();
                if (qty > 0) {
                    if (hasDemand) demandSb.append(", ");
                    demandSb.append(prettifyCommodityId(q.getCommodityId()));
                    demandSb.append(" (").append(qty).append(")");
                    hasDemand = true;
                }
            }
            if (hasDemand) {
                sb.append("<b>Demand:</b> ").append(demandSb).append("<br/>");
            }
        } catch (Exception e) { /* ignore */ }

        // Deficits
        try {
            List<com.fs.starfarer.api.util.Pair<String, Integer>> deficits = ind.getAllDeficit();
            if (deficits != null && !deficits.isEmpty()) {
                StringBuilder defSb = new StringBuilder();
                boolean hasDef = false;
                for (int i = 0; i < deficits.size(); i++) {
                    com.fs.starfarer.api.util.Pair<String, Integer> def = deficits.get(i);
                    if (def.two > 0) {
                        if (hasDef) defSb.append(", ");
                        defSb.append(prettifyCommodityId(def.one));
                        defSb.append(" (-").append(def.two).append(")");
                        hasDef = true;
                    }
                }
                if (hasDef) {
                    sb.append("<font color='#FF5050'><b>Deficit:</b> ").append(defSb);
                    sb.append("</font><br/>");
                }
            }
        } catch (Exception e) { /* ignore */ }

        // AI Core
        String ai = ind.getAICoreId();
        if (ai != null) {
            sb.append("<br/><b>AI Core:</b> ").append(prettifyCoreId(ai)).append("<br/>");
        }

        // Special item
        try {
            SpecialItemData item = ind.getSpecialItem();
            if (item != null) {
                String itemName = item.getId();
                try {
                    SpecialItemSpecAPI itemSpec = Global.getSettings().getSpecialItemSpec(item.getId());
                    if (itemSpec != null) itemName = itemSpec.getName();
                } catch (Exception e) { /* ignore */ }
                sb.append("<b>Special Item:</b> ").append(esc(itemName)).append("<br/>");
            } else if (ai == null) {
                sb.append("<br/>No items installed<br/>");
            }
        } catch (Exception e) { /* ignore */ }

        // Improved status
        try {
            if (ind.isImproved()) {
                sb.append("<font color='#FFD700'><b>\u2605 Improved</b></font><br/>");
            }
        } catch (Exception e) { /* ignore */ }

        sb.append("</div></html>");
        return sb.toString();
    }

    private void appendStatBreakdown(StringBuilder sb, MutableStat stat) {
        // Base value
        float base = stat.getBaseValue();
        if (base != 0) {
            sb.append("&nbsp;&nbsp;").append(fmtC((int) base)).append("&nbsp; Base value<br/>");
        }

        // Flat mods
        Map<String, MutableStat.StatMod> flatMods = stat.getFlatMods();
        if (flatMods != null) {
            for (MutableStat.StatMod mod : flatMods.values()) {
                if (mod.value == 0) continue;
                String label = mod.desc != null ? mod.desc : mod.source;
                if ("base val".equals(mod.source) || "base".equals(mod.source)) continue;
                String sign = mod.value > 0 ? "+" : "";
                sb.append("&nbsp;&nbsp;").append(sign).append(fmtC((int) mod.value));
                sb.append("&nbsp; ").append(esc(label)).append("<br/>");
            }
        }

        // Percent mods
        Map<String, MutableStat.StatMod> pctMods = stat.getPercentMods();
        if (pctMods != null) {
            for (MutableStat.StatMod mod : pctMods.values()) {
                if (mod.value == 0) continue;
                String label = mod.desc != null ? mod.desc : mod.source;
                String sign = mod.value > 0 ? "+" : "";
                sb.append("&nbsp;&nbsp;").append(sign).append((int) mod.value).append("%");
                sb.append("&nbsp; ").append(esc(label)).append("<br/>");
            }
        }

        // Mult mods
        Map<String, MutableStat.StatMod> multMods = stat.getMultMods();
        if (multMods != null) {
            for (MutableStat.StatMod mod : multMods.values()) {
                if (mod.value == 1f) continue;
                String label = mod.desc != null ? mod.desc : mod.source;
                sb.append("&nbsp;&nbsp;x").append(String.format("%.2f", mod.value));
                sb.append("&nbsp; ").append(esc(label)).append("<br/>");
            }
        }
    }

    // ========================================================================
    // Utilities
    // ========================================================================

    /** Read a public String field from an object, searching superclasses. */
    private static String readStringField(Object obj, String fieldName) {
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

    /** Read a public float field from an object, searching superclasses. */
    private static float readFloatField(Object obj, String fieldName) {
        Class<?> cls = obj.getClass();
        while (cls != null) {
            try {
                java.lang.reflect.Field f = cls.getDeclaredField(fieldName);
                f.setAccessible(true);
                return f.getFloat(obj);
            } catch (NoSuchFieldException e) {
                cls = cls.getSuperclass();
            } catch (Exception e) {
                return 0f;
            }
        }
        return 0f;
    }

    private static String safeStr(String s) {
        return s != null ? s : "";
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String fmtC(int amount) {
        if (amount >= 1000000 || amount <= -1000000) {
            return String.format("%.1fM", amount / 1000000f);
        }
        if (amount >= 1000 || amount <= -1000) {
            return String.format("%,d", amount);
        }
        return String.valueOf(amount);
    }

    private static String prettifyCommodityId(String id) {
        if (id == null || id.isEmpty()) return "";
        String s = id.replace('_', ' ');
        return s.substring(0, 1).toUpperCase() + s.substring(1);
    }

    private static String prettifyCoreId(String coreId) {
        if ("alpha_core".equals(coreId)) return "Alpha Core";
        if ("beta_core".equals(coreId)) return "Beta Core";
        if ("gamma_core".equals(coreId)) return "Gamma Core";
        return coreId;
    }

    private static void sortColonies(ArrayList<ColonySnapshot> list) {
        for (int i = 1; i < list.size(); i++) {
            ColonySnapshot key = list.get(i);
            int j = i - 1;
            while (j >= 0 && compareColonies(list.get(j), key) > 0) {
                list.set(j + 1, list.get(j));
                j--;
            }
            list.set(j + 1, key);
        }
    }

    private static int compareColonies(ColonySnapshot a, ColonySnapshot b) {
        if (a.size != b.size) return b.size - a.size;
        return a.name.compareToIgnoreCase(b.name);
    }
}
