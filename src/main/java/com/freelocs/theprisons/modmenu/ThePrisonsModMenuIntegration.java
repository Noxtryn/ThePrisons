package com.freelocs.theprisons.modmenu;

import com.freelocs.theprisons.core.ThePrisonsCore;
import com.freelocs.theprisons.ThePrisonsClient;
import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

public final class ThePrisonsModMenuIntegration implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return parent -> ThePrisonsClient.dashboard(parent, ThePrisonsCore.get());
    }
}
