package com.halil.realmvoice;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.IntConsumer;

/** Small status/control screen. X opens it. V toggles microphone. */
public final class RealmVoiceScreen extends Screen {
    private static final int WIDTH = 440;
    private static final int LIST_TOP = 140;
    private static final int LIST_BOTTOM_MARGIN = 44;
    private static final int ROW_HEIGHT = 27;

    private final Minecraft client;
    private final Screen parent;
    private final RealmVoiceClient.VoiceNetwork voice;

    private Button muteButton;
    private Button deafenButton;
    private Button inputButton;
    private Button outputButton;
    private Button doneButton;
    private final List<VolumeSlider> sliders = new ArrayList<>();
    private List<RealmVoiceClient.VoiceNetwork.PlayerInfo> players = List.of();
    private double scroll;

    public RealmVoiceScreen(Minecraft client, RealmVoiceClient mod) {
        super(Component.literal("RealmVoice"));
        this.client = client;
        this.parent = client.gui.screen();
        this.voice = mod.voice();
    }

    @Override
    protected void init() {
        int left = (this.width - WIDTH) / 2;

        muteButton = addRenderableWidget(Button.builder(toggleText("Mute", voice.isMuted()), b -> {
            voice.toggleMuted();
            b.setMessage(toggleText("Mute", voice.isMuted()));
        }).bounds(left, 45, 135, 20).build());

        deafenButton = addRenderableWidget(Button.builder(toggleText("Deafen", voice.isDeafened()), b -> {
            voice.setDeafened(!voice.isDeafened());
            b.setMessage(toggleText("Deafen", voice.isDeafened()));
        }).bounds(left + 145, 45, 135, 20).build());

        Button refreshButton = addRenderableWidget(Button.builder(Component.literal("Refresh"), b -> refreshPlayers())
                .bounds(left + 290, 45, 140, 20).build());

        inputButton = addRenderableWidget(Button.builder(
                Component.literal("Input: " + RealmVoiceClient.trimDeviceName(voice.getInputMixer())),
                b -> {
                    voice.cycleInputMixer();
                    b.setMessage(Component.literal("Input: " + RealmVoiceClient.trimDeviceName(voice.getInputMixer())));
                }
        ).bounds(left, 77, 208, 20).build());

        outputButton = addRenderableWidget(Button.builder(
                Component.literal("Output: " + RealmVoiceClient.trimDeviceName(voice.getOutputMixer())),
                b -> {
                    voice.cycleOutputMixer();
                    b.setMessage(Component.literal("Output: " + RealmVoiceClient.trimDeviceName(voice.getOutputMixer())));
                }
        ).bounds(left + 220, 77, 210, 20).build());

        addRenderableWidget(new PercentSlider("Mic volume", left, 101, 208,
                voice.getMicVolume(), 200, voice::setMicVolume));

        addRenderableWidget(new PercentSlider("Incoming volume", left + 220, 101, 210,
                voice.getOutputVolume(), 200, voice::setOutputVolume));

        doneButton = addRenderableWidget(Button.builder(Component.literal("Done"), b -> close())
                .bounds(left + 120, this.height - 30, 200, 20).build());

        refreshPlayers();
    }

    private void refreshPlayers() {
        for (VolumeSlider slider : sliders) removeWidget(slider);
        sliders.clear();
        players = voice.getPlayersSnapshot();

        int left = (this.width - WIDTH) / 2 + 12;
        int width = WIDTH - 24;
        int visibleHeight = Math.max(20, this.height - LIST_TOP - LIST_BOTTOM_MARGIN);
        int contentHeight = players.size() * ROW_HEIGHT;
        double maxScroll = Math.max(0, contentHeight - visibleHeight);
        scroll = Math.max(0, Math.min(scroll, maxScroll));

        for (int i = 0; i < players.size(); i++) {
            var p = players.get(i);
            int y = LIST_TOP + i * ROW_HEIGHT - (int) Math.round(scroll);
            sliders.add(addRenderableWidget(new VolumeSlider(p.uuid(), p.name(), left, y, width, voice.getPlayerVolume(p.uuid()))));
        }
        updateSliderVisibility();
    }

    private void updateSliders() {
        int left = (this.width - WIDTH) / 2 + 12;
        int visibleHeight = Math.max(20, this.height - LIST_TOP - LIST_BOTTOM_MARGIN);
        int contentHeight = players.size() * ROW_HEIGHT;
        double maxScroll = Math.max(0, contentHeight - visibleHeight);
        scroll = Math.max(0, Math.min(scroll, maxScroll));

        for (int i = 0; i < sliders.size(); i++) {
            sliders.get(i).setX(left);
            sliders.get(i).setY(LIST_TOP + i * ROW_HEIGHT - (int) Math.round(scroll));
        }
        updateSliderVisibility();
    }

