package com.halil.realmvoice;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.SourceDataLine;
import javax.sound.sampled.TargetDataLine;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Client-only RealmVoice. Minecraft Realms does not need to know about it.
 * The client talks directly to the external UDP voice server.
 */
public final class RealmVoiceClient implements ClientModInitializer {
    private static final String MOD_ID = "realmvoice";
    private static final int VERSION = 1;
    private static final int PACKET_HELLO = 1;
    private static final int PACKET_POSITION = 2;
    private static final int PACKET_AUDIO = 3;
    private static final int PACKET_WELCOME = 100;
    private static final int PACKET_PLAYER_LIST = 101;

    // Voice protocol is always 16 kHz mono PCM.
    private static final int VOICE_RATE = 16_000;
    private static final int FRAME_MS = 20;
    private static final int VOICE_SAMPLES = VOICE_RATE * FRAME_MS / 1000; // 320
    private static final int VOICE_FRAME_BYTES = VOICE_SAMPLES * 2; // 640

    // Common Windows hardware rate. We capture/output at 48 kHz and convert to 16 kHz.
    private static final int IO_RATE = 48_000;
    private static final int IO_SAMPLES = IO_RATE * FRAME_MS / 1000; // 960
    private static final int IO_MONO_FRAME_BYTES = IO_SAMPLES * 2;
    private static final int IO_STEREO_FRAME_BYTES = IO_SAMPLES * 4;

    private static final AudioFormat MIC_FORMAT = new AudioFormat(
            AudioFormat.Encoding.PCM_SIGNED,
            IO_RATE,
            16,
            1,
            2,
            IO_RATE,
            false
    );

    private static final AudioFormat OUTPUT_FORMAT = new AudioFormat(
            AudioFormat.Encoding.PCM_SIGNED,
            IO_RATE,
            16,
            2,
            4,
            IO_RATE,
            false
    );

    private static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(
            Identifier.fromNamespaceAndPath(MOD_ID, "category")
    );

    /** V = mute/unmute microphone. It is a toggle, not push-to-talk. */
    private static final KeyMapping TOGGLE_MIC = KeyMappingHelper.registerKeyMapping(
            new KeyMapping(
                    "key.realmvoice.toggle_voice",
                    InputConstants.Type.KEYBOARD,
                    InputConstants.KEY_V,
                    CATEGORY
            )
    );

    /** X = open the small settings/status GUI. */
    private static final KeyMapping OPEN_SETTINGS = KeyMappingHelper.registerKeyMapping(
            new KeyMapping(
                    "key.realmvoice.open_settings",
                    InputConstants.Type.KEYBOARD,
                    InputConstants.KEY_X,
                    CATEGORY
            )
    );

    private static volatile RealmVoiceClient INSTANCE;

    private final Config config = Config.load();
    private final VoiceNetwork voice = new VoiceNetwork(config);

    @Override
    public void onInitializeClient() {
        INSTANCE = this;

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null || client.level == null) {
                voice.stop();
                return;
            }

            voice.start(client.player.getUUID(), client.player.getName().getString());

            while (OPEN_SETTINGS.consumeClick()) {
                if (client.gui.screen() == null) {
                    client.gui.setScreen(new RealmVoiceScreen(client, this));
                }
            }

            if (client.gui.screen() == null) {
                while (TOGGLE_MIC.consumeClick()) {
                    voice.toggleMuted();
                }
            }

