package Source;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Refactored PlayState focused on:
 * - merged charts: song.json + song-2.json + song-3.json ...
 * - scroll speed read from JSON "speed"
 * - visible-window rendering only
 * - chunked note storage with chunk dropping after notes are behind the playhead
 *
 * Notes:
 * - This is designed to avoid O(total notes) work per frame.
 * - Truly massive charts still depend on RAM and the size of the chart file(s).
 * - For extreme charts, the next step would be a disk-backed streaming index.
 */
public class PlayState extends JPanel implements KeyListener {
    private static final int SCREEN_W = 1280;
    private static final int SCREEN_H = 720;

    private static final int NPS_WINDOW_MS = 1000;
    private static final int STREAM_KEEP_BEHIND_MS = 5000;
    private static final int MISS_DRAW_PADDING = 120;
    private static final int HIT_Y = 100;
    private static final int FINAL_RENDER_PADDING_MS = 5000;

    // Tune this up/down depending on your RAM and how long you keep old notes around.
    private static final int MAX_BUFFERED_NOTES = 1_500_000;
    private static final int CHUNK_SIZE = 8192;

    private final JFrame frame;
    private final String songName;
    private final boolean botPlay;
    private final boolean renderMode;
    private final int targetFPS;

    private Process ffmpegProcess;
    private OutputStream videoOutput;
    private BufferedImage offscreenImage;
    private Graphics2D offscreenGraphics;
    private byte[] ffmpegRgbData;
    private boolean recordingFinished = false;

    private BufferedImage stageBG;
    private BufferedImage leftArrow, downArrow, upArrow, rightArrow;
    private BufferedImage leftComing, downComing, upComing, rightComing;
    private BufferedImage leftPress, downPress, upPress, rightPress;
    private BufferedImage leftGlow, downGlow, upGlow, rightGlow;
    private final BufferedImage[] numberDigits = new BufferedImage[10];
    private BufferedImage numberComma;

    private volatile boolean chartParsingFinished = false;
    private volatile boolean parserStarted = false;
    private volatile boolean parserError = false;

    private long totalNotes = 0L;
    private long bufferedNotes = 0L;
    private final Object bufferLock = new Object();

    private final Object laneLock = new Object();
    private final Object renderLock = new Object();

    private volatile boolean running = true;
    private double songTimeMs = 0.0;
    private long lastUpdateNano = System.nanoTime();

    private int fps = 0;
    private int frameCount = 0;
    private long lastFpsTick = System.currentTimeMillis();

    private long usedMemoryMB = 0;
    private long maxMemoryMB = 0;

    private final double baseScrollSpeed = 0.45;
    private double songSpeed = 10.0;
    private double renderSpeed = 1.0;

    private final int hitWindowMs = 150;
    private final int missWindowMs = 180;
    private final int flashFrames = 8;

    private int opponentCurrentNps = 0;
    private int opponentMaxNps = 0;
    private int playerCurrentNps = 0;
    private int playerMaxNps = 0;

    private int laneCount;
    private int[] laneDirections;
    private int[] laneKeys;

    private LaneStream[] playerLanes;
    private LaneStream[] opponentLanes;
    private boolean[] playerHeld;
    private int[] playerGlow;
    private int[] opponentGlow;

    private int playerCombo = 0;
    private int opponentCombo = 0;
    private long comboPopupValue = 0;
    private int popupFrames = 0;
    private final int popupMaxFrames = 45;
    private long lastNoteTimeMs = 0L;
    private long sharedCombo = 0;

    private final Font hudFont = new Font("Monospaced", Font.BOLD, 16);
    private final Font statFont = new Font("Monospaced", Font.BOLD, 18);

    private static final int[] KEY_POOL = {
            KeyEvent.VK_A, KeyEvent.VK_S, KeyEvent.VK_D, KeyEvent.VK_F, KeyEvent.VK_G, KeyEvent.VK_H,
            KeyEvent.VK_J, KeyEvent.VK_K, KeyEvent.VK_L, KeyEvent.VK_Z, KeyEvent.VK_X, KeyEvent.VK_C,
            KeyEvent.VK_V, KeyEvent.VK_B, KeyEvent.VK_N, KeyEvent.VK_M, KeyEvent.VK_Q, KeyEvent.VK_W,
            KeyEvent.VK_E, KeyEvent.VK_R, KeyEvent.VK_T, KeyEvent.VK_Y, KeyEvent.VK_U, KeyEvent.VK_I,
            KeyEvent.VK_O, KeyEvent.VK_P, KeyEvent.VK_1, KeyEvent.VK_2, KeyEvent.VK_3, KeyEvent.VK_4,
            KeyEvent.VK_5, KeyEvent.VK_6, KeyEvent.VK_7, KeyEvent.VK_8, KeyEvent.VK_9, KeyEvent.VK_0,
            KeyEvent.VK_F1, KeyEvent.VK_F2, KeyEvent.VK_F3, KeyEvent.VK_F4, KeyEvent.VK_F5, KeyEvent.VK_F6,
            KeyEvent.VK_F7, KeyEvent.VK_F8, KeyEvent.VK_F9, KeyEvent.VK_F10, KeyEvent.VK_F11, KeyEvent.VK_F12,
            KeyEvent.VK_COMMA, KeyEvent.VK_PERIOD, KeyEvent.VK_SLASH, KeyEvent.VK_SEMICOLON, KeyEvent.VK_OPEN_BRACKET,
            KeyEvent.VK_CLOSE_BRACKET, KeyEvent.VK_MINUS, KeyEvent.VK_EQUALS, KeyEvent.VK_BACK_SLASH, KeyEvent.VK_BACK_QUOTE
    };

    private static final class Note {
        final double time;
        final float sustain;

        Note(double time, float sustain) {
            this.time = time;
            this.sustain = sustain;
        }
    }

    /**
     * Chunked notes per lane.
     * Good for huge charts because we can drop whole chunks that are entirely behind the playhead.
     */
    private static final class LaneStream {
        private final ArrayList<double[]> timeChunks = new ArrayList<>();
        private final ArrayList<float[]> sustainChunks = new ArrayList<>();
        private final ArrayList<Integer> chunkSizes = new ArrayList<>();
        private final ArrayList<Double> chunkMaxTimes = new ArrayList<>();

        private int totalSize = 0;
        private int headChunk = 0;
        private int headIndex = 0;
        private int hitCursor = 0;
        private int npsStart = 0;
        private int npsEnd = 0;

