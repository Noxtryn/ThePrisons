package com.freelocs.theprisons.config;

public final class ThePrisonsConfig {
    public final GeneralConfig general = new GeneralConfig();
    public final HudConfig hud = new HudConfig();
    public final QualityOfLifeConfig qol = new QualityOfLifeConfig();
    public final GuiConfig gui = new GuiConfig();

    public void normalize() {
        general.updateCheckIntervalHours = clamp(general.updateCheckIntervalHours, 1, 168);
        hud.petHudColor = clampColor(hud.petHudColor, 0xFFA8E8FF);
        hud.readyColor = clampColor(hud.readyColor, 0xFFF84EA8);
        hud.armorHudScale = Math.max(0.75F, Math.min(1.35F, hud.armorHudScale));
        hud.armorHudIconSize = clamp(hud.armorHudIconSize, 10, 20);
        hud.armorHudGap = clamp(hud.armorHudGap, 0, 8);
        hud.armorHudOffsetX = clamp(hud.armorHudOffsetX, -64, 64);
        hud.armorHudOffsetY = clamp(hud.armorHudOffsetY, -64, 64);
        hud.armorHudShowText = false;
        hud.showDurabilityOverlays = false;
        hud.satchelWarningPercent = clamp(hud.satchelWarningPercent, 50, 100);
        qol.messageNotificationVolume = Math.max(0.0F, Math.min(2.0F, qol.messageNotificationVolume));
        gui.gridSize = clamp(gui.gridSize, 6, 32);
        gui.hudScale = Math.max(0.5f, Math.min(2.5f, gui.hudScale));
        gui.petHudScale = Math.max(0.5f, Math.min(2.5f, gui.petHudScale));
        gui.sessionXpHudScale = Math.max(0.5f, Math.min(2.5f, gui.sessionXpHudScale));
        gui.energyHudScale = Math.max(0.5f, Math.min(2.5f, gui.energyHudScale));
        gui.miningHudScale = Math.max(0.5f, Math.min(2.5f, gui.miningHudScale));
        gui.petHudX = Math.max(0, gui.petHudX);
        gui.petHudY = Math.max(0, gui.petHudY);
        gui.sessionXpHudX = Math.max(0, gui.sessionXpHudX);
        gui.sessionXpHudY = Math.max(0, gui.sessionXpHudY);
        gui.energyHudX = Math.max(0, gui.energyHudX);
        gui.energyHudY = Math.max(0, gui.energyHudY);
        gui.miningHudX = Math.max(0, gui.miningHudX);
        gui.miningHudY = Math.max(0, gui.miningHudY);
        gui.armorHudX = Math.max(-1, gui.armorHudX);
        gui.armorHudY = Math.max(-1, gui.armorHudY);
        hud.alertTitleColor = clampColor(hud.alertTitleColor, 0xFFFF3DBA);
        hud.alertBodyColor = clampColor(hud.alertBodyColor, 0xFF4D8DFF);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static int clampColor(int color, int fallback) {
        return color == 0 ? fallback : color;
    }

    public static final class GeneralConfig {
        public boolean showReadyAnnouncements = true;
        public boolean persistCooldownCache = true;
        public boolean autoUpdaterEnabled = true;
        public int updateCheckIntervalHours = 24;
    }

    public static final class HudConfig {
        public boolean petHudEnabled = true;
        public boolean showCommandCooldowns = true;
        public boolean powerballReadyAlert = true;
        public boolean showSatchelHud = true;
        public boolean showStatsHud = false;
        public boolean pauseStatsHud = false;
        public boolean showReadyStatus = true;
        public boolean showCountdown = true;
        public boolean showArmorHud = true;
        public boolean showArmorWarnings = true;
        public boolean showClueScrollSteps = true;
        public boolean showDurabilityOverlays = false;
        public boolean showItemInsightTooltips = true;
        public boolean showSatchelWarnings = true;
        public boolean lowVitalsWarnings = true;
        public boolean armorHudShowText = false;
        public float armorHudScale = 1.0F;
        public int armorHudIconSize = 16;
        public int armorHudGap = 2;
        public int armorHudOffsetX = 0;
        public int armorHudOffsetY = 0;
        public int petHudColor = 0xFFA8E8FF;
        public int readyColor = 0xFFF84EA8;
        public int alertTitleColor = 0xFFFF3DBA;
        public int alertBodyColor = 0xFF4D8DFF;
        public int satchelWarningPercent = 85;
    }

    public static final class QualityOfLifeConfig {
        public boolean messageNotifications = true;
        public boolean messageNotificationSound = true;
        public float messageNotificationVolume = 0.85F;
        public boolean peacefulMiningSafety = true;
    }

    public static final class GuiConfig {
        public boolean snapToGrid = true;
        public int gridSize = 12;
        public int petHudX = 168;
        public int petHudY = 6;
        public int sessionXpHudX = 16;
        public int sessionXpHudY = 94;
        public int energyHudX = 16;
        public int energyHudY = 148;
        public int miningHudX = 168;
        public int miningHudY = 90;
        public float petHudScale = 1.0f;
        public float sessionXpHudScale = 1.0f;
        public float energyHudScale = 1.0f;
        public float miningHudScale = 1.0f;
        public float hudScale = 1.0f;
        public int armorHudX = -1;
        public int armorHudY = -1;
        public int announcementX = -1;
        public int announcementY = 18;
    }
}
