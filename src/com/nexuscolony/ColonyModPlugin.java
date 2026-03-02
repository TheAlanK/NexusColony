package com.nexuscolony;

import com.fs.starfarer.api.BaseModPlugin;
import com.fs.starfarer.api.Global;
import com.nexusui.api.NexusPage;
import com.nexusui.api.NexusPageFactory;
import com.nexusui.overlay.NexusFrame;

public class ColonyModPlugin extends BaseModPlugin {

    @Override
    public void onGameLoad(boolean newGame) {
        NexusFrame.registerPageFactory(new NexusPageFactory() {
            public String getId() { return "nexus_colony"; }
            public String getTitle() { return "Colonies"; }
            public NexusPage create() { return new ColonyPage(); }
        });
        Global.getSector().addTransientScript(new ColonyTracker());
    }
}