            voice.updatePosition(
                    client.level.dimension().identifier().toString(),
                    client.player.getX(),
                    client.player.getY(),
                    client.player.getZ(),
                    client.player.getYRot()
            );
        });

        Runtime.getRuntime().addShutdownHook(new Thread(voice::stop, "realmvoice-shutdown"));
    }

    public VoiceNetwork voice() {
        return voice;
    }

    public Config config() {
        return config;
    }

    public static boolean isMicrophoneActive() {
        RealmVoiceClient instance = INSTANCE;
        return instance != null && instance.voice.isMicrophoneActive();
    }

    public static String trimDeviceName(String name) {
        if (name == null || name.isBlank()) return "Default";
        if (name.length() <= 30) return name;
        return name.substring(0, 27) + "...";
    }

    public static final class Config {
        private final Path path;
        private volatile String server;
        private volatile int port;
        private volatile String room;
        private volatile String token;
        private volatile boolean muted;
        private volatile boolean deafened;
        private volatile String inputMixer;
        private volatile String outputMixer;
        private final Map<UUID, Integer> playerVolumes = new ConcurrentHashMap<>();
        /** Outgoing (your microphone) volume, 0-200 %. */
        private volatile int micVolume = 100;
        /** Incoming (everyone else) master volume, 0-200 %. */
        private volatile int outputVolume = 100;
        /** false = no distance fade and no left/right panning (flat, centered audio). */
        private volatile boolean spatialAudio = false;

        private Config(Path path, String server, int port, String room, String token,
                       boolean muted, boolean deafened, String inputMixer, String outputMixer) {
            this.path = path;
            this.server = server;
            this.port = port;
            this.room = room;
            this.token = token;
            this.muted = muted;
            this.deafened = deafened;
            this.inputMixer = inputMixer;
            this.outputMixer = outputMixer;
        }

        static Config load() {
            Path path = FabricLoader.getInstance().getConfigDir().resolve("realmvoice.properties");
            Properties p = new Properties();

            try {
                if (Files.exists(path)) {
                    try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                        p.load(reader);
                    }
                }
            } catch (IOException e) {
                throw new RuntimeException("Could not load " + path, e);
            }

            Config config = new Config(
                    path,
                    p.getProperty("server", "YOUR_ORACLE_PUBLIC_IP").trim(),
                    parseInt(p.getProperty("port", "24454"), 24454, 1, 65535),
                    p.getProperty("room", "realm").trim(),
                    p.getProperty("token", "CHANGE_ME").trim(),
                    parseBoolean(p.getProperty("muted", "false"), false),
                    parseBoolean(p.getProperty("deafened", "false"), false),
                    p.getProperty("input_mixer", "").trim(),
                    p.getProperty("output_mixer", "").trim()
            );

            config.micVolume = parseInt(p.getProperty("mic_volume", "100"), 100, 0, 200);
            config.outputVolume = parseInt(p.getProperty("output_volume", "100"), 100, 0, 200);
            config.spatialAudio = parseBoolean(p.getProperty("spatial_audio", "false"), false);

            for (String key : p.stringPropertyNames()) {
                if (!key.startsWith("volume.")) continue;
                try {
                    UUID id = UUID.fromString(key.substring("volume.".length()));
                    int volume = parseInt(p.getProperty(key), 100, 0, 100);
                    config.playerVolumes.put(id, volume);
                } catch (IllegalArgumentException ignored) {
                }
            }

            if (!Files.exists(path)) config.save();
            return config;
        }

        public synchronized void save() {
            Properties p = new Properties();
            p.setProperty("server", server);
            p.setProperty("port", Integer.toString(port));
            p.setProperty("room", room);
            p.setProperty("token", token);
            p.setProperty("muted", Boolean.toString(muted));
            p.setProperty("deafened", Boolean.toString(deafened));
            p.setProperty("input_mixer", inputMixer);
            p.setProperty("output_mixer", outputMixer);
            p.setProperty("mic_volume", Integer.toString(micVolume));
            p.setProperty("output_volume", Integer.toString(outputVolume));
            p.setProperty("spatial_audio", Boolean.toString(spatialAudio));
            for (Map.Entry<UUID, Integer> entry : playerVolumes.entrySet()) {
                p.setProperty("volume." + entry.getKey(), Integer.toString(entry.getValue()));
            }

            try {
                Files.createDirectories(path.getParent());
                try (var writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                    p.store(writer, "RealmVoice configuration");
                }
            } catch (IOException e) {
                System.err.println("[RealmVoice] Config save failed: " + e.getMessage());
            }
        }

        private static boolean parseBoolean(String value, boolean fallback) {
            if (value == null) return fallback;
            if (value.equalsIgnoreCase("true")) return true;
            if (value.equalsIgnoreCase("false")) return false;
            return fallback;
        }

        private static int parseInt(String value, int fallback, int min, int max) {
            try {
                return Math.max(min, Math.min(max, Integer.parseInt(value.trim())));
            } catch (Exception e) {
                return fallback;
            }
        }
    }

    public static final class VoiceNetwork {
        private static final int MAX_QUEUE_FRAMES = 6;
        private static final long CLIENT_RESPONSE_TIMEOUT_MS = 6000;
        private static final long POSITION_INTERVAL_NS = 100_000_000L;
        private static final long HELLO_INTERVAL_NS = 2_000_000_000L;

        private final Config config;
        private final AtomicBoolean running = new AtomicBoolean(false);
        private final AtomicBoolean muted = new AtomicBoolean(false);
        private final AtomicBoolean deafened = new AtomicBoolean(false);
        private final Map<UUID, ConcurrentLinkedQueue<AudioFrame>> audioQueues = new ConcurrentHashMap<>();
        private final Map<UUID, PlayerInfo> players = new ConcurrentHashMap<>();

        private volatile DatagramSocket socket;
        private volatile InetSocketAddress serverAddress;
        private volatile Thread receiverThread;
        private volatile Thread microphoneThread;
        private volatile Thread playbackThread;
        private volatile UUID uuid;
        private volatile String playerName = "Player";
        private volatile String lastDimension = "";
        private volatile double lastX;
        private volatile double lastY;
        private volatile double lastZ;
        private volatile float lastYaw;
        private volatile long lastPositionNanos;
        private volatile long lastHelloNanos;
        private volatile long lastServerPacketMs;
        private volatile boolean serverConnected;
        private volatile TargetDataLine activeMicrophone;
        private volatile SourceDataLine outputLine;
        private volatile boolean microphoneReady;
        private volatile boolean outputReady;
        private int sequence;

        private VoiceNetwork(Config config) {
            this.config = config;
            muted.set(config.muted);
            deafened.set(config.deafened);
        }

        synchronized void start(UUID playerUuid, String name) {
            uuid = playerUuid;
            playerName = name;
            if (running.get()) return;

            try {
                if (config.server.isBlank() || config.server.equalsIgnoreCase("YOUR_ORACLE_PUBLIC_IP")) {
                    System.err.println("[RealmVoice] Set server=YOUR_ORACLE_PUBLIC_IP in .minecraft/config/realmvoice.properties");
                    return;
                }
                if (config.token.isBlank() || config.token.equalsIgnoreCase("CHANGE_ME")) {
                    System.err.println("[RealmVoice] Set the same token as the Oracle server in realmvoice.properties");
                    return;
                }

                serverAddress = new InetSocketAddress(InetAddress.getByName(config.server), config.port);
                socket = new DatagramSocket();
                socket.setReceiveBufferSize(2 * 1024 * 1024);
                socket.setSendBufferSize(2 * 1024 * 1024);
                running.set(true);
                serverConnected = false;

                receiverThread = daemonThread(this::receiverLoop, "realmvoice-receiver");
                microphoneThread = daemonThread(this::microphoneLoop, "realmvoice-microphone");
                playbackThread = daemonThread(this::playbackLoop, "realmvoice-playback");
                receiverThread.start();
                microphoneThread.start();
                playbackThread.start();

                sendHello();
                System.out.println("[RealmVoice] Voice client started -> " + config.server + ":" + config.port);
            } catch (Exception e) {
                stop();
                System.err.println("[RealmVoice] Start failed: " + e.getMessage());
            }
        }

        private static Thread daemonThread(Runnable task, String name) {
            Thread t = new Thread(task, name);
            t.setDaemon(true);
            return t;
        }

        synchronized void stop() {
            boolean wasRunning = running.getAndSet(false);
            serverConnected = false;
            microphoneReady = false;
            outputReady = false;
            if (!wasRunning) return;

            TargetDataLine mic = activeMicrophone;
            activeMicrophone = null;
            closeInput(mic);

            SourceDataLine out = outputLine;
            outputLine = null;
            closeOutput(out);

            DatagramSocket current = socket;
            socket = null;
            if (current != null) current.close();

            audioQueues.clear();
            players.clear();
        }

        public void toggleMuted() {
            boolean value = !muted.get();
            muted.set(value);
            config.muted = value;
            restartMicrophone();
            config.save();
            System.out.println("[RealmVoice] Microphone " + (value ? "MUTED" : "UNMUTED"));
        }

        public void setMuted(boolean value) {
            muted.set(value);
            config.muted = value;
            restartMicrophone();
            config.save();
        }

        public void setDeafened(boolean value) {
            deafened.set(value);
            config.deafened = value;
            if (value) audioQueues.values().forEach(ConcurrentLinkedQueue::clear);
            config.save();
            System.out.println("[RealmVoice] Deafen " + (value ? "ON" : "OFF"));
        }

        public boolean isMuted() { return muted.get(); }
        public boolean isDeafened() { return deafened.get(); }
        public boolean isServerConnected() {
            return serverConnected && running.get() && System.currentTimeMillis() - lastServerPacketMs <= CLIENT_RESPONSE_TIMEOUT_MS;
        }
        public int getConnectedPlayerCount() { return getPlayersSnapshot().size(); }

        public List<PlayerInfo> getPlayersSnapshot() {
            return players.values().stream()
                    .filter(p -> uuid == null || !p.uuid().equals(uuid))
                    .sorted(Comparator.comparing(PlayerInfo::name, String.CASE_INSENSITIVE_ORDER))
                    .toList();
        }

        public int getPlayerVolume(UUID player) {
            return config.playerVolumes.getOrDefault(player, 100);
        }

        public void setPlayerVolume(UUID player, int value) {
            config.playerVolumes.put(player, Math.max(0, Math.min(100, value)));
        }

        public int getMicVolume() { return config.micVolume; }
        public void setMicVolume(int percent) { config.micVolume = Math.max(0, Math.min(200, percent)); }
        public int getOutputVolume() { return config.outputVolume; }
        public void setOutputVolume(int percent) { config.outputVolume = Math.max(0, Math.min(200, percent)); }

        public String getInputMixer() { return config.inputMixer; }
        public String getOutputMixer() { return config.outputMixer; }

        public List<String> getInputDevices() {
            return listMixers(TargetDataLine.class, MIC_FORMAT);
        }

        public List<String> getOutputDevices() {
            return listMixers(SourceDataLine.class, OUTPUT_FORMAT);
        }

        public void cycleInputMixer() {
            List<String> devices = getInputDevices();
            if (devices.isEmpty()) return;
            config.inputMixer = nextDevice(config.inputMixer, devices);
            restartMicrophone();
            config.save();
        }

        public void cycleOutputMixer() {
            List<String> devices = getOutputDevices();
            if (devices.isEmpty()) return;
            config.outputMixer = nextDevice(config.outputMixer, devices);
            closeOutput(outputLine);
            outputLine = null;
            outputReady = false;
            config.save();
        }

        public boolean isMicrophoneActive() {
            return running.get() && isServerConnected() && !muted.get() && microphoneReady;
        }

        public void saveSettings() { config.save(); }

        void updatePosition(String dimension, double x, double y, double z, float yaw) {
            if (!running.get()) return;
            long now = System.nanoTime();
            if (!dimension.equals(lastDimension) || now - lastPositionNanos >= POSITION_INTERVAL_NS) {
                lastDimension = dimension;
                lastX = x;
                lastY = y;
                lastZ = z;
                lastYaw = yaw;
                lastPositionNanos = now;
                sendPositionPacket();
            }
            if (now - lastHelloNanos >= HELLO_INTERVAL_NS) {
                sendHello();
            }
        }

        private void sendHello() {
            if (!running.get() || uuid == null) return;
            String json = String.format(Locale.ROOT,
                    "{\"token\":\"%s\",\"room\":\"%s\",\"uuid\":\"%s\",\"name\":\"%s\"}",
                    jsonEscape(config.token), jsonEscape(config.room), uuid,
                    jsonEscape(playerName));
            sendControl(PACKET_HELLO, json);
            lastHelloNanos = System.nanoTime();
        }

        private void sendPositionPacket() {
            if (!running.get() || uuid == null) return;
            String json = String.format(Locale.ROOT,
                    "{\"room\":\"%s\",\"uuid\":\"%s\",\"name\":\"%s\",\"dim\":\"%s\",\"x\":%.5f,\"y\":%.5f,\"z\":%.5f,\"yaw\":%.5f}",
                    jsonEscape(config.room), uuid, jsonEscape(playerName), jsonEscape(lastDimension),
                    lastX, lastY, lastZ, lastYaw);
            sendControl(PACKET_POSITION, json);
        }

        private void sendControl(int type, String json) {
            DatagramSocket current = socket;
            InetSocketAddress target = serverAddress;
            if (!running.get() || current == null || target == null) return;
            byte[] body = json.getBytes(StandardCharsets.UTF_8);
            ByteBuffer packet = ByteBuffer.allocate(2 + body.length).order(ByteOrder.BIG_ENDIAN);
            packet.put((byte) VERSION).put((byte) type).put(body);
            send(packet.array(), target);
        }

        private void sendAudio(byte[] pcm) {
            DatagramSocket current = socket;
            InetSocketAddress target = serverAddress;
            if (!running.get() || current == null || target == null || uuid == null || pcm.length != VOICE_FRAME_BYTES) return;
            byte[] id = uuid.toString().getBytes(StandardCharsets.US_ASCII);
            ByteBuffer packet = ByteBuffer.allocate(2 + 36 + 2 + pcm.length).order(ByteOrder.BIG_ENDIAN);
            packet.put((byte) VERSION).put((byte) PACKET_AUDIO).put(id, 0, 36);
            packet.putShort((short) (sequence++ & 0xFFFF));
            packet.put(pcm);
            send(packet.array(), target);
        }

        private void send(byte[] data, InetSocketAddress target) {
            DatagramSocket current = socket;
            if (current == null) return;
            try {
                current.send(new DatagramPacket(data, data.length, target.getAddress(), target.getPort()));
            } catch (IOException e) {
                if (running.get()) System.err.println("[RealmVoice] UDP send failed: " + e.getMessage());
            }
        }

        private void receiverLoop() {
            byte[] buffer = new byte[1500];
            while (running.get()) {
                try {
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    DatagramSocket current = socket;
                    if (current == null) return;
                    current.receive(packet);
                    byte[] data = Arrays.copyOf(packet.getData(), packet.getLength());
                    lastServerPacketMs = System.currentTimeMillis();
                    handleIncoming(data);
                } catch (SocketException e) {
                    if (running.get()) System.err.println("[RealmVoice] Receiver stopped: " + e.getMessage());
                    return;
                } catch (IOException e) {
                    if (running.get()) System.err.println("[RealmVoice] Receiver error: " + e.getMessage());
                }
            }
        }

        private void handleIncoming(byte[] data) {
            if (data.length < 2 || (data[0] & 0xFF) != VERSION) return;
            int type = data[1] & 0xFF;

            if (type == PACKET_WELCOME) {
                boolean ok = data.length >= 3 && data[2] == 1;
                serverConnected = ok;
                System.out.println("[RealmVoice] Server connection: " + (ok ? "OK" : "REJECTED (check token)"));
                return;
            }

            if (type == PACKET_PLAYER_LIST) {
                handlePlayerList(data);
                serverConnected = true;
                return;
            }

            if (type != PACKET_AUDIO) return;
            if (deafened.get()) return;
            if (data.length < 42 + VOICE_FRAME_BYTES) return;

            String senderText = new String(data, 2, 36, StandardCharsets.US_ASCII);
            UUID sender;
            try {
                sender = UUID.fromString(senderText);
            } catch (IllegalArgumentException e) {
                return;
            }
            if (uuid != null && sender.equals(uuid)) return;

            int gain = data[40] & 0xFF;
            float pan = Math.max(-1.0f, Math.min(1.0f, data[41] / 127.0f));
            byte[] pcm = Arrays.copyOfRange(data, 42, data.length);
            if (pcm.length != VOICE_FRAME_BYTES || gain == 0) return;

            ConcurrentLinkedQueue<AudioFrame> queue = audioQueues.computeIfAbsent(sender, ignored -> new ConcurrentLinkedQueue<>());
            while (queue.size() >= MAX_QUEUE_FRAMES) queue.poll();
            boolean spatial = config.spatialAudio;
            // Flat audio: ignore the server's distance fade and left/right pan.
            // (The server still decides WHO is in range - gain 0 was dropped above.)
            queue.offer(new AudioFrame(pcm, spatial ? gain / 255.0 : 1.0, spatial ? pan : 0.0f));
        }

        private void handlePlayerList(byte[] data) {
            if (data.length < 4) return;
            ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
            b.position(2);
            int count = b.getShort() & 0xFFFF;
            Map<UUID, PlayerInfo> fresh = new HashMap<>();

            for (int i = 0; i < count; i++) {
                if (b.remaining() < 17) return;
                long most = b.getLong();
                long least = b.getLong();
                int len = b.get() & 0xFF;
                if (len > b.remaining()) return;
                byte[] name = new byte[len];
                b.get(name);
                UUID id = new UUID(most, least);
                fresh.put(id, new PlayerInfo(id, new String(name, StandardCharsets.UTF_8)));
            }

            players.clear();
            players.putAll(fresh);
        }

        private void microphoneLoop() {
            while (running.get()) {
                if (muted.get() || !isServerConnected()) {
                    microphoneReady = false;
                    sleep(100);
                    continue;
                }

                TargetDataLine mic = null;
                MicrophoneCleaner cleaner = new MicrophoneCleaner();
                try {
                    mic = openMicrophone();
                    activeMicrophone = mic;
                    mic.start();
                    microphoneReady = true;

                    byte[] capture = new byte[IO_MONO_FRAME_BYTES];
                    while (running.get() && activeMicrophone == mic && !muted.get() && isServerConnected()) {
                        int offset = 0;
                        while (offset < capture.length) {
                            int read = mic.read(capture, offset, capture.length - offset);
                            if (read <= 0) throw new IOException("Microphone returned no data");
                            offset += read;
                        }

                        byte[] voiceFrame = downsample48kTo16k(capture);
                        cleaner.process(voiceFrame);
                        applyGain(voiceFrame, config.micVolume / 100.0);
                        sendAudio(voiceFrame);
                    }
                } catch (Exception e) {
                    if (running.get() && !muted.get()) {
                        System.err.println("[RealmVoice] Microphone error: " + e.getMessage());
                    }
                    sleep(500);
                } finally {
                    microphoneReady = false;
                    if (activeMicrophone == mic) activeMicrophone = null;
                    closeInput(mic);
                }
            }
        }

        private void playbackLoop() {
            long next = System.nanoTime();
            final long frameNs = FRAME_MS * 1_000_000L;

            while (running.get()) {
                try {
                    if (deafened.get()) {
                        audioQueues.values().forEach(ConcurrentLinkedQueue::clear);
                    }

                    int[] left = new int[VOICE_SAMPLES];
                    int[] right = new int[VOICE_SAMPLES];

                    if (!deafened.get()) {
                        for (Map.Entry<UUID, ConcurrentLinkedQueue<AudioFrame>> entry : audioQueues.entrySet()) {
                            AudioFrame frame = entry.getValue().poll();
                            if (frame == null) continue;
                            int localVolume = config.playerVolumes.getOrDefault(entry.getKey(), 100);
                            mixFrame(frame, (localVolume / 100.0) * (config.outputVolume / 100.0), left, right);
                        }
                    }

                    if (playMixedFrame(left, right)) {
                        outputReady = true;
                    } else {
                        outputReady = false;
                    }
                } catch (Exception e) {
                    outputReady = false;
                    if (running.get()) {
                        System.err.println("[RealmVoice] Playback error: " + e.getMessage());
                    }
                    closeOutput(outputLine);
                    outputLine = null;
                    sleep(500);
                }

                next += frameNs;
                long sleepNs = next - System.nanoTime();
                if (sleepNs > 0) {
                    try {
                        Thread.sleep(sleepNs / 1_000_000L, (int) (sleepNs % 1_000_000L));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                } else {
                    next = System.nanoTime();
                }
            }
        }

        private void mixFrame(AudioFrame frame, double localVolume, int[] left, int[] right) {
            double overall = Math.max(0, frame.serverGain * localVolume);
            if (overall <= 0) return;

            double p = Math.max(-1, Math.min(1, frame.pan));
            double leftGain = p > 0 ? 1.0 - p : 1.0;
            double rightGain = p < 0 ? 1.0 + p : 1.0;

            byte[] pcm = frame.pcm;
            for (int i = 0, s = 0; s < VOICE_SAMPLES && i + 1 < pcm.length; s++, i += 2) {
                int sample = (short) ((pcm[i] & 0xFF) | ((pcm[i + 1] & 0xFF) << 8));
                left[s] += (int) Math.round(sample * overall * leftGain);
                right[s] += (int) Math.round(sample * overall * rightGain);
            }
        }

        private boolean playMixedFrame(int[] left, int[] right) throws LineUnavailableException {
            SourceDataLine line = outputLine;
            if (line == null || !line.isOpen()) {
                line = openOutputLine();
                outputLine = line;
            }

            byte[] out = new byte[IO_STEREO_FRAME_BYTES];
            int pos = 0;
            for (int s = 0; s < VOICE_SAMPLES; s++) {
                int l = clamp16(left[s]);
                int r = clamp16(right[s]);
                // 3x upsample: 16 kHz voice -> 48 kHz output.
                for (int repeat = 0; repeat < 3; repeat++) {
                    out[pos++] = (byte) (l & 0xFF);
                    out[pos++] = (byte) ((l >>> 8) & 0xFF);
                    out[pos++] = (byte) (r & 0xFF);
                    out[pos++] = (byte) ((r >>> 8) & 0xFF);
                }
            }
            line.write(out, 0, out.length);
            return true;
        }

        private TargetDataLine openMicrophone() throws LineUnavailableException {
            Mixer.Info selected = findMixer(config.inputMixer, TargetDataLine.class, MIC_FORMAT);
            TargetDataLine line = selected == null
                    ? AudioSystem.getTargetDataLine(MIC_FORMAT)
                    : AudioSystem.getTargetDataLine(MIC_FORMAT, selected);
            line.open(MIC_FORMAT, IO_MONO_FRAME_BYTES * 2);
            return line;
        }

        private SourceDataLine openOutputLine() throws LineUnavailableException {
            Mixer.Info selected = findMixer(config.outputMixer, SourceDataLine.class, OUTPUT_FORMAT);
            try {
                SourceDataLine line = selected == null
                        ? AudioSystem.getSourceDataLine(OUTPUT_FORMAT)
                        : AudioSystem.getSourceDataLine(OUTPUT_FORMAT, selected);
                line.open(OUTPUT_FORMAT, IO_STEREO_FRAME_BYTES * 4);
                line.start();
                return line;
            } catch (LineUnavailableException first) {
                if (selected != null) {
                    SourceDataLine line = AudioSystem.getSourceDataLine(OUTPUT_FORMAT);
                    line.open(OUTPUT_FORMAT, IO_STEREO_FRAME_BYTES * 4);
                    line.start();
                    return line;
                }
                throw first;
            }
        }

        private void restartMicrophone() {
            TargetDataLine mic = activeMicrophone;
            activeMicrophone = null;
            microphoneReady = false;
            closeInput(mic);
        }

        private static List<String> listMixers(Class<?> lineType, AudioFormat format) {
            LinkedHashSet<String> names = new LinkedHashSet<>();
            names.add("");
            DataLine.Info info = new DataLine.Info(lineType, format);
            for (Mixer.Info mixerInfo : AudioSystem.getMixerInfo()) {
                try {
                    Mixer mixer = AudioSystem.getMixer(mixerInfo);
                    if (mixer.isLineSupported(info)) names.add(mixerInfo.getName());
                } catch (Exception ignored) {
                }
            }
            return new ArrayList<>(names);
        }

        private static Mixer.Info findMixer(String requested, Class<?> lineType, AudioFormat format) {
            if (requested == null || requested.isBlank()) return null;
            DataLine.Info info = new DataLine.Info(lineType, format);
            for (Mixer.Info mixerInfo : AudioSystem.getMixerInfo()) {
                if (!mixerInfo.getName().equals(requested)) continue;
                try {
                    Mixer mixer = AudioSystem.getMixer(mixerInfo);
                    if (mixer.isLineSupported(info)) return mixerInfo;
                } catch (Exception ignored) {
                    return null;
                }
            }
            return null;
        }

        private static String nextDevice(String current, List<String> devices) {
            int index = devices.indexOf(current == null ? "" : current);
            return devices.get((index + 1) % devices.size());
        }

        private static void closeInput(TargetDataLine line) {
            if (line == null) return;
            try { line.stop(); } catch (Exception ignored) {}
            try { line.close(); } catch (Exception ignored) {}
        }

        private static void closeOutput(SourceDataLine line) {
            if (line == null) return;
            try { line.stop(); } catch (Exception ignored) {}
            try { line.close(); } catch (Exception ignored) {}
        }

        private static byte[] downsample48kTo16k(byte[] input) {
            byte[] output = new byte[VOICE_FRAME_BYTES];
            for (int i = 0, o = 0; o < output.length; o += 2, i += 6) {
                int a = (short) ((input[i] & 0xFF) | ((input[i + 1] & 0xFF) << 8));
                int b = (short) ((input[i + 2] & 0xFF) | ((input[i + 3] & 0xFF) << 8));
                int c = (short) ((input[i + 4] & 0xFF) | ((input[i + 5] & 0xFF) << 8));
                int sample = (a + b + c) / 3;
                output[o] = (byte) (sample & 0xFF);
                output[o + 1] = (byte) ((sample >>> 8) & 0xFF);
            }
            return output;
        }

        private static void applyGain(byte[] pcm, double gain) {
            if (gain == 1.0) return;
            for (int i = 0; i + 1 < pcm.length; i += 2) {
                int sample = (short) ((pcm[i] & 0xFF) | ((pcm[i + 1] & 0xFF) << 8));
                int out = clamp16((int) Math.round(sample * gain));
                pcm[i] = (byte) (out & 0xFF);
                pcm[i + 1] = (byte) ((out >>> 8) & 0xFF);
            }
        }

        private static int clamp16(int sample) {
            return Math.max(-32768, Math.min(32767, sample));
        }

        private static void sleep(long millis) {
            try { Thread.sleep(millis); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }

        private static String jsonEscape(String value) {
            if (value == null) return "";
            return value.replace("\\", "\\\\").replace("\"", "\\\"");
        }

        public record PlayerInfo(UUID uuid, String name) {}
        private record AudioFrame(byte[] pcm, double serverGain, float pan) {}

        private static final class MicrophoneCleaner {
            private static final double HP_A = Math.exp(-2.0 * Math.PI * 70.0 / VOICE_RATE);
            private static final double LP_ALPHA = 1.0 - Math.exp(-2.0 * Math.PI * 7000.0 / VOICE_RATE);
            private double prevInput;
            private double highPass;
            private double lowPass;

            void process(byte[] pcm) {
                for (int i = 0; i + 1 < pcm.length; i += 2) {
                    int sample = (short) ((pcm[i] & 0xFF) | ((pcm[i + 1] & 0xFF) << 8));
                    double x = sample;
                    double hp = HP_A * (highPass + x - prevInput);
                    prevInput = x;
                    highPass = hp;
                    lowPass += LP_ALPHA * (hp - lowPass);
                    int out = clamp16((int) Math.round(lowPass));
                    pcm[i] = (byte) (out & 0xFF);
                    pcm[i + 1] = (byte) ((out >>> 8) & 0xFF);
                }
            }
        }
    }
}