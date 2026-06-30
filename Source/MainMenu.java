package Source;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.URISyntaxException;

public class MainMenu extends JPanel implements KeyListener, Runnable {
    private JFrame frame;
    private String state = "MAIN";
    private int selected = 0;

    public static boolean botPlay = false;
    public static boolean numberPopups = true;
    public static boolean commaOnThirdDigits = true;
    public static boolean renderMode = false;
    public static int targetFPS = 60;

    public static int extraKeysCount = 4;
    public static boolean infiniteKeys = false;

    public static boolean removeOverlappedNotes = false;
    public static double overlapThresholdMs = 10.0;
    public static int maxNotesShown = 0;
    public static boolean batchedRenderer = true;
    public static boolean universalParser = true;

    private static final int[] KEY_PRESETS = {
            1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20,
            21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32, 40, 50, 60, 70, 80, 90,
            100, 105, 200, 300, 400, 500, 600, 700, 800, 900, 1000, 2000, 3000, 4000,
            5000, 6000, 7000, 8000, 9000, 9999
    };
    private static int extraKeysPresetIndex = 3; // default = 4

    private final int[] bindCodes = {
            KeyEvent.VK_D,
            KeyEvent.VK_F,
            KeyEvent.VK_J,
            KeyEvent.VK_K
    };
    private final String[] bindLabels = {"Left", "Down", "Up", "Right"};

    private final String[] mainMenu = {"Freeplay", "Options"};
    private final String[] optionsMenu = {"Gameplay", "Optimizations", "Controls", "Extra Keys"};
    private final String[] gameplayMenu = {"BotPlay"};
    private final String[] optimizationsMenu = {
            "Enable Number Popups",
            "Enable Comma On Third Digits",
            "Remove Overlapped Notes",
            "Overlapped Threshold (ms)",
            "Max Notes Shown",
            "Game Rendering (FFMPEG)",
            "Batched Note Renderer",
            "Universal Chart Parser"
    };
    private final String[] extraKeysMenu = {
            "Key Count",
            "Back"
    };

    private int fps = 0;
    private int frames = 0;
    private long lastTime = System.nanoTime();
    private Font fnfFont;
    private Font hudFont;
    private BufferedImage bgMain;
    private BufferedImage bgOptions;
    private BufferedImage imgFreeplay;
    private BufferedImage imgOptions;

    public MainMenu() {
        loadFont();
        loadImages();
        applyKeyPreset(extraKeysPresetIndex);

        setPreferredSize(new Dimension(1280, 720));
        setFocusable(true);
        addKeyListener(this);

        frame = new JFrame("Main Menu");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setResizable(false);
        frame.add(this);
        frame.pack();
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
        requestFocusInWindow();

        new Thread(this).start();
    }

    private void applyKeyPreset(int presetIndex) {
        if (presetIndex < 0) presetIndex = KEY_PRESETS.length;
        if (presetIndex > KEY_PRESETS.length) presetIndex = 0;

        extraKeysPresetIndex = presetIndex;

        if (presetIndex == KEY_PRESETS.length) {
            infiniteKeys = true;
            extraKeysCount = KEY_PRESETS[KEY_PRESETS.length - 1];
        } else {
            infiniteKeys = false;
            extraKeysCount = KEY_PRESETS[presetIndex];
        }
    }

    private void cycleExtraKeys(int delta) {
        int current = infiniteKeys ? KEY_PRESETS.length : extraKeysPresetIndex;
        current += delta;

        if (current < 0) current = KEY_PRESETS.length;
        if (current > KEY_PRESETS.length) current = 0;

        applyKeyPreset(current);
    }

    private void adjustOverlapThreshold(double delta) {
        overlapThresholdMs = Math.max(0.0, Math.min(1000.0, overlapThresholdMs + delta));
    }

    private void adjustMaxNotesShown(int delta) {
        maxNotesShown = Math.max(0, Math.min(99999, maxNotesShown + delta));
    }