    private void updateSliderVisibility() {
        int top = LIST_TOP;
        int bottom = this.height - LIST_BOTTOM_MARGIN;
        for (VolumeSlider slider : sliders) {
            slider.visible = slider.getY() + slider.getHeight() >= top && slider.getY() <= bottom;
        }
    }

    @Override
    public void tick() {
        List<RealmVoiceClient.VoiceNetwork.PlayerInfo> fresh = voice.getPlayersSnapshot();
        if (!fresh.equals(players)) refreshPlayers();
        if (muteButton != null) muteButton.setMessage(toggleText("Mute", voice.isMuted()));
        if (deafenButton != null) deafenButton.setMessage(toggleText("Deafen", voice.isDeafened()));
    }

    @Override
    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        if (x >= (this.width - WIDTH) / 2 + 8 && x <= (this.width + WIDTH) / 2 - 8
                && y >= LIST_TOP - 4 && y <= this.height - LIST_BOTTOM_MARGIN) {
            scroll -= scrollY * ROW_HEIGHT * 2.0;
            updateSliders();
            return true;
        }
        return super.mouseScrolled(x, y, scrollX, scrollY);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        int left = (this.width - WIDTH) / 2;
        int bottom = this.height - LIST_BOTTOM_MARGIN;

        int statusColor = voice.isServerConnected() ? 0xFF55DD66 : 0xFFFF5555;
        graphics.text(this.font, Component.literal("RealmVoice"), left + 12, 18, 0xFFFFFFFF, true);
        graphics.text(this.font,
                Component.literal("Server: " + (voice.isServerConnected() ? "CONNECTED" : "DISCONNECTED")
                        + "  •  Voice range: 150 blocks"),
                left + 12, 30, statusColor, false);
        graphics.text(this.font, Component.literal("V = mute/unmute microphone   •   X = this screen"),
                left + 12, 126, 0xFFAAAAAA, false);
        graphics.text(this.font,
                Component.literal("Connected voice clients: " + voice.getConnectedPlayerCount()),
                left + 12, 136, 0xFFE0E0E0, true);
        graphics.outline(left + 8, LIST_TOP + 10, WIDTH - 16, bottom - LIST_TOP - 2, 0x66888888);

        if (players.isEmpty()) {
            graphics.text(this.font, Component.literal("No other RealmVoice clients connected."),
                    left + 24, LIST_TOP + 25, 0xFF888888, false);
        }
    }

    @Override
    public void onClose() {
        close();
    }

    private void close() {
        voice.saveSettings();
        client.gui.setScreen(parent);
    }

    private static Component toggleText(String label, boolean on) {
        return Component.literal(label + ": " + (on ? "ON" : "OFF"));
    }

    /** Generic 0..max % slider used for mic / incoming master volume. */
    private final class PercentSlider extends AbstractSliderButton {
        private final String label;
        private final int max;
        private final IntConsumer onChange;

        private PercentSlider(String label, int x, int y, int width, int percent, int max, IntConsumer onChange) {
            super(x, y, width, 20, Component.empty(), Math.max(0, Math.min(max, percent)) / (double) max);
            this.label = label;
            this.max = max;
            this.onChange = onChange;
            updateMessage();
        }

        private int percent() {
            return (int) Math.round(this.value * max);
        }

        @Override
        protected void updateMessage() {
            setMessage(Component.literal(label + ": " + percent() + "%"));
        }

        @Override
        protected void applyValue() {
            onChange.accept(percent());
        }
    }

    private final class VolumeSlider extends AbstractSliderButton {
        private final UUID uuid;
        private final String name;

        private VolumeSlider(UUID uuid, String name, int x, int y, int width, int percent) {
            super(x, y, width, 20, Component.empty(), Math.max(0, Math.min(100, percent)) / 100.0);
            this.uuid = uuid;
            this.name = name;
            updateMessage();
        }

        private int percent() {
            return (int) Math.round(this.value * 100.0);
        }

        @Override
        protected void updateMessage() {
            setMessage(Component.literal(name + ": " + percent() + "%"));
        }

        @Override
        protected void applyValue() {
            voice.setPlayerVolume(uuid, percent());
        }
    }
}