        synchronized void add(double time, float sustain) {
            if (timeChunks.isEmpty() || chunkSizes.get(chunkSizes.size() - 1) >= CHUNK_SIZE) {
                timeChunks.add(new double[CHUNK_SIZE]);
                sustainChunks.add(new float[CHUNK_SIZE]);
                chunkSizes.add(0);
                chunkMaxTimes.add(Double.NEGATIVE_INFINITY);
            }

            int last = timeChunks.size() - 1;
            int idx = chunkSizes.get(last);
            timeChunks.get(last)[idx] = time;
            sustainChunks.get(last)[idx] = sustain;
            chunkSizes.set(last, idx + 1);
            chunkMaxTimes.set(last, time);
            totalSize++;
        }

        synchronized int size() {
            return totalSize;
        }

        synchronized int hitCursor() {
            return hitCursor;
        }

        synchronized void setHitCursor(int value) {
            hitCursor = Math.max(0, value);
        }

        synchronized int npsStart() { return npsStart; }
        synchronized void setNpsStart(int value) { npsStart = Math.max(0, value); }
        synchronized int npsEnd() { return npsEnd; }
        synchronized void setNpsEnd(int value) { npsEnd = Math.max(0, value); }

        synchronized double timeAt(int globalIndex) {
            if (globalIndex < 0 || globalIndex >= totalSize) {
                throw new IndexOutOfBoundsException("Index: " + globalIndex + " size: " + totalSize);
            }
            int idx = globalIndex;
            for (int c = 0; c < timeChunks.size(); c++) {
                int sz = chunkSizes.get(c);
                if (idx < sz) return timeChunks.get(c)[idx];
                idx -= sz;
            }
            throw new IndexOutOfBoundsException("Index mapping failed");
        }

        synchronized int lowerBound(double value) {
            int lo = 0, hi = totalSize;
            while (lo < hi) {
                int mid = (lo + hi) >>> 1;
                if (timeAt(mid) < value) lo = mid + 1;
                else hi = mid;
            }
            return lo;
        }

        synchronized int upperBound(double value) {
            int lo = 0, hi = totalSize;
            while (lo < hi) {
                int mid = (lo + hi) >>> 1;
                if (timeAt(mid) <= value) lo = mid + 1;
                else hi = mid;
            }
            return lo;
        }

        synchronized int countInWindow(double startInclusive, double endInclusive) {
            int start = lowerBound(startInclusive);
            int end = upperBound(endInclusive);
            return Math.max(0, end - start);
        }

        synchronized int compactBefore(double minTime) {
            int removed = 0;

            while (headChunk < timeChunks.size()) {
                int sz = chunkSizes.get(headChunk);
                if (sz <= 0) {
                    dropHeadChunk();
                    continue;
                }

                double maxTime = chunkMaxTimes.get(headChunk);
                if (maxTime >= minTime) break;

                removed += sz;
                dropHeadChunk();
            }

            if (removed > 0) {
                totalSize -= removed;
                hitCursor = Math.max(0, hitCursor - removed);
                npsStart = Math.max(0, npsStart - removed);
                npsEnd = Math.max(0, npsEnd - removed);
            }

            if (hitCursor < 0) hitCursor = 0;
            if (npsStart < 0) npsStart = 0;
            if (npsEnd < 0) npsEnd = 0;
            return removed;
        }

        private void dropHeadChunk() {
            timeChunks.remove(headChunk);
            sustainChunks.remove(headChunk);
            chunkSizes.remove(headChunk);
            chunkMaxTimes.remove(headChunk);
            headIndex = 0;
        }
    }

    private static final class Layout {
        final double spacing;
        final double noteSize;
        final double width;
        final double opponentX;
        final double playerX;

        Layout(double spacing, double noteSize, double width, double opponentX, double playerX) {
            this.spacing = spacing;
            this.noteSize = noteSize;
            this.width = width;
            this.opponentX = opponentX;
            this.playerX = playerX;
        }
    }

    public PlayState(String songName) {
        this(songName, MainMenu.botPlay);
    }

    public PlayState(String songName, boolean botPlay) {
        this.songName = songName;
        this.renderMode = MainMenu.renderMode;
        this.targetFPS = MainMenu.targetFPS;
        this.botPlay = botPlay || this.renderMode;

        this.laneCount = Math.max(1, MainMenu.extraKeysCount);
        this.laneDirections = buildLaneDirections(this.laneCount);
        this.laneKeys = buildLaneKeys(this.laneCount);
        this.playerLanes = createLaneStreams(this.laneCount);
        this.opponentLanes = createLaneStreams(this.laneCount);
        this.playerHeld = new boolean[this.laneCount];
        this.playerGlow = new int[this.laneCount];
        this.opponentGlow = new int[this.laneCount];

        loadImages();
        loadSongJSON(songName);

        if (this.renderMode) {
            try {
                offscreenImage = new BufferedImage(SCREEN_W, SCREEN_H, BufferedImage.TYPE_INT_RGB);
                offscreenGraphics = offscreenImage.createGraphics();
                offscreenGraphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                offscreenGraphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                offscreenGraphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                ffmpegRgbData = new byte[SCREEN_W * SCREEN_H * 3];
                startFFmpegRecording();
            } catch (Exception e) {
                System.err.println("Failed to initialize video rendering: " + e.getMessage());
                e.printStackTrace();
            }
        }

        frame = new JFrame("Playing: " + songName);
        frame.setSize(SCREEN_W, SCREEN_H);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setResizable(false);
        frame.setLocationRelativeTo(null);
        frame.setIgnoreRepaint(true);
        frame.add(this);
        frame.setVisible(true);
        frame.addKeyListener(this);
        frame.setFocusable(true);
        setFocusable(true);
        setDoubleBuffered(true);
        SwingUtilities.invokeLater(() -> frame.requestFocusInWindow());

        Thread gameThread = new Thread(this::gameLoop, "PlayState-GameLoop");
        gameThread.setDaemon(true);
        gameThread.start();
    }

    private LaneStream[] createLaneStreams(int count) {
        LaneStream[] lanes = new LaneStream[count];
        for (int i = 0; i < count; i++) lanes[i] = new LaneStream();
        return lanes;
    }

    private int[] buildLaneKeys(int count) {
        int[] keys = new int[count];
        for (int i = 0; i < count; i++) keys[i] = KEY_POOL[i % KEY_POOL.length];
        return keys;
    }

