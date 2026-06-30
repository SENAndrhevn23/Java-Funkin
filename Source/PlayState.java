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
import java.lang.reflect.Constructor;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import Source.ChartEditor;

public class PlayState extends JPanel implements KeyListener {
    private static final int SCREEN_W = 1280;
    private static final int SCREEN_H = 720;
    private static final int NPS_WINDOW_MS = 1000;
    private static final int STREAM_KEEP_BEHIND_MS = 5000;
    private static final int MISS_DRAW_PADDING = 120;
    private static final int HIT_Y = 100;
    private static final int FINAL_RENDER_PADDING_MS = 5000;
    private static final int MAX_BUFFERED_NOTES = 2_000_000;
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

    private static final class LaneStream {
        private static final class Chunk {
            final double[] times = new double[CHUNK_SIZE];
            final float[] sustains = new float[CHUNK_SIZE];
            int size = 0;
            double firstTime = Double.POSITIVE_INFINITY;
            double lastTime = Double.NEGATIVE_INFINITY;
        }

        private final ArrayList<Chunk> chunks = new ArrayList<>();
        private final ArrayList<Integer> chunkStarts = new ArrayList<>();
        private int totalSize = 0;
        private int baseIndex = 0;
        private int headChunk = 0;
        private int hitCursor = 0;
        private int npsStart = 0;
        private int npsEnd = 0;

        synchronized void add(double time, float sustain) {
            if (chunks.isEmpty() || chunks.get(chunks.size() - 1).size >= CHUNK_SIZE) {
                chunks.add(new Chunk());
                chunkStarts.add(totalSize);
            }

            Chunk c = chunks.get(chunks.size() - 1);
            int idx = c.size++;
            c.times[idx] = time;
            c.sustains[idx] = sustain;
            if (time < c.firstTime) c.firstTime = time;
            if (time > c.lastTime) c.lastTime = time;
            totalSize++;
        }