    private InputStream openAssetStream(String relativePath) throws IOException, URISyntaxException {
        String clean = relativePath.replace("\\", "/");

        // 1) Inside the JAR / classpath
        InputStream in = MainMenu.class.getResourceAsStream("/Assets/" + clean);
        if (in != null) return in;

        // 2) Next to the JAR in an Assets folder
        File codeSource = new File(MainMenu.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        File baseDir = codeSource.isFile() ? codeSource.getParentFile() : codeSource;
        File external = new File(baseDir, "Assets/" + clean);
        if (external.exists()) return new FileInputStream(external);

        // 3) Current working directory
        File cwd = new File("Assets/" + clean);
        if (cwd.exists()) return new FileInputStream(cwd);

        return null;
    }

    private void loadFont() {
        try (InputStream in = openAssetStream("Fonts/FridayFunkin-Regular.ttf")) {
            if (in != null) {
                Font base = Font.createFont(Font.TRUETYPE_FONT, in);
                fnfFont = base.deriveFont(48f);
            } else {
                fnfFont = new Font("Arial", Font.BOLD, 48);
            }
        } catch (Exception e) {
            fnfFont = new Font("Arial", Font.BOLD, 48);
        }

        hudFont = new Font("Arial", Font.PLAIN, 18);
    }

    private BufferedImage loadImg(String relativePath) {
        try (InputStream in = openAssetStream("Images/" + relativePath)) {
            if (in != null) {
                return ImageIO.read(in);
            }
        } catch (Exception e) {
            System.out.println("Failed loading: " + relativePath);
        }

        System.out.println("Failed loading: " + relativePath);
        return null;
    }

    private void loadImages() {
        bgMain = loadImg("menuBG.png");
        bgOptions = loadImg("menuBGMagenta.png");
        imgFreeplay = loadImg("Freeplay.png");
        imgOptions = loadImg("Option.png");
    }

    @Override
    public void run() {
        while (true) {
            repaint();
            frames++;

            long now = System.nanoTime();
            if (now - lastTime >= 1_000_000_000L) {
                fps = frames;
                frames = 0;
                lastTime = now;
            }

            try {
                Thread.sleep(16);
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);

        Graphics2D g2 = (Graphics2D) g;
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);

        if (state.equals("MAIN")) {
            drawBG(g2, bgMain);
            drawMainMenu(g2);
        } else {
            drawBG(g2, bgOptions);
            drawOptionsMenu(g2);
        }

        g2.setFont(hudFont);
        g2.setColor(Color.WHITE);
        g2.drawString("FPS: " + fps, 20, 30);
    }

    private void drawBG(Graphics2D g2, BufferedImage img) {
        if (img != null) {
            g2.drawImage(img, 0, 0, 1280, 720, null);
        } else {
            g2.setColor(Color.BLACK);
            g2.fillRect(0, 0, 1280, 720);
        }
    }

    private void drawMainMenu(Graphics2D g2) {
        int w = 420;
        int h = 140;
        int x = (1280 / 2) - (w / 2);
        int startY = 260;
        int spacing = 170;

        int freeY = startY;
        int optY = startY + spacing;

        drawImage(g2, imgFreeplay, x, freeY, w, h);
        drawImage(g2, imgOptions, x, optY, w, h);

        if (selected == 0) drawSelectBox(g2, x, freeY, w, h);
        if (selected == 1) drawSelectBox(g2, x, optY, w, h);
    }

    private void drawImage(Graphics2D g2, BufferedImage img, int x, int y, int w, int h) {
        if (img != null) {
            g2.drawImage(img, x, y, w, h, null);
        } else {
            g2.setColor(Color.DARK_GRAY);
            g2.fillRoundRect(x, y, w, h, 20, 20);
        }
    }

    private void drawSelectBox(Graphics2D g2, int x, int y, int w, int h) {
        g2.setColor(new Color(0, 255, 0));
        g2.setStroke(new BasicStroke(5f));
        g2.drawRect(x - 8, y - 8, w + 16, h + 16);
    }

    private void drawOptionsMenu(Graphics2D g2) {
        String[] menu = getMenu();
        if (menu == null) return;

        g2.setFont(fnfFont);

        for (int i = 0; i < menu.length; i++) {
            g2.setColor(i == selected ? Color.YELLOW : Color.WHITE);
            String text = menu[i];

            if (state.equals("GAMEPLAY")) {
                text = "BotPlay: " + (botPlay ? "ON" : "OFF");
            } else if (state.equals("OPTIMIZATIONS")) {
                if (i == 0) {
                    text = "Enable Number Popups: " + (numberPopups ? "ON" : "OFF");
                } else if (i == 1) {
                    text = "Enable Comma On Third Digits: " + (commaOnThirdDigits ? "ON" : "OFF");
                } else if (i == 2) {
                    text = "Remove Overlapped Notes: " + (removeOverlappedNotes ? "ON" : "OFF");
                } else if (i == 3) {
                    text = "Overlapped Threshold (ms): " + String.format(java.util.Locale.US, "%.1f", overlapThresholdMs);
                } else if (i == 4) {
                    text = "Max Notes Shown: " + (maxNotesShown == 0 ? "INF" : String.valueOf(maxNotesShown));
                } else if (i == 5) {
                    text = "Game Rendering (FFMPEG): " + (renderMode ? "ON" : "OFF");
                } else if (i == 6) {
                    text = "Batched Note Renderer: " + (batchedRenderer ? "ON" : "OFF");
                } else if (i == 7) {
                    text = "Universal Chart Parser: " + (universalParser ? "ON" : "OFF");
                }
            } else if (state.equals("CONTROLS")) {
                text = bindLabels[i] + ": " + KeyEvent.getKeyText(bindCodes[i]);
            } else if (state.equals("EXTRA_KEYS")) {
                if (i == 0) {
                    text = "Key Count: " + (infiniteKeys ? "INF" : String.valueOf(extraKeysCount));
                } else if (i == 1) {
                    text = "Back";
                }
            }

            g2.drawString(text, 320, 260 + (i * 80));
        }
    }

    private String[] getMenu() {
        return switch (state) {
            case "MAIN" -> mainMenu;
            case "OPTIONS" -> optionsMenu;
            case "GAMEPLAY" -> gameplayMenu;
            case "OPTIMIZATIONS" -> optimizationsMenu;
            case "CONTROLS" -> bindLabels;
            case "EXTRA_KEYS" -> extraKeysMenu;
            default -> null;
        };
    }

    @Override
    public void keyPressed(KeyEvent e) {
        int key = e.getKeyCode();
        String[] menu = getMenu();

        if (key == KeyEvent.VK_DOWN) selected++;
        if (key == KeyEvent.VK_UP) selected--;

        if (state.equals("MAIN")) {
            if (selected < 0) selected = mainMenu.length - 1;
            if (selected >= mainMenu.length) selected = 0;
        } else if (menu != null) {
            if (selected < 0) selected = menu.length - 1;
            if (selected >= menu.length) selected = 0;
        }

        if (state.equals("EXTRA_KEYS")) {
            if (selected == 0) {
                if (key == KeyEvent.VK_LEFT) cycleExtraKeys(-1);
                if (key == KeyEvent.VK_RIGHT) cycleExtraKeys(1);
                if (key == KeyEvent.VK_ENTER) cycleExtraKeys(1);
            }

            if (key == KeyEvent.VK_ENTER && selected == 1) {
                state = "OPTIONS";
                selected = 0;
            }

            if (key == KeyEvent.VK_BACK_SPACE) {
                state = "OPTIONS";
                selected = 0;
            }

            repaint();
            return;
        }

        if (state.equals("OPTIMIZATIONS")) {
            if (selected == 3) {
                if (key == KeyEvent.VK_LEFT) {
                    adjustOverlapThreshold(-1.0);
                    repaint();
                    return;
                }
                if (key == KeyEvent.VK_RIGHT) {
                    adjustOverlapThreshold(1.0);
                    repaint();
                    return;
                }
            } else if (selected == 4) {
                if (key == KeyEvent.VK_LEFT) {
                    adjustMaxNotesShown(-100);
                    repaint();
                    return;
                }
                if (key == KeyEvent.VK_RIGHT) {
                    adjustMaxNotesShown(100);
                    repaint();
                    return;
                }
            }
        }

        if (key == KeyEvent.VK_ENTER) {
            switch (state) {
                case "MAIN" -> {
                    if (selected == 0) {
                        frame.dispose();
                        new FreeplayState();
                    } else {
                        state = "OPTIONS";
                        selected = 0;
                    }
                }
                case "OPTIONS" -> {
                    if (selected == 0) {
                        state = "GAMEPLAY";
                        selected = 0;
                    } else if (selected == 1) {
                        state = "OPTIMIZATIONS";
                        selected = 0;
                    } else if (selected == 2) {
                        state = "CONTROLS";
                        selected = 0;
                    } else if (selected == 3) {
                        state = "EXTRA_KEYS";
                        selected = 0;
                    }
                }
                case "GAMEPLAY" -> {
                    if (selected == 0) {
                        botPlay = !botPlay;
                    }
                }
                case "OPTIMIZATIONS" -> {
                    if (selected == 0) {
                        numberPopups = !numberPopups;
                    } else if (selected == 1) {
                        commaOnThirdDigits = !commaOnThirdDigits;
                    } else if (selected == 2) {
                        removeOverlappedNotes = !removeOverlappedNotes;
                    } else if (selected == 5) {
                        renderMode = !renderMode;
                    } else if (selected == 6) {
                        batchedRenderer = !batchedRenderer;
                    } else if (selected == 7) {
                        universalParser = !universalParser;
                    }
                }
                case "CONTROLS" -> {
                    if (selected >= 0 && selected < bindCodes.length) {
                        bindCodes[selected] =
                                (bindCodes[selected] == KeyEvent.VK_SPACE)
                                        ? KeyEvent.VK_D
                                        : KeyEvent.VK_SPACE;
                    }
                }
            }
        }

        if (key == KeyEvent.VK_BACK_SPACE) {
            if (state.equals("OPTIONS")) state = "MAIN";
            else if (state.equals("GAMEPLAY") || state.equals("OPTIMIZATIONS")
                    || state.equals("CONTROLS") || state.equals("EXTRA_KEYS")) {
                state = "OPTIONS";
            }
            selected = 0;
        }

        repaint();
    }

    @Override
    public void keyReleased(KeyEvent e) {}

    @Override
    public void keyTyped(KeyEvent e) {}
}