    private int directionForLaneIndex(int laneIndex) {
        int mod = laneIndex % 9;
        return switch (mod) {
            case 0 -> 0;
            case 1 -> 1;
            case 2 -> 2;
            case 3 -> 3;
            case 4 -> 2;
            case 5 -> 0;
            case 6 -> 1;
            case 7 -> 2;
            default -> 3;
        };
    }

    private int[] buildLaneDirections(int count) {
        if (count == 1) return new int[]{2};
        if (count == 2) return new int[]{0, 3};
        if (count == 3) return new int[]{0, 2, 3};
        if (count == 4) return new int[]{0, 1, 2, 3};
        if (count == 5) return new int[]{0, 1, 1, 2, 3};
        if (count == 6) return new int[]{0, 2, 3, 0, 1, 3};
        if (count == 7) return new int[]{0, 2, 3, 2, 0, 1, 3};
        if (count == 8) return new int[]{0, 1, 2, 3, 0, 1, 2, 3};
        if (count == 9) return new int[]{0, 1, 2, 3, 2, 0, 1, 2, 3};

        int[] dirs = new int[count];
        for (int i = 0; i < count; i++) dirs[i] = directionForLaneIndex(i);
        return dirs;
    }

    private void ensureLaneCount(int needed) {
        if (needed <= laneCount) return;
        if (!MainMenu.infiniteKeys) return;

        synchronized (laneLock) {
            if (needed <= laneCount) return;

            int oldCount = laneCount;
            int newCount = Math.max(needed, laneCount * 2);

            playerLanes = Arrays.copyOf(playerLanes, newCount);
            opponentLanes = Arrays.copyOf(opponentLanes, newCount);
            playerHeld = Arrays.copyOf(playerHeld, newCount);
            playerGlow = Arrays.copyOf(playerGlow, newCount);
            opponentGlow = Arrays.copyOf(opponentGlow, newCount);
            laneKeys = Arrays.copyOf(laneKeys, newCount);
            laneDirections = Arrays.copyOf(laneDirections, newCount);

            for (int i = oldCount; i < newCount; i++) {
                playerLanes[i] = new LaneStream();
                opponentLanes[i] = new LaneStream();
                laneKeys[i] = KEY_POOL[i % KEY_POOL.length];
                laneDirections[i] = directionForLaneIndex(i);
            }

            laneCount = newCount;
        }
    }

    private void startFFmpegRecording() throws IOException {
        Files.createDirectories(Paths.get("Video"));
        String videoPath = "Video/" + songName + ".mp4";
        String audioPath = getAudioPath();

        ProcessBuilder pb;
        if (audioPath != null) {
            pb = new ProcessBuilder(
                    "ffmpeg", "-y",
                    "-f", "rawvideo",
                    "-pix_fmt", "rgb24",
                    "-s", SCREEN_W + "x" + SCREEN_H,
                    "-r", String.valueOf(targetFPS),
                    "-i", "-",
                    "-i", audioPath,
                    "-c:v", "libx264",
                    "-preset", "veryfast",
                    "-crf", "17",
                    "-c:a", "aac",
                    "-b:a", "320k",
                    "-shortest",
                    videoPath
            );
        } else {
            pb = new ProcessBuilder(
                    "ffmpeg", "-y",
                    "-f", "rawvideo",
                    "-pix_fmt", "rgb24",
                    "-s", SCREEN_W + "x" + SCREEN_H,
                    "-r", String.valueOf(targetFPS),
                    "-i", "-",
                    "-c:v", "libx264",
                    "-preset", "veryfast",
                    "-crf", "17",
                    videoPath
            );
        }

        pb.redirectError(ProcessBuilder.Redirect.INHERIT);
        ffmpegProcess = pb.start();
        videoOutput = ffmpegProcess.getOutputStream();
        System.out.println("[RENDER] Started FFmpeg recording → " + videoPath + " (using audio: " + (audioPath != null ? audioPath : "NONE") + ")");
    }

    private String getAudioPath() {
        String base = "Assets/Songs/" + songName + "/";
        String[] candidates = {
                songName + ".mp3", "song.mp3", "Inst.mp3", "inst.mp3",
                songName + ".ogg", "song.ogg"
        };
        for (String c : candidates) {
            File f = new File(base + c);
            if (f.exists()) return f.getAbsolutePath();
        }
        System.err.println("[RENDER] WARNING: No audio file found for song '" + songName + "'. Video will be silent.");
        return null;
    }

    private void writeFrameToFFmpeg() {
        if (videoOutput == null || offscreenImage == null || ffmpegRgbData == null) return;
        try {
            int[] pixels = ((DataBufferInt) offscreenImage.getRaster().getDataBuffer()).getData();
            int idx = 0;
            for (int pixel : pixels) {
                ffmpegRgbData[idx++] = (byte) ((pixel >> 16) & 0xFF);
                ffmpegRgbData[idx++] = (byte) ((pixel >> 8) & 0xFF);
                ffmpegRgbData[idx++] = (byte) (pixel & 0xFF);
            }
            videoOutput.write(ffmpegRgbData);
        } catch (IOException e) {
            System.err.println("[RENDER] FFmpeg pipe error");
            videoOutput = null;
        }
    }

    private void finishRendering() {
        if (recordingFinished) return;
        recordingFinished = true;
        try {
            if (videoOutput != null) {
                videoOutput.flush();
                videoOutput.close();
                videoOutput = null;
            }
            if (ffmpegProcess != null) {
                ffmpegProcess.waitFor();
            }
        } catch (Exception ignored) {
        }
        running = false;
        if (frame != null) frame.dispose();
        System.out.println("[RENDER] FINISHED! Video saved to Video/" + songName + ".mp4");
        try {
            File videoFolder = new File("Video");
            if (Desktop.isDesktopSupported()) Desktop.getDesktop().open(videoFolder);
        } catch (Exception ignored) {
        }
        SwingUtilities.invokeLater(MainMenu::new);
    }

    private void gameLoop() {
        while (running) {
            long frameStart = System.nanoTime();
            updateGame();

            if (renderMode && offscreenImage != null) {
                synchronized (renderLock) {
                    offscreenGraphics.setColor(Color.BLACK);
                    offscreenGraphics.fillRect(0, 0, SCREEN_W, SCREEN_H);
                    drawScene(offscreenGraphics);
                    writeFrameToFFmpeg();
                }
            } else {
                repaint();
            }

            Toolkit.getDefaultToolkit().sync();

            int targetFrameMs = renderMode ? Math.max(1, 1000 / Math.max(1, targetFPS)) : 16;
            long elapsed = (System.nanoTime() - frameStart) / 1_000_000L;
            long sleep = targetFrameMs - elapsed;

            if (sleep > 0) {
                try {
                    Thread.sleep(sleep);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            } else {
                Thread.yield();
            }
        }
    }

    private void resetChart() {
        songSpeed = 10.0;
        renderSpeed = 1.0;
        songTimeMs = 0.0;
        lastUpdateNano = System.nanoTime();
        sharedCombo = 0;
        lastNoteTimeMs = 0L;
        chartParsingFinished = false;
        totalNotes = 0L;
        bufferedNotes = 0L;
        opponentCurrentNps = 0;
        opponentMaxNps = 0;
        playerCurrentNps = 0;
        playerMaxNps = 0;

        synchronized (laneLock) {
            for (int i = 0; i < laneCount; i++) {
                playerLanes[i] = new LaneStream();
                opponentLanes[i] = new LaneStream();
                playerGlow[i] = 0;
                opponentGlow[i] = 0;
                playerHeld[i] = false;
            }
        }

        playerCombo = 0;
        opponentCombo = 0;
        comboPopupValue = 0;
        popupFrames = 0;
    }

    private void updateGame() {
        long nowNano = System.nanoTime();
        double deltaMs = (nowNano - lastUpdateNano) / 1_000_000.0;
        lastUpdateNano = nowNano;

        frameCount++;
        long now = System.currentTimeMillis();
        if (now - lastFpsTick >= 1000) {
            fps = frameCount;
            frameCount = 0;
            lastFpsTick = now;
        }

        Runtime rt = Runtime.getRuntime();
        usedMemoryMB = (rt.totalMemory() - rt.freeMemory()) / (1024L * 1024L);
        maxMemoryMB = rt.maxMemory() / (1024L * 1024L);

        if (popupFrames > 0) popupFrames--;
        for (int i = 0; i < laneCount; i++) {
            if (playerGlow[i] > 0) playerGlow[i]--;
            if (opponentGlow[i] > 0) opponentGlow[i]--;
        }

        if (renderMode) {
            songTimeMs += 1000.0 / Math.max(1, targetFPS);
            renderSpeed = 1.0;
        } else {
            renderSpeed = 1.0;
            songTimeMs += deltaMs;
        }

        long songTime = getSongTime();
        updateNpsCounters(songTime);
        processOpponentNotes(songTime);
        processPlayerNotes(songTime);
        cleanupOldNotes(songTime);

        if (renderMode && isChartDrained(songTime)) finishRendering();
    }

    private void processOpponentNotes(long songTime) {
        synchronized (laneLock) {
            for (int lane = 0; lane < laneCount; lane++) {
                LaneStream stream = opponentLanes[lane];
                int cursor = stream.hitCursor();
                int size = stream.size();

                while (cursor < size) {
                    double noteTime = stream.timeAt(cursor);
                    if (songTime >= noteTime) {
                        opponentGlow[lane] = flashFrames;
                        opponentCombo++;
                        sharedCombo++;
                        triggerNumberPopup(sharedCombo);
                        cursor++;
                    } else {
                        break;
                    }
                }

                stream.setHitCursor(cursor);
            }
        }
    }

    private void processPlayerNotes(long songTime) {
        synchronized (laneLock) {
            for (int lane = 0; lane < laneCount; lane++) {
                LaneStream stream = playerLanes[lane];
                int cursor = stream.hitCursor();
                int size = stream.size();

                if (botPlay || renderMode) {
                    while (cursor < size) {
                        double noteTime = stream.timeAt(cursor);
                        if (songTime >= noteTime) {
                            playerGlow[lane] = flashFrames;
                            playerCombo++;
                            sharedCombo++;
                            triggerNumberPopup(sharedCombo);
                            cursor++;
                        } else {
                            break;
                        }
                    }
                } else {
                    while (cursor < size) {
                        double noteTime = stream.timeAt(cursor);
                        if (songTime - noteTime > missWindowMs) {
                            playerCombo = 0;
                            sharedCombo = 0;
                            cursor++;
                        } else {
                            break;
                        }
                    }
                }

                stream.setHitCursor(cursor);
            }
        }
    }

    private void cleanupOldNotes(long songTime) {
        double minKeepTime = songTime - STREAM_KEEP_BEHIND_MS;
        long removedTotal = 0;

        synchronized (laneLock) {
            for (int i = 0; i < laneCount; i++) {
                removedTotal += playerLanes[i].compactBefore(minKeepTime);
                removedTotal += opponentLanes[i].compactBefore(minKeepTime);
            }
        }

        if (removedTotal > 0) {
            synchronized (bufferLock) {
                bufferedNotes -= removedTotal;
                if (bufferedNotes < 0) bufferedNotes = 0;
                bufferLock.notifyAll();
            }
        }
    }

    private boolean isChartDrained(long songTime) {
        if (!chartParsingFinished) return false;
        if (songTime < lastNoteTimeMs + FINAL_RENDER_PADDING_MS) return false;

        synchronized (laneLock) {
            for (int i = 0; i < laneCount; i++) {
                if (playerLanes[i].hitCursor() < playerLanes[i].size()) return false;
                if (opponentLanes[i].hitCursor() < opponentLanes[i].size()) return false;
            }
        }
        return true;
    }

    private void updateNpsCounters(long songTime) {
        double windowStart = songTime - NPS_WINDOW_MS;

        int oNps = 0;
        int pNps = 0;

        synchronized (laneLock) {
            for (int lane = 0; lane < laneCount; lane++) {
                oNps += opponentLanes[lane].countInWindow(windowStart, songTime);
                pNps += playerLanes[lane].countInWindow(windowStart, songTime);
            }
        }

        opponentCurrentNps = oNps;
        if (opponentCurrentNps > opponentMaxNps) opponentMaxNps = opponentCurrentNps;

        playerCurrentNps = pNps;
        if (playerCurrentNps > playerMaxNps) playerMaxNps = playerCurrentNps;
    }

    private void triggerNumberPopup(long value) {
        if (!MainMenu.numberPopups) return;
        comboPopupValue = value;
        popupFrames = popupMaxFrames;
    }

    private long getSongTime() {
        return (long) songTimeMs;
    }

    private String formatNumber(long value) {
        return String.format(Locale.US, "%,d", value);
    }

    private String formatMemoryMB(long mb) {
        return mb + "MB";
    }

    private String formatMemoryGB(long mb) {
        return String.format(Locale.US, "%.2fGB", mb / 1024.0);
    }

    private void loadSongJSON(String song) {
        resetChart();
        parserStarted = true;
        parserError = false;
        chartParsingFinished = false;

        Thread parserThread = new Thread(() -> {
            try {
                List<Path> chartFiles = findChartFiles(song);
                if (chartFiles.isEmpty()) throw new FileNotFoundException("No chart JSON found for song: " + song);

                for (Path chart : chartFiles) {
                    try (InputStream in = new BufferedInputStream(Files.newInputStream(chart), 1 << 20)) {
                        new ChartParser(in).parse();
                        System.out.println("[CHART] Loaded: " + chart.getFileName());
                    }
                }

                System.out.println("[CHART] Total notes loaded: " + totalNotes);
                System.out.println("[CHART] Scroll speed: " + songSpeed);
            } catch (Exception e) {
                parserError = true;
                e.printStackTrace();
            } finally {
                chartParsingFinished = true;
                parserStarted = false;
                synchronized (bufferLock) {
                    bufferLock.notifyAll();
                }
            }
        }, "Chart-Parser");

        parserThread.setDaemon(true);
        parserThread.start();
    }

    private List<Path> findChartFiles(String song) throws IOException {
        Path dir = Paths.get("Assets", "Songs", song);
        if (!Files.isDirectory(dir)) throw new FileNotFoundException("Song folder not found: " + dir.toAbsolutePath());

        List<Path> files = new ArrayList<>();
        Path base = dir.resolve(song + ".json");
        Path test = dir.resolve("test.json");

        if (Files.exists(base)) files.add(base);
        else if (Files.exists(test)) files.add(test);

        Pattern splitPattern = Pattern.compile(Pattern.quote(song) + "-(\\d+)\\.json", Pattern.CASE_INSENSITIVE);

        try (var stream = Files.list(dir)) {
            stream.filter(p -> Files.isRegularFile(p) && p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".json"))
                    .forEach(p -> {
                        String name = p.getFileName().toString();
                        Matcher m = splitPattern.matcher(name);
                        if (m.matches()) files.add(p);
                    });
        }

        files.sort(Comparator.comparingInt(p -> chartPartIndex(p.getFileName().toString(), song)));
        return files;
    }

    private int chartPartIndex(String fileName, String song) {
        if (fileName.equalsIgnoreCase(song + ".json")) return 0;
        if (fileName.equalsIgnoreCase("test.json")) return 0;

        Pattern splitPattern = Pattern.compile(Pattern.quote(song) + "-(\\d+)\\.json", Pattern.CASE_INSENSITIVE);
        Matcher m = splitPattern.matcher(fileName);
        if (m.matches()) {
            try {
                return Integer.parseInt(m.group(1));
            } catch (NumberFormatException ignored) {
            }
        }
        return Integer.MAX_VALUE;
    }

    private final class ChartParser {
        private final InputStream in;
        private final byte[] buffer = new byte[1 << 16];
        private int pos = 0;
        private int limit = 0;
        private int peeked = Integer.MIN_VALUE;

        ChartParser(InputStream in) {
            this.in = in;
        }

        void parse() throws IOException {
            skipWs();
            parseValue();
            skipWs();
        }

        private int read() throws IOException {
            if (peeked != Integer.MIN_VALUE) {
                int c = peeked;
                peeked = Integer.MIN_VALUE;
                return c;
            }
            if (pos >= limit) {
                limit = in.read(buffer);
                pos = 0;
                if (limit < 0) return -1;
            }
            return buffer[pos++] & 0xFF;
        }

        private int peek() throws IOException {
            if (peeked == Integer.MIN_VALUE) peeked = read();
            return peeked;
        }

        private void skipWs() throws IOException {
            while (true) {
                int c = peek();
                if (c < 0 || c > 32) return;
                read();
            }
        }

        private void expect(char expected) throws IOException {
            int c = read();
            if (c != expected) throw new IOException("Expected '" + expected + "' but found '" + (char) c + "'");
        }

        private void readLiteral(String literal) throws IOException {
            for (int i = 0; i < literal.length(); i++) {
                int c = read();
                if (c != literal.charAt(i)) throw new IOException("Expected literal: " + literal);
            }
        }

        private String readString() throws IOException {
            expect('"');
            StringBuilder sb = new StringBuilder(64);
            while (true) {
                int c = read();
                if (c < 0) throw new IOException("Unexpected EOF inside string");
                char ch = (char) c;
                if (ch == '"') break;
                if (ch == '\\') {
                    int esc = read();
                    if (esc < 0) throw new IOException("Unexpected EOF in escape sequence");
                    switch (esc) {
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case '/' -> sb.append('/');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'u' -> sb.append(readUnicodeEscape());
                        default -> sb.append((char) esc);
                    }
                } else {
                    sb.append(ch);
                }
            }
            return sb.toString();
        }

        private void skipString() throws IOException {
            expect('"');
            while (true) {
                int c = read();
                if (c < 0) throw new IOException("Unexpected EOF inside string");
                char ch = (char) c;
                if (ch == '"') return;
                if (ch == '\\') {
                    int esc = read();
                    if (esc < 0) throw new IOException("Unexpected EOF in escape sequence");
                    if (esc == 'u') {
                        for (int i = 0; i < 4; i++) {
                            if (read() < 0) throw new IOException("Unexpected EOF in unicode escape");
                        }
                    }
                }
            }
        }

        private char readUnicodeEscape() throws IOException {
            int value = 0;
            for (int i = 0; i < 4; i++) {
                int c = read();
                if (c < 0) throw new IOException("Unexpected EOF in unicode escape");
                int digit = Character.digit((char) c, 16);
                if (digit < 0) throw new IOException("Bad unicode escape");
                value = (value << 4) | digit;
            }
            return (char) value;
        }

        private boolean isNumberChar(char c) {
            return Character.isDigit(c) || c == '.' || c == '-' || c == '+' || c == 'e' || c == 'E';
        }