        synchronized int size() {
            return totalSize - baseIndex;
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

        private int findChunkByAbsoluteIndex(int absIndex) {
            int lo = headChunk;
            int hi = chunks.size() - 1;
            while (lo <= hi) {
                int mid = (lo + hi) >>> 1;
                int start = chunkStarts.get(mid);
                Chunk c = chunks.get(mid);
                int end = start + c.size;
                if (absIndex < start) hi = mid - 1;
                else if (absIndex >= end) lo = mid + 1;
                else return mid;
            }
            return -1;
        }

        synchronized double timeAt(int liveIndex) {
            int absIndex = liveIndex + baseIndex;
            if (absIndex < baseIndex || absIndex >= totalSize) {
                throw new IndexOutOfBoundsException("Index: " + liveIndex + " size: " + size());
            }
            int chunkIndex = findChunkByAbsoluteIndex(absIndex);
            if (chunkIndex < 0) throw new IndexOutOfBoundsException("Index mapping failed");
            int start = chunkStarts.get(chunkIndex);
            return chunks.get(chunkIndex).times[absIndex - start];
        }

        private int localLowerBound(double[] arr, int size, double value, boolean upper) {
            int lo = 0, hi = size;
            while (lo < hi) {
                int mid = (lo + hi) >>> 1;
                double t = arr[mid];
                if (t < value || (upper && t <= value)) lo = mid + 1;
                else hi = mid;
            }
            return lo;
        }

        private int bound(double value, boolean upper) {
            int liveSize = size();
            if (liveSize <= 0) return 0;

            int lo = headChunk;
            int hi = chunks.size();
            while (lo < hi) {
                int mid = (lo + hi) >>> 1;
                Chunk c = chunks.get(mid);
                if (c.lastTime < value) lo = mid + 1;
                else hi = mid;
            }
            if (lo >= chunks.size()) return liveSize;

            Chunk c = chunks.get(lo);
            int startAbs = chunkStarts.get(lo);
            int local = localLowerBound(c.times, c.size, value, upper);
            return Math.max(0, (startAbs + local) - baseIndex);
        }

        synchronized int lowerBound(double value) { return bound(value, false); }
        synchronized int upperBound(double value) { return bound(value, true); }
        synchronized int countInWindow(double startInclusive, double endInclusive) {
            return Math.max(0, upperBound(endInclusive) - lowerBound(startInclusive));
        }

        synchronized int compactBefore(double minTime) {
            int removed = 0;
            while (headChunk < chunks.size()) {
                Chunk c = chunks.get(headChunk);
                if (c.size <= 0) {
                    headChunk++;
                    continue;
                }
                if (c.lastTime < minTime) {
                    removed += c.size;
                    headChunk++;
                } else {
                    break;
                }
            }

            if (removed == 0) return 0;

            baseIndex = Math.min(totalSize, baseIndex + removed);
            hitCursor = Math.max(0, hitCursor - removed);
            npsStart = Math.max(0, npsStart - removed);
            npsEnd = Math.max(0, npsEnd - removed);

            if (headChunk > 128 && headChunk > chunks.size() / 2) {
                rebuildAfterTrim();
            }
            return removed;
        }

        private void rebuildAfterTrim() {
            ArrayList<Chunk> newChunks = new ArrayList<>(chunks.size() - headChunk);
            ArrayList<Integer> newStarts = new ArrayList<>(chunks.size() - headChunk);
            int abs = 0;
            for (int i = headChunk; i < chunks.size(); i++) {
                Chunk c = chunks.get(i);
                newStarts.add(abs);
                newChunks.add(c);
                abs += c.size;
            }
            chunks.clear();
            chunks.addAll(newChunks);
            chunkStarts.clear();
            chunkStarts.addAll(newStarts);
            totalSize = abs;
            baseIndex = 0;
            headChunk = 0;
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

    public PlayState(String songName) { this(songName, MainMenu.botPlay); }

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
        setDoubleBuffered(true);

        // Listen on the panel too, but also install a global binding so 7 always works.
        setFocusable(true);
        setFocusTraversalKeysEnabled(false);
        addKeyListener(this);
        frame.addKeyListener(this);

        frame.setVisible(true);
        installGlobalKeyBindings();
        SwingUtilities.invokeLater(() -> {
            requestFocusInWindow();
            frame.requestFocusInWindow();
        });

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
        if (needed <= laneCount || !MainMenu.infiniteKeys) return;
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
        Path base = Paths.get("Assets", "Songs", songName);
        String[] candidates = {
                songName + ".mp3", "song.mp3", "Inst.mp3", "inst.mp3",
                songName + ".ogg", "song.ogg"
        };
        for (String c : candidates) {
            Path p = base.resolve(c);
            if (Files.exists(p)) return p.toAbsolutePath().toString();
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
            if (ffmpegProcess != null) ffmpegProcess.waitFor();
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
                try { Thread.sleep(sleep); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
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
                    } else break;
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
                        } else break;
                    }
                } else {
                    while (cursor < size) {
                        double noteTime = stream.timeAt(cursor);
                        if (songTime - noteTime > missWindowMs) {
                            playerCombo = 0;
                            sharedCombo = 0;
                            cursor++;
                        } else break;
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

    private long getSongTime() { return (long) songTimeMs; }
    private String formatNumber(long value) { return String.format(Locale.US, "%,d", value); }
    private String formatMemoryMB(long mb) { return mb + "MB"; }
    private String formatMemoryGB(long mb) { return String.format(Locale.US, "%.2fGB", mb / 1024.0); }

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
                    if (MainMenu.universalParser) {
                        ChartParserUniversal.ChartMeta meta = ChartParserUniversal.parseFile(chart, (time, lane, sustain, mustHit) -> {
                            addNote(time, lane, mustHit, sustain);
                        });
                        songSpeed = meta.speed;
                        System.out.println("[CHART] Loaded (Universal): " + chart.getFileName() + " (bpm " + meta.bpm + ")");
                    } else {
                        // Fallback to old simple parser
                        try (InputStream in = new BufferedInputStream(Files.newInputStream(chart), 1 << 20)) {
                            String json = new String(in.readAllBytes());
                            // Simple parse - only basic Psych format
                            if (json.contains("sectionNotes")) {
                                int idx = json.indexOf("sectionNotes");
                                // Very basic fallback - just load via universal anyway for safety
                                ChartParserUniversal.ChartMeta meta = ChartParserUniversal.parseFile(chart, (time, lane, sustain, mustHit) -> {
                                    addNote(time, lane, mustHit, sustain);
                                });
                                songSpeed = meta.speed;
                            }
                        }
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
                synchronized (bufferLock) { bufferLock.notifyAll(); }
            }
        }, "Chart-Parser");

        parserThread.setDaemon(true);
        parserThread.start();
    }


private List<Path> findChartFiles(String song) throws IOException {
    Path dir = Paths.get("Assets", "Songs", song);
    if (!Files.isDirectory(dir)) throw new FileNotFoundException("Song folder not found: " + dir.toAbsolutePath());

    List<Path> files = new ArrayList<>();
    Path songBase = dir.resolve(song + ".json");
    Path testBase = dir.resolve("test.json");
    String prefix = null;

    if (Files.exists(songBase)) {
        prefix = song;
        files.add(songBase);
    } else if (Files.exists(testBase)) {
        prefix = "test";
        files.add(testBase);
    }

    if (prefix != null) {
        Pattern splitPattern = Pattern.compile(Pattern.quote(prefix) + "-(\\d+)\\.json", Pattern.CASE_INSENSITIVE);
        try (var stream = Files.list(dir)) {
            stream.filter(p -> Files.isRegularFile(p) && p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".json"))
                    .forEach(p -> {
                        String name = p.getFileName().toString();
                        Matcher m = splitPattern.matcher(name);
                        if (m.matches()) files.add(p);
                    });
        }
    }

    files.sort(Comparator.comparingInt(p -> chartPartIndex(p.getFileName().toString(), song)));
    return files;
}

    private int chartPartIndex(String fileName, String song) {
        if (fileName.equalsIgnoreCase(song + ".json")) return 0;
        if (fileName.equalsIgnoreCase("test.json")) return 0;
        Pattern splitPatternSong = Pattern.compile(Pattern.quote(song) + "-(\\d+)\\.json", Pattern.CASE_INSENSITIVE);
        Matcher songMatch = splitPatternSong.matcher(fileName);
        if (songMatch.matches()) {
            try { return Integer.parseInt(songMatch.group(1)); } catch (NumberFormatException ignored) { }
        }
        Pattern splitPatternTest = Pattern.compile(Pattern.quote("test") + "-(\\d+)\\.json", Pattern.CASE_INSENSITIVE);
        Matcher testMatch = splitPatternTest.matcher(fileName);
        if (testMatch.matches()) {
            try { return Integer.parseInt(testMatch.group(1)); } catch (NumberFormatException ignored) { }
        }
        return Integer.MAX_VALUE;
    }


    private void addNote(double time, int lane, boolean mustHit, double sustain) {
        if (lane < 0) lane = 0;
        if (MainMenu.infiniteKeys) ensureLaneCount(lane + 1);
        else {
            synchronized (laneLock) {
                if (lane >= laneCount) lane = Math.floorMod(lane, laneCount);
            }
        }

        lastNoteTimeMs = Math.max(lastNoteTimeMs, (long) (time + sustain));

        synchronized (bufferLock) {
            while (bufferedNotes >= MAX_BUFFERED_NOTES && running) {
                try { bufferLock.wait(5); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
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
        Font statFont = new Font("Monospaced", Font.BOLD, 18);
        g2.setFont(statFont);
        FontMetrics fm = g2.getFontMetrics(statFont);
        int paddingX = 14;
        int paddingY = 10;
        int boxW = fm.stringWidth(text) + (paddingX * 2);
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
        Font statFont = new Font("Monospaced", Font.BOLD, 18);
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

    private void drawLaneNotes(Graphics2D g2, boolean isPlayer, double baseX, long songTime, double finalSpeed, Layout layout) {
        LaneStream[] laneArr;
        int[] dirs;
        int count;
        synchronized (laneLock) {
            laneArr = isPlayer ? playerLanes : opponentLanes;
            dirs = laneDirections;
            count = laneCount;
        }

        if (!MainMenu.batchedRenderer) {
            // ORIGINAL per-note rendering
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
            return;
        }

        // BATCHED RENDERING FOR 1M+ NOTES
        final int DENSE_THRESHOLD = 5000;
        final int GROUP_TOL = 3;

        for (int lane = 0; lane < count; lane++) {
            LaneStream stream = laneArr[lane];
            BufferedImage img = getComing(dirs[lane]);
            double x = baseX + lane * layout.spacing;
            
            int start = Math.max(stream.hitCursor(), stream.lowerBound(songTime - 500));
            int end = stream.lowerBound(songTime + 2000);
            int visible = end - start;
            if (visible <= 0) continue;
            
            // Ultra dense mode: draw single bar
            if (visible > DENSE_THRESHOLD) {
                g2.setColor(new Color(100, 180, 255, 90));
                double topY = HIT_Y + (stream.timeAt(start) - songTime) * finalSpeed;
                double botY = HIT_Y + (stream.timeAt(end-1) - songTime) * finalSpeed;
                g2.fillRect((int)x, (int)topY, (int)layout.noteSize, (int)Math.max(2, botY - topY));
                g2.setColor(new Color(255,255,255,30));
                for (int i = start; i < end; i += Math.max(1, visible/100)) {
                    double y = HIT_Y + (stream.timeAt(i) - songTime) * finalSpeed;
                    g2.fillRect((int)x, (int)y, (int)layout.noteSize, 2);
                }
                continue;
            }
            
            // Group close notes
            double lastY = Double.NEGATIVE_INFINITY;
            double groupStart = 0;
            int groupCount = 0;
            
            for (int i = start; i < end; i++) {
                double time = stream.timeAt(i);
                double y = HIT_Y + (time - songTime) * finalSpeed;
                if (y < -120 || y > 840) continue;
                
                if (Math.abs(y - lastY) <= GROUP_TOL && groupCount > 0) {
                    groupCount++;
                } else {
                    if (groupCount > 0) {
                        double h = lastY - groupStart + layout.noteSize;
                        if (h <= layout.noteSize * 1.5) {
                            drawImageScaled(g2, img, x, groupStart, layout.noteSize, layout.noteSize);
                        } else {
                            g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.85f));
                            drawImageScaled(g2, img, x, groupStart, layout.noteSize, layout.noteSize);
                            drawImageScaled(g2, img, x, groupStart + h - layout.noteSize, layout.noteSize, layout.noteSize);
                            g2.setColor(new Color(80, 150, 255, 180));
                            g2.fillRect((int)x + 4, (int)(groupStart + layout.noteSize), (int)layout.noteSize - 8, (int)(h - layout.noteSize*2));
                            g2.setComposite(AlphaComposite.SrcOver);
                        }
                    }
                    groupStart = y;
                    groupCount = 1;
                }
                lastY = y;
            }
            if (groupCount > 0) {
                double h = lastY - groupStart + layout.noteSize;
                if (h <= layout.noteSize * 1.5) {
                    drawImageScaled(g2, img, x, groupStart, layout.noteSize, layout.noteSize);
                } else {
                    g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.85f));
                    drawImageScaled(g2, img, x, groupStart, layout.noteSize, layout.noteSize);
                    drawImageScaled(g2, img, x, groupStart + h - layout.noteSize, layout.noteSize, layout.noteSize);
                    g2.setColor(new Color(80, 150, 255, 180));
                    g2.fillRect((int)x + 4, (int)(groupStart + layout.noteSize), (int)layout.noteSize - 8, (int)(h - layout.noteSize*2));
                    g2.setComposite(AlphaComposite.SrcOver);
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
        if (stageBG != null) g2.drawImage(stageBG, 0, 0, SCREEN_W, SCREEN_H, null);
        else {
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
            synchronized (renderLock) { g2.drawImage(offscreenImage, 0, 0, null); }
        } else {
            drawScene(g2);
        }
        g2.dispose();
    }

    private void installGlobalKeyBindings() {
        JRootPane root = frame.getRootPane();
        InputMap inputMap = root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
        ActionMap actionMap = root.getActionMap();

        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_7, 0), "openChartEditor");
        actionMap.put("openChartEditor", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                openChartEditor();
            }
        });

        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "backToMenu");
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_BACK_SPACE, 0), "backToMenu");
        actionMap.put("backToMenu", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                running = false;
                if (frame != null) frame.dispose();
                SwingUtilities.invokeLater(MainMenu::new);
            }
        });
    }

    @Override
    public void keyPressed(KeyEvent e) {
        if (e.getKeyCode() == KeyEvent.VK_7) {
            openChartEditor();
            return;
        }

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

private void openChartEditor() {
    System.out.println("[PlayState] Opening ChartEditor");

    running = false;

    if (frame != null)
        frame.dispose();

    // Direct launch — NO reflection
    SwingUtilities.invokeLater(() ->
        new ChartEditor(songName, botPlay)
    );
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

    @Override public void keyTyped(KeyEvent e) { }

    private void loadImages() {
        try {
            stageBG = readImageAny(
                    "/home/andre/FNF-JAVA-ENGINE/Assets/Images/Stage.jpg",
                    "Assets/Images/Stage.jpg"
            );

            leftArrow = readImageAny("Notes/LeftArrow.png");
            downArrow = readImageAny("Notes/DownArrow.png");
            upArrow = readImageAny("Notes/UpArrow.png");
            rightArrow = readImageAny("Notes/RightArrow.png");

            leftComing = readImageAny("Notes/LeftComing.png");
            downComing = readImageAny("Notes/DownComing.png");
            upComing = readImageAny("Notes/UpComing.png");
            rightComing = readImageAny("Notes/RightComing.png");

            leftPress = readImageAny("Notes/LeftPress.png");
            downPress = readImageAny("Notes/DownPress.png");
            upPress = readImageAny("Notes/UpPress.png");
            rightPress = readImageAny("Notes/RightPress.png");

            leftGlow = readImageAny("Notes/LeftGlow.png");
            downGlow = readImageAny("Notes/DownGlow.png");
            upGlow = readImageAny("Notes/UpGlow.png");
            rightGlow = readImageAny("Notes/RightGlow.png");

            for (int i = 0; i < 10; i++) {
                numberDigits[i] = readImageAny(
                        "/home/andre/FNF-JAVA-ENGINE/Assets/Numbers/num" + i + ".png",
                        "Assets/Numbers/num" + i + ".png"
                );
            }
            numberComma = readImageAny(
                    "/home/andre/FNF-JAVA-ENGINE/Assets/Numbers/numComma.png",
                    "Assets/Numbers/numComma.png"
            );
        } catch (Exception e) {
            System.err.println("IMAGE FOLDER ERROR.");
            e.printStackTrace();
        }
    }

    private BufferedImage readImageAny(String... paths) throws IOException {
        for (String p : paths) {
            File f = new File(p);
            if (f.exists()) return ImageIO.read(f);
        }
        return null;
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