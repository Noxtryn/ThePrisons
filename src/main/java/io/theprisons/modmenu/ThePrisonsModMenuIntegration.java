package io.theprisons.modmenu;

import io.theprisons.core.ThePrisonsCore;
import io.theprisons.ThePrisonsClient;
import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

public final class ThePrisonsModMenuIntegration implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return parent -> ThePrisonsClient.dashboard(parent, ThePrisonsCore.get());
    }
}