        private double readNumber() throws IOException {
            skipWs();
            int c = peek();
            if (c < 0) throw new IOException("Expected number");

            boolean negative = false;
            if (c == '-') {
                negative = true;
                read();
                c = peek();
            } else if (c == '+') {
                read();
                c = peek();
            }

            double result = 0.0;
            while (c >= '0' && c <= '9') {
                result = result * 10.0 + (c - '0');
                read();
                c = peek();
            }

            if (c == '.') {
                read();
                c = peek();
                double fraction = 0.0;
                double divisor = 10.0;
                while (c >= '0' && c <= '9') {
                    fraction += (c - '0') / divisor;
                    divisor *= 10.0;
                    read();
                    c = peek();
                }
                result += fraction;
            }

            if (c == 'e' || c == 'E') {
                read();
                c = peek();
                int expSign = 1;
                if (c == '-') {
                    expSign = -1;
                    read();
                    c = peek();
                } else if (c == '+') {
                    read();
                    c = peek();
                }
                int exponent = 0;
                while (c >= '0' && c <= '9') {
                    exponent = exponent * 10 + (c - '0');
                    read();
                    c = peek();
                }
                if (exponent > 0) result *= Math.pow(10.0, expSign * exponent);
            }

            return negative ? -result : result;
        }

        private boolean readBoolean() throws IOException {
            skipWs();
            int c = peek();
            if (c == 't') {
                readLiteral("true");
                return true;
            }
            if (c == 'f') {
                readLiteral("false");
                return false;
            }
            throw new IOException("Expected boolean");
        }

        private void readNull() throws IOException {
            readLiteral("null");
        }

        private void skipValue() throws IOException {
            skipWs();
            int c = peek();
            if (c < 0) return;
            switch (c) {
                case '{' -> parseObject();
                case '[' -> parseArray();
                case '"' -> skipString();
                case 't', 'f' -> readBoolean();
                case 'n' -> readNull();
                default -> {
                    if (isNumberChar((char) c)) readNumber();
                    else read();
                }
            }
        }

        private void parseValue() throws IOException {
            skipWs();
            int c = peek();
            if (c < 0) return;
            switch (c) {
                case '{' -> parseObject();
                case '[' -> parseArray();
                case '"' -> readString();
                case 't', 'f' -> readBoolean();
                case 'n' -> readNull();
                default -> {
                    if (isNumberChar((char) c)) readNumber();
                    else read();
                }
            }
        }

        private void parseObject() throws IOException {
            expect('{');
            skipWs();
            boolean localMustHit = true;

            if (peek() == '}') {
                read();
                return;
            }

            while (true) {
                skipWs();
                if (peek() == '}') {
                    read();
                    return;
                }

                String key = readString();
                skipWs();

                if (peek() == ':') {
                    read();
                } else {
                    recoverToNextObjectToken();
                    continue;
                }

                skipWs();

                // Support common chart metadata.
                if ("speed".equals(key)) {
                    songSpeed = readNumber();
                } else if ("mustHitSection".equals(key)) {
                    localMustHit = readBoolean();
                } else if ("sectionNotes".equals(key)) {
                    parseSectionNotes(localMustHit);
                } else {
                    skipValue();
                }

                skipWs();
                int c = peek();
                if (c == ',') {
                    read();
                    continue;
                }
                if (c == '}') {
                    read();
                    return;
                }
                if (c < 0) return;
                recoverToNextObjectToken();
            }
        }

        private void recoverToNextObjectToken() throws IOException {
            while (true) {
                int c = peek();
                if (c < 0) return;
                if (c == ',' || c == '}') return;
                read();
            }
        }

        private void parseArray() throws IOException {
            expect('[');
            skipWs();
            if (peek() == ']') {
                read();
                return;
            }
            while (true) {
                parseValue();
                skipWs();
                int c = peek();
                if (c == ',') {
                    read();
                    continue;
                }
                if (c == ']') {
                    read();
                    return;
                }
                if (c < 0) return;
                while (true) {
                    c = peek();
                    if (c < 0 || c == ',' || c == ']') break;
                    read();
                }
            }
        }

        private void parseSectionNotes(boolean sectionMustHit) throws IOException {
            expect('[');
            skipWs();
            if (peek() == ']') {
                read();
                return;
            }
            while (true) {
                skipWs();
                if (peek() == ']') {
                    read();
                    return;
                }
                parseNoteEntry(sectionMustHit);
                skipWs();
                int c = peek();
                if (c == ',') {
                    read();
                    continue;
                }
                if (c == ']') {
                    read();
                    return;
                }
                if (c < 0) return;
                read();
            }
        }

        private void parseNoteEntry(boolean sectionMustHit) throws IOException {
            skipWs();
            if (peek() == ']') return;
            expect('[');

            double time = 0;
            int rawLane = 0;
            double sustain = 0;

            try {
                skipWs();
                if (peek() != ']') time = readNumber();
                skipWs();
                if (peek() == ',') {
                    read();
                    skipWs();
                    if (peek() != ']') rawLane = (int) Math.round(readNumber());
                }
                skipWs();
                if (peek() == ',') {
                    read();
                    skipWs();
                    if (peek() != ']') sustain = readNumber();
                }
            } catch (Exception ignored) {
                skipToEndOfArray();
                return;
            }

            skipToEndOfArray();

            if (rawLane < 0) rawLane = 0;
            if (MainMenu.infiniteKeys) ensureLaneCount(rawLane + 1);

            int lane;
            synchronized (laneLock) {
                lane = laneCount == 0 ? 0 : Math.floorMod(rawLane, laneCount);
            }
            addNote(time, lane, sectionMustHit, sustain);
        }

        private void skipToEndOfArray() throws IOException {
            int depth = 1;
            boolean inString = false;
            while (depth > 0) {
                int c = read();
                if (c < 0) return;
                char ch = (char) c;
                if (inString) {
                    if (ch == '\\') read();
                    else if (ch == '"') inString = false;
                    continue;
                }
                if (ch == '"') inString = true;
                else if (ch == '[') depth++;
                else if (ch == ']') depth--;
            }
        }
    }

    private void addNote(double time, int lane, boolean mustHit, double sustain) {
        if (lane < 0) lane = 0;
        if (MainMenu.infiniteKeys) {
            ensureLaneCount(lane + 1);
        } else {
            synchronized (laneLock) {
                if (lane >= laneCount) lane = Math.floorMod(lane, laneCount);
            }
        }

        lastNoteTimeMs = Math.max(lastNoteTimeMs, (long) (time + sustain));

        synchronized (bufferLock) {
            while (bufferedNotes >= MAX_BUFFERED_NOTES && running) {
                try {
                    bufferLock.wait(5);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }

            synchronized (laneLock) {
                if (mustHit) playerLanes[lane].add(time, (float) sustain);
                else opponentLanes[lane].add(time, (float) sustain);
            }

            bufferedNotes++;
            totalNotes++;
            bufferLock.notifyAll();
        }
    }

    private String formatNpsText(int current, int max) {
        String currentStr = MainMenu.commaOnThirdDigits ? formatNumber(current) : String.valueOf(current);
        String maxStr = MainMenu.commaOnThirdDigits ? formatNumber(max) : String.valueOf(max);
        return currentStr + " | " + maxStr;
    }

    private void drawStatBox(Graphics2D g2, int x, int y, String text, float alpha) {
        g2.setFont(statFont);
        FontMetrics fm = g2.getFontMetrics(statFont);
        int paddingX = 14;
        int paddingY = 10;
        int textW = fm.stringWidth(text);
        int boxW = textW + (paddingX * 2);
        int boxH = fm.getHeight() + (paddingY * 2);

        Composite old = g2.getComposite();
        g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));
        g2.setColor(Color.BLACK);
        g2.fillRoundRect(x, y, boxW, boxH, 14, 14);
        g2.setComposite(old);

        g2.setColor(Color.WHITE);
        g2.setStroke(new BasicStroke(2f));
        g2.drawRoundRect(x, y, boxW, boxH, 14, 14);
        g2.drawString(text, x + paddingX, y + paddingY + fm.getAscent());
    }

    private void drawStatBoxRight(Graphics2D g2, int rightX, int y, String text, float alpha) {
        g2.setFont(statFont);
        FontMetrics fm = g2.getFontMetrics(statFont);
        int paddingX = 14;
        int boxW = fm.stringWidth(text) + (paddingX * 2);
        drawStatBox(g2, rightX - boxW, y, text, alpha);
    }

    private void drawHud(Graphics2D g2) {
        float boxAlpha = renderMode ? 0.65f : 0.45f;
        drawStatBox(g2, 20, 650, formatNpsText(opponentCurrentNps, opponentMaxNps), boxAlpha);
        drawStatBoxRight(g2, SCREEN_W - 20, 650, formatNpsText(playerCurrentNps, playerMaxNps), boxAlpha);
        drawStatBox(g2, 20, 20, "FPS: " + fps, boxAlpha);
        drawStatBox(g2, 20, 60, "MEM: " + formatMemoryMB(usedMemoryMB) + " / " + formatMemoryGB(maxMemoryMB), boxAlpha);
        drawStatBox(g2, 20, 100, "SPEED: " + String.format(Locale.US, "%.2f", renderSpeed), boxAlpha);
    }

    private void drawNumberPopup(Graphics2D g2) {
        if (!MainMenu.numberPopups || popupFrames <= 0) return;
        String text = MainMenu.commaOnThirdDigits ? formatNumber(comboPopupValue) : Long.toString(comboPopupValue);

        int digitW = 54;
        int digitH = 72;
        int commaW = 26;
        int totalW = 0;
        for (int i = 0; i < text.length(); i++) totalW += (text.charAt(i) == ',') ? commaW : digitW;

        int x = (SCREEN_W - totalW) / 2;
        int y = 290 - ((popupMaxFrames - popupFrames) * 2);
        float alpha = Math.max(0f, Math.min(1f, popupFrames / (float) popupMaxFrames));

        Composite old = g2.getComposite();
        g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == ',') {
                if (numberComma != null) g2.drawImage(numberComma, x, y + 18, commaW, digitH - 18, null);
                x += commaW;
            } else {
                int idx = c - '0';
                if (idx >= 0 && idx <= 9 && numberDigits[idx] != null) {
                    g2.drawImage(numberDigits[idx], x, y, digitW, digitH, null);
                }
                x += digitW;
            }
        }

        g2.setComposite(old);
    }

    private Layout computeLayout() {
        double availableSideWidth = (SCREEN_W - 240.0) / 2.0;
        double spacing = availableSideWidth / Math.max(1, laneCount);
        double noteSize = Math.min(100.0, Math.max(4.0, spacing * 0.92));
        double width = spacing * laneCount;
        double opponentX = 60.0;
        double playerX = SCREEN_W - 60.0 - width;
        return new Layout(spacing, noteSize, width, opponentX, playerX);
    }

    private void drawImageScaled(Graphics2D g2, BufferedImage img, double x, double y, double w, double h) {
        if (img == null || w <= 0 || h <= 0) return;
        AffineTransform at = new AffineTransform();
        at.translate(x, y);
        at.scale(w / img.getWidth(), h / img.getHeight());
        g2.drawImage(img, at, null);
    }

    /**
     * Visible-window-only note drawing.
     * Only notes that can appear on screen are touched.
     */
    private void drawLaneNotes(Graphics2D g2, boolean isPlayer, double baseX, long songTime, double finalSpeed, Layout layout) {
        LaneStream[] laneArr;
        int[] dirs;
        int count;
        synchronized (laneLock) {
            laneArr = isPlayer ? playerLanes : opponentLanes;
            dirs = laneDirections;
            count = laneCount;
        }

        double visibleTopTime = songTime - (MISS_DRAW_PADDING / finalSpeed) - 200.0;
        double visibleBottomTime = songTime + ((SCREEN_H - HIT_Y) / finalSpeed) + 200.0;

        for (int lane = 0; lane < count; lane++) {
            double x = baseX + (lane * layout.spacing);
            LaneStream stream = laneArr[lane];
            int start = Math.max(stream.hitCursor(), stream.lowerBound(visibleTopTime));
            int end = stream.lowerBound(visibleBottomTime);

            for (int j = start; j < end; j++) {
                double noteTime = stream.timeAt(j);
                double y = HIT_Y + (noteTime - songTime) * finalSpeed;
                if (y > -MISS_DRAW_PADDING && y < SCREEN_H + MISS_DRAW_PADDING) {
                    drawImageScaled(g2, getComing(dirs[lane]), x, y, layout.noteSize, layout.noteSize);
                }
            }
        }
    }

    private void drawStrumLine(Graphics2D g2, double baseX, int[] glow, boolean isPlayer, Layout layout) {
        int count;
        int[] dirs;
        boolean[] held;
        synchronized (laneLock) {
            count = laneCount;
            dirs = laneDirections;
            held = playerHeld;
        }

        for (int i = 0; i < count; i++) {
            double x = baseX + (i * layout.spacing);
            int dir = dirs[i];

            drawImageScaled(g2, getStatic(dir), x, HIT_Y, layout.noteSize, layout.noteSize);

            if (glow[i] > 0) {
                drawImageScaled(g2, getGlow(dir), x, HIT_Y, layout.noteSize, layout.noteSize);
            } else if (isPlayer && held[i]) {
                drawImageScaled(g2, getPress(dir), x, HIT_Y, layout.noteSize, layout.noteSize);
            }
        }
    }

    private int laneFromKey(int keyCode) {
        synchronized (laneLock) {
            for (int i = 0; i < laneKeys.length; i++) {
                if (laneKeys[i] == keyCode) return i;
            }
        }
        return -1;
    }

    private boolean tryHit(int lane) {
        if (lane < 0) return false;

        synchronized (laneLock) {
            if (lane >= laneCount) return false;

            LaneStream stream = playerLanes[lane];
            int cursor = stream.hitCursor();
            int size = stream.size();
            if (cursor >= size) return false;

            double noteTime = stream.timeAt(cursor);
            long time = getSongTime();

            if (Math.abs(time - noteTime) <= hitWindowMs) {
                playerGlow[lane] = flashFrames;
                playerCombo++;
                sharedCombo++;
                triggerNumberPopup(sharedCombo);
                stream.setHitCursor(cursor + 1);
                return true;
            }
        }
        return false;
    }

    private void drawScene(Graphics2D g2) {
        if (stageBG != null) {
            g2.drawImage(stageBG, 0, 0, SCREEN_W, SCREEN_H, null);
        } else {
            g2.setColor(Color.BLACK);
            g2.fillRect(0, 0, SCREEN_W, SCREEN_H);
        }

        Layout layout = computeLayout();
        long songTime = getSongTime();
        double finalSpeed = baseScrollSpeed * songSpeed;

        drawStrumLine(g2, layout.opponentX, opponentGlow, false, layout);
        drawStrumLine(g2, layout.playerX, playerGlow, true, layout);
        drawLaneNotes(g2, false, layout.opponentX, songTime, finalSpeed, layout);
        drawLaneNotes(g2, true, layout.playerX, songTime, finalSpeed, layout);
        drawNumberPopup(g2);
        drawHud(g2);
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g;
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);

        if (renderMode && offscreenImage != null) {
            synchronized (renderLock) {
                g2.drawImage(offscreenImage, 0, 0, null);
            }
        } else {
            drawScene(g2);
        }
        g2.dispose();
    }

    @Override
    public void keyPressed(KeyEvent e) {
        int lane = laneFromKey(e.getKeyCode());
        if (lane >= 0) {
            synchronized (laneLock) {
                if (lane < laneCount) playerHeld[lane] = true;
            }
            tryHit(lane);
            return;
        }

        switch (e.getKeyCode()) {
            case KeyEvent.VK_BACK_SPACE, KeyEvent.VK_ESCAPE -> {
                running = false;
                if (frame != null) frame.dispose();
                SwingUtilities.invokeLater(MainMenu::new);
            }
        }
    }

    @Override
    public void keyReleased(KeyEvent e) {
        int lane = laneFromKey(e.getKeyCode());
        if (lane >= 0) {
            synchronized (laneLock) {
                if (lane < laneCount) playerHeld[lane] = false;
            }
        }
    }

    @Override
    public void keyTyped(KeyEvent e) {
    }

    private void loadImages() {
        try {
            stageBG = ImageIO.read(new File("/home/andre/FNF-JAVA-ENGINE/Assets/Images/Stage.jpg"));
            leftArrow = ImageIO.read(new File("Notes/LeftArrow.png"));
            downArrow = ImageIO.read(new File("Notes/DownArrow.png"));
            upArrow = ImageIO.read(new File("Notes/UpArrow.png"));
            rightArrow = ImageIO.read(new File("Notes/RightArrow.png"));

            leftComing = ImageIO.read(new File("Notes/LeftComing.png"));
            downComing = ImageIO.read(new File("Notes/DownComing.png"));
            upComing = ImageIO.read(new File("Notes/UpComing.png"));
            rightComing = ImageIO.read(new File("Notes/RightComing.png"));

            leftPress = ImageIO.read(new File("Notes/LeftPress.png"));
            downPress = ImageIO.read(new File("Notes/DownPress.png"));
            upPress = ImageIO.read(new File("Notes/UpPress.png"));
            rightPress = ImageIO.read(new File("Notes/RightPress.png"));

            leftGlow = ImageIO.read(new File("Notes/LeftGlow.png"));
            downGlow = ImageIO.read(new File("Notes/DownGlow.png"));
            upGlow = ImageIO.read(new File("Notes/UpGlow.png"));
            rightGlow = ImageIO.read(new File("Notes/RightGlow.png"));

            for (int i = 0; i < 10; i++) {
                numberDigits[i] = ImageIO.read(new File("/home/andre/FNF-JAVA-ENGINE/Assets/Numbers/num" + i + ".png"));
            }
            numberComma = ImageIO.read(new File("/home/andre/FNF-JAVA-ENGINE/Assets/Numbers/numComma.png"));
        } catch (Exception e) {
            System.err.println("IMAGE FOLDER ERROR.");
            e.printStackTrace();
        }
    }

    private BufferedImage getStatic(int dir) {
        return switch (Math.floorMod(dir, 4)) {
            case 0 -> leftArrow;
            case 1 -> downArrow;
            case 2 -> upArrow;
            default -> rightArrow;
        };
    }

    private BufferedImage getComing(int dir) {
        return switch (Math.floorMod(dir, 4)) {
            case 0 -> leftComing;
            case 1 -> downComing;
            case 2 -> upComing;
            default -> rightComing;
        };
    }

    private BufferedImage getPress(int dir) {
        return switch (Math.floorMod(dir, 4)) {
            case 0 -> leftPress;
            case 1 -> downPress;
            case 2 -> upPress;
            default -> rightPress;
        };
    }

    private BufferedImage getGlow(int dir) {
        return switch (Math.floorMod(dir, 4)) {
            case 0 -> leftGlow;
            case 1 -> downGlow;
            case 2 -> upGlow;
            default -> rightGlow;
        };
    }
}
