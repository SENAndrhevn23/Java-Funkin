package Source;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ChartEditor extends JPanel implements MouseListener, MouseMotionListener, KeyListener, ChangeListener, ActionListener, MouseWheelListener {
    private static final int W = 1280;
    private static final int H = 720;

    private static final int DEFAULT_LANES = 8;
    private static final int ROWS = 16;
    private static final int GRID_X = 60;
    private static final int GRID_Y = 90;
    private static final int GRID_W = 1160;
    private static final int GRID_H = 520;

    private static final Color BG = new Color(14, 14, 18);
    private static final Color GRID_BG = new Color(24, 24, 30);
    private static final Color GRID_LINE = new Color(80, 80, 92);
    private static final Color GRID_MINOR = new Color(46, 46, 56);
    private static final Color TEXT = new Color(240, 240, 245);
    private static final Color SELECT = new Color(255, 210, 90, 100);
    private static final Color PLAYER_TINT = new Color(80, 130, 255, 26);
    private static final Color OPP_TINT = new Color(255, 120, 120, 18);
    private static final Color NOTE_BG = new Color(255, 255, 255, 38);

    private final String defaultSongName;
    private final boolean botPlay;

    private JFrame frame;

    private final List<NoteData> notes = new ArrayList<>();
    private final Map<String, BufferedImage> imageCache = new HashMap<>();

    private final JTabbedPane tabs = new JTabbedPane();
    private final EditorPanel editorPanel = new EditorPanel();

    private final JButton btnLoad = new JButton("Load");
    private final JButton btnSave = new JButton("Save");
    private final JButton btnPrevSection = new JButton("Prev Section");
    private final JButton btnNextSection = new JButton("Next Section");
    private final JButton btnDelete = new JButton("Delete Note");
    private final JButton btnClearAll = new JButton("Clear All Notes");
    private final JButton btnSpam = new JButton("Spam Side");
    private final JButton btnReturn = new JButton("Return");

    private final JCheckBox chkShowPlayer = new JCheckBox("Show player side", true);
    private final JCheckBox chkShowNumbers = new JCheckBox("Show grid numbers", true);
    private final JCheckBox chkSpamMode = new JCheckBox("Spam mode", false);

    private final JSlider densitySlider = new JSlider(1, 32, 4);
    private final JSlider lengthSlider = new JSlider(1, 64, 16);
    private final JSpinner maniaSpinner = new JSpinner(new SpinnerNumberModel(DEFAULT_LANES, 1, 9999, 1));

    private final JTextField songField = new JTextField(14);
    private final JTextField bpmField = new JTextField("120", 6);
    private final JTextField speedField = new JTextField("1.0", 6);

    private int currentSection = 0;
    private int selectedLane = 0;
    private int selectedRow = 0;
    private boolean showingPlayerSide = true;
    private boolean showGridNumbers = true;
    private boolean spamMode = false;
    private int totalLanes = DEFAULT_LANES;
    private int maniaKeys = DEFAULT_LANES;

    private double bpm = 120.0;
    private double speed = 1.0;

    private final javax.swing.Timer repaintTimer;

    private final BufferedImage[] leftComing = new BufferedImage[4];
    private final BufferedImage[] rightComing = new BufferedImage[4];
    private final BufferedImage[] leftArrow = new BufferedImage[4];
    private final BufferedImage[] rightArrow = new BufferedImage[4];
    private final BufferedImage[] leftGlow = new BufferedImage[4];
    private final BufferedImage[] rightGlow = new BufferedImage[4];
    private final BufferedImage[] leftPress = new BufferedImage[4];
    private final BufferedImage[] rightPress = new BufferedImage[4];

    private final SpamPreviewPanel spamPreview = new SpamPreviewPanel();

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new ChartEditor("Test", false));
    }

    public ChartEditor(String songName, boolean botPlay) {
        this.defaultSongName = (songName == null || songName.isBlank()) ? "Test" : songName;
        this.botPlay = botPlay;

        loadImages();
        buildUi();
        applyMania(DEFAULT_LANES);
        loadChart();

        repaintTimer = new javax.swing.Timer(16, e -> editorPanel.repaint());
        repaintTimer.start();
    }

    private void buildUi() {
        setLayout(new BorderLayout());
        setBackground(BG);
        setFocusable(true);
        addKeyListener(this);

        songField.setText(defaultSongName);

        JPanel topBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 6));
        topBar.add(btnLoad);
        topBar.add(btnSave);
        topBar.add(btnPrevSection);
        topBar.add(btnNextSection);
        topBar.add(btnDelete);
        topBar.add(btnClearAll);
        topBar.add(btnSpam);
        topBar.add(btnReturn);

        btnLoad.addActionListener(this);
        btnSave.addActionListener(this);
        btnPrevSection.addActionListener(this);
        btnNextSection.addActionListener(this);
        btnDelete.addActionListener(this);
        btnClearAll.addActionListener(this);
        btnSpam.addActionListener(this);
        btnReturn.addActionListener(this);

        JPanel songBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 6));
        songBar.add(new JLabel("Song:"));
        songBar.add(songField);
        songBar.add(new JLabel("BPM:"));
        songBar.add(bpmField);
        songBar.add(new JLabel("Speed:"));
        songBar.add(speedField);

        JPanel chartTab = new JPanel(new BorderLayout());
        chartTab.add(topBar, BorderLayout.NORTH);
        chartTab.add(editorPanel, BorderLayout.CENTER);
        chartTab.add(songBar, BorderLayout.SOUTH);

        JPanel toolsTab = new JPanel();
        toolsTab.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        toolsTab.setLayout(new BoxLayout(toolsTab, BoxLayout.Y_AXIS));
        toolsTab.add(makeSliderRow("Note density", densitySlider, 1, 32));
        toolsTab.add(Box.createVerticalStrut(10));
        toolsTab.add(makeSliderRow("Spam length", lengthSlider, 1, 64));
        toolsTab.add(Box.createVerticalStrut(10));
        toolsTab.add(chkSpamMode);
        toolsTab.add(Box.createVerticalStrut(10));
        toolsTab.add(spamPreview);
        toolsTab.add(Box.createVerticalStrut(10));
        toolsTab.add(chkShowPlayer);
        toolsTab.add(chkShowNumbers);
        toolsTab.add(Box.createVerticalStrut(10));
        toolsTab.add(new JLabel("Space = toggle note"));
        toolsTab.add(new JLabel("Delete = remove selected note"));
        toolsTab.add(new JLabel("Mouse wheel = change section"));
        toolsTab.add(new JLabel("Tab = switch tabs"));
        toolsTab.add(new JLabel("R = reload, S = save"));

        JPanel maniaTab = new JPanel();
        maniaTab.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        maniaTab.setLayout(new BoxLayout(maniaTab, BoxLayout.Y_AXIS));
        maniaTab.add(makeFieldRow("Key count (1k-9999k):", maniaSpinner));
        maniaTab.add(Box.createVerticalStrut(10));
        maniaTab.add(new JLabel("This controls the total lane count in the editor."));
        maniaTab.add(new JLabel("The grid still uses 16 rows per section."));

        JPanel infoTab = new JPanel(new BorderLayout());
        JTextArea info = new JTextArea(
                "The grid is split into opponent and player halves.\n" +
                "The grid is 16 rows tall.\n" +
                "Click a cell to place or remove a note.\n" +
                "Notes render using the arrow/coming images in the Notes folder.\n" +
                "Charts save to Assets/Songs/<song>/test.json."
        );
        info.setEditable(false);
        info.setLineWrap(true);
        info.setWrapStyleWord(true);
        info.setForeground(TEXT);
        info.setBackground(new Color(28, 28, 34));
        info.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        infoTab.add(info, BorderLayout.CENTER);

        tabs.addTab("Chart", chartTab);
        tabs.addTab("Tools", toolsTab);
        tabs.addTab("Mania", maniaTab);
        tabs.addTab("Info", infoTab);
        add(tabs, BorderLayout.CENTER);

        chkShowPlayer.addActionListener(this);
        chkShowNumbers.addActionListener(this);
        chkSpamMode.addActionListener(this);
        densitySlider.addChangeListener(this);
        lengthSlider.addChangeListener(this);
        maniaSpinner.addChangeListener(this);

        frame = new JFrame("Chart Editor - " + defaultSongName);
        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        frame.setContentPane(this);
        frame.setSize(W, H);
        frame.setLocationRelativeTo(null);
        frame.setResizable(true);
        frame.setVisible(true);
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                repaintTimer.stop();
            }
        });

        editorPanel.addMouseListener(this);
        editorPanel.addMouseMotionListener(this);
        editorPanel.addMouseWheelListener(this);
        editorPanel.addKeyListener(this);
        editorPanel.setFocusable(true);
        SwingUtilities.invokeLater(editorPanel::requestFocusInWindow);
    }

    private JPanel makeSliderRow(String label, JSlider slider, int min, int max) {
        JPanel row = new JPanel(new BorderLayout(8, 0));
        JLabel value = new JLabel(label + ": " + slider.getValue());
        slider.setMinimum(min);
        slider.setMaximum(max);
        slider.setPaintTicks(true);
        slider.setPaintLabels(true);
        slider.setMajorTickSpacing(Math.max(1, (max - min) / 4));
        slider.setMinorTickSpacing(1);
        slider.addChangeListener(e -> value.setText(label + ": " + slider.getValue()));
        row.add(value, BorderLayout.NORTH);
        row.add(slider, BorderLayout.CENTER);
        return row;
    }

    private JPanel makeFieldRow(String label, JComponent field) {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        row.add(new JLabel(label));
        row.add(field);
        return row;
    }

    private int clampManiaKeys(int value) {
        return Math.max(1, Math.min(9999, value));
    }

    private void applyMania(int keys) {
        maniaKeys = clampManiaKeys(keys);
        totalLanes = maniaKeys;
        if (selectedLane >= totalLanes) {
            selectedLane = Math.max(0, totalLanes - 1);
        }
    }

    private void clearAllNotes() {
        notes.clear();
        editorPanel.repaint();
    }

    private void loadImages() {
        loadImageSet(leftComing, new String[]{"LeftComing.png", "DownComing.png", "UpComing.png", "RightComing.png"});
        loadImageSet(rightComing, new String[]{"LeftComing.png", "DownComing.png", "UpComing.png", "RightComing.png"});
        loadImageSet(leftArrow, new String[]{"LeftArrow.png", "DownArrow.png", "UpArrow.png", "RightArrow.png"});
        loadImageSet(rightArrow, new String[]{"LeftArrow.png", "DownArrow.png", "UpArrow.png", "RightArrow.png"});
        loadImageSet(leftGlow, new String[]{"LeftGlow.png", "DownGlow.png", "UpGlow.png", "RightGlow.png"});
        loadImageSet(rightGlow, new String[]{"LeftGlow.png", "DownGlow.png", "UpGlow.png", "RightGlow.png"});
        loadImageSet(leftPress, new String[]{"LeftPress.png", "DownPress.png", "UpPress.png", "RightPress.png"});
        loadImageSet(rightPress, new String[]{"LeftPress.png", "DownPress.png", "UpPress.png", "RightPress.png"});

        for (int i = 0; i < 4; i++) {
            if (leftComing[i] == null) leftComing[i] = leftArrow[i];
            if (rightComing[i] == null) rightComing[i] = rightArrow[i];
        }
    }

    private void loadImageSet(BufferedImage[] target, String[] fileNames) {
        for (int i = 0; i < 4; i++) {
            target[i] = loadImage("Notes/" + fileNames[i]);
        }
    }

    private BufferedImage loadImage(String path) {
        if (imageCache.containsKey(path)) return imageCache.get(path);
        BufferedImage image = null;
        try {
            File file = new File(path);
            if (file.exists()) {
                image = ImageIO.read(file);
            }
        } catch (IOException ignored) {}
        imageCache.put(path, image);
        return image;
    }

    private String currentSongName() {
        String s = songField.getText();
        return (s == null || s.isBlank()) ? defaultSongName : s.trim();
    }


    private Path chartFile() {
        Path dir = Paths.get("Assets", "Songs", currentSongName());
        Path songJson = dir.resolve(currentSongName() + ".json");
        if (Files.exists(songJson)) return songJson;
        return dir.resolve("test.json");
    }

    private void loadChart() {
        notes.clear();
        try {
            Path file = chartFile();
            if (!Files.exists(file)) {
                currentSection = 0;
                return;
            }

            String raw = Files.readString(file);
            Object parsed = new MiniJsonParser(raw).parse();
            Map<String, Object> root = asObject(parsed);
            Map<String, Object> songObj = root;
            Object nestedSong = root.get("song");
            if (nestedSong instanceof Map<?, ?>) {
                songObj = asObject(nestedSong);
            }

            Object songNameValue = songObj.get("song");
            if (songNameValue != null) songField.setText(String.valueOf(songNameValue));

            if (songObj.containsKey("bpm")) bpm = asDouble(songObj.get("bpm"), bpm);
            if (songObj.containsKey("speed")) speed = asDouble(songObj.get("speed"), speed);
            if (songObj.containsKey("mania")) {
                applyMania(clampManiaKeys(asInt(songObj.get("mania"), maniaKeys)));
                maniaSpinner.setValue(maniaKeys);
            } else {
                maniaSpinner.setValue(maniaKeys);
            }

            bpmField.setText(String.valueOf(bpm));
            speedField.setText(String.valueOf(speed));

            List<Object> sections = asList(songObj.get("notes"));
            int sectionIndex = 0;
            for (Object secEl : sections) {
                if (!(secEl instanceof Map<?, ?>)) {
                    sectionIndex++;
                    continue;
                }
                Map<String, Object> secObj = asObject(secEl);
                List<Object> secNotes = asList(secObj.get("sectionNotes"));
                for (Object noteEl : secNotes) {
                    if (!(noteEl instanceof List<?>)) continue;
                    List<Object> arr = asList(noteEl);
                    if (arr.size() < 3) continue;

                    double time = asDouble(arr.get(0), 0.0);
                    int lane = asInt(arr.get(1), 0);
                    double sustain = asDouble(arr.get(2), 0.0);
                    boolean mustHit = lane >= Math.max(1, totalLanes / 2);
                    if (arr.size() >= 4 && arr.get(3) instanceof Boolean b) mustHit = b;

                    int row = rowFromTime(time);
                    notes.add(new NoteData(time, lane, row, sectionIndex, sustain, mustHit));
                }
                sectionIndex++;
            }

            notes.sort(Comparator.comparingDouble(n -> n.timeMs));
            currentSection = Math.max(0, Math.min(currentSection, Math.max(0, sectionIndex - 1)));
            editorPanel.repaint();
        } catch (Exception ex) {
            ex.printStackTrace();
            currentSection = 0;
        }
    }


private void saveChart() {
    syncFromUi();
    try {
        String name = currentSongName();
        Path folder = Paths.get("Assets", "Songs", name);
        Files.createDirectories(folder);

        int maxSection = Math.max(0, currentSection);
        for (NoteData n : notes) maxSection = Math.max(maxSection, n.sectionIndex);

        StringBuilder sb = new StringBuilder(16_384);
        sb.append("{\n");
        sb.append("  \"song\": {\n");
        sb.append("    \"song\": \"").append(jsonEscape(name)).append("\",\n");
        sb.append("    \"speed\": ").append(formatJsonNumber(speed)).append(",\n");
        sb.append("    \"bpm\": ").append(formatJsonNumber(bpm)).append(",\n");
        sb.append("    \"needsVoices\": false,\n");
        sb.append("    \"player1\": \"bf\",\n");
        sb.append("    \"player2\": \"dad\",\n");
        sb.append("    \"mania\": ").append(maniaKeys).append(",\n");
        sb.append("    \"notes\": [\n");

        for (int sectionIndex = 0; sectionIndex <= maxSection; sectionIndex++) {
            sb.append("      {\n");
            sb.append("        \"mustHitSection\": true,\n");
            sb.append("        \"changeBPM\": false,\n");
            sb.append("        \"bpm\": ").append(formatJsonNumber(bpm)).append(",\n");
            sb.append("        \"sectionNotes\": [");

            boolean firstNote = true;
            for (NoteData note : notes) {
                if (note.sectionIndex != sectionIndex) continue;
                if (firstNote) {
                    sb.append('\n');
                    firstNote = false;
                } else {
                    sb.append(",\n");
                }
                sb.append("          [")
                  .append(formatJsonNumber(note.timeMs)).append(", ")
                  .append(note.lane).append(", ")
                  .append(formatJsonNumber(note.sustainMs)).append(", ")
                  .append(note.mustHit)
                  .append("]");
            }

            if (!firstNote) sb.append('\n').append("        ");
            sb.append("]\n");
            sb.append("      }");
            if (sectionIndex < maxSection) sb.append(',');
            sb.append('\n');
        }

        sb.append("    ]\n");
        sb.append("  }\n");
        sb.append("}\n");

        Files.writeString(folder.resolve("test.json"), sb.toString());
        System.out.println("[ChartEditor] Saved to " + folder.resolve("test.json").toAbsolutePath());
    } catch (Exception ex) {
        ex.printStackTrace();
    }
}

private static Map<String, Object> asObject(Object value) {
    if (!(value instanceof Map<?, ?> map)) return new java.util.LinkedHashMap<>();
    Map<String, Object> out = new java.util.LinkedHashMap<>();
    for (Map.Entry<?, ?> entry : map.entrySet()) {
        out.put(String.valueOf(entry.getKey()), entry.getValue());
    }
    return out;
}

private static List<Object> asList(Object value) {
    if (!(value instanceof List<?> list)) return java.util.Collections.emptyList();
    return new ArrayList<>(list);
}

private static double asDouble(Object value, double fallback) {
    if (value instanceof Number n) return n.doubleValue();
    if (value instanceof String s) {
        try { return Double.parseDouble(s.trim()); } catch (Exception ignored) {}
    }
    return fallback;
}

private static int asInt(Object value, int fallback) {
    if (value instanceof Number n) return n.intValue();
    if (value instanceof String s) {
        try { return Integer.parseInt(s.trim()); } catch (Exception ignored) {}
    }
    return fallback;
}

private static String formatJsonNumber(double value) {
    if (Double.isNaN(value) || Double.isInfinite(value)) return "0";
    long asLong = (long) value;
    if (Math.abs(value - asLong) < 1e-9) return Long.toString(asLong);
    String s = String.format(java.util.Locale.US, "%.8f", value);
    while (s.contains(".") && (s.endsWith("0") || s.endsWith("."))) {
        s = s.substring(0, s.length() - 1);
    }
    return s;
}

private static String jsonEscape(String s) {
    if (s == null) return "";
    StringBuilder out = new StringBuilder(s.length() + 16);
    for (int i = 0; i < s.length(); i++) {
        char c = s.charAt(i);
        switch (c) {
            case '"' -> out.append("\\\"");
            case '\\' -> out.append("\\\\");
            case '\b' -> out.append("\\b");
            case '\f' -> out.append("\\f");
            case '\n' -> out.append("\\n");
            case '\r' -> out.append("\\r");
            case '\t' -> out.append("\\t");
            default -> {
                if (c < 32) out.append(String.format("\\u%04x", (int) c));
                else out.append(c);
            }
        }
    }
    return out.toString();
}

private final class MiniJsonParser {
    private final String src;
    private int pos = 0;

    MiniJsonParser(String src) {
        this.src = src == null ? "" : src;
    }

    Object parse() throws IOException {
        skipWs();
        Object value = parseValue();
        skipWs();
        return value;
    }

    private Object parseValue() throws IOException {
        skipWs();
        if (pos >= src.length()) return null;
        char c = src.charAt(pos);
        return switch (c) {
            case '{' -> parseObject();
            case '[' -> parseArray();
            case '"' -> parseString();
            case 't' -> { expectLiteral("true"); yield Boolean.TRUE; }
            case 'f' -> { expectLiteral("false"); yield Boolean.FALSE; }
            case 'n' -> { expectLiteral("null"); yield null; }
            default -> parseNumber();
        };
    }

    private Map<String, Object> parseObject() throws IOException {
        expect('{');
        Map<String, Object> map = new java.util.LinkedHashMap<>();
        skipWs();
        if (peek('}')) {
            pos++;
            return map;
        }
        while (pos < src.length()) {
            skipWs();
            String key = parseString();
            skipWs();
            expect(':');
            Object value = parseValue();
            map.put(key, value);
            skipWs();
            if (peek(',')) {
                pos++;
                continue;
            }
            if (peek('}')) {
                pos++;
                break;
            }
            throw new IOException("Invalid JSON object near index " + pos);
        }
        return map;
    }

    private List<Object> parseArray() throws IOException {
        expect('[');
        List<Object> list = new ArrayList<>();
        skipWs();
        if (peek(']')) {
            pos++;
            return list;
        }
        while (pos < src.length()) {
            list.add(parseValue());
            skipWs();
            if (peek(',')) {
                pos++;
                continue;
            }
            if (peek(']')) {
                pos++;
                break;
            }
            throw new IOException("Invalid JSON array near index " + pos);
        }
        return list;
    }

    private String parseString() throws IOException {
        expect('"');
        StringBuilder sb = new StringBuilder();
        while (pos < src.length()) {
            char c = src.charAt(pos++);
            if (c == '"') return sb.toString();
            if (c == '\\') {
                if (pos >= src.length()) throw new IOException("Bad escape sequence");
                char e = src.charAt(pos++);
                switch (e) {
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case '/' -> sb.append('/');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'u' -> {
                        if (pos + 4 > src.length()) throw new IOException("Bad unicode escape");
                        int code = Integer.parseInt(src.substring(pos, pos + 4), 16);
                        sb.append((char) code);
                        pos += 4;
                    }
                    default -> sb.append(e);
                }
            } else {
                sb.append(c);
            }
        }
        throw new IOException("Unterminated string");
    }

    private Number parseNumber() throws IOException {
        int start = pos;
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if ((c >= '0' && c <= '9') || c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E') {
                pos++;
            } else {
                break;
            }
        }
        if (start == pos) throw new IOException("Expected number near index " + pos);
        String token = src.substring(start, pos);
        try {
            if (token.contains(".") || token.contains("e") || token.contains("E")) return Double.parseDouble(token);
            long lv = Long.parseLong(token);
            if (lv >= Integer.MIN_VALUE && lv <= Integer.MAX_VALUE) return (int) lv;
            return lv;
        } catch (NumberFormatException ex) {
            throw new IOException("Bad number: " + token, ex);
        }
    }

    private void expect(char c) throws IOException {
        skipWs();
        if (pos >= src.length() || src.charAt(pos) != c) {
            throw new IOException("Expected '" + c + "' near index " + pos);
        }
        pos++;
    }

    private void expectLiteral(String lit) throws IOException {
        skipWs();
        if (!src.startsWith(lit, pos)) {
            throw new IOException("Expected " + lit + " near index " + pos);
        }
        pos += lit.length();
    }

    private boolean peek(char c) {
        return pos < src.length() && src.charAt(pos) == c;
    }

    private void skipWs() {
        while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) pos++;
    }
}

    private double stepMs() {
        return (60000.0 / Math.max(1.0, bpm)) / 4.0;
    }

    private int densityStepRows() {
        int density = Math.max(1, densitySlider.getValue());
        return Math.max(1, Math.round((float) ROWS / density));
    }

    private int rowFromTime(double timeMs) {
        double step = stepMs();
        long stepIndex = Math.round(timeMs / step);
        int row = (int) (Math.abs(stepIndex) % ROWS);
        return Math.max(0, Math.min(ROWS - 1, row));
    }

    private double timeFromSectionRow(int sectionIndex, int row) {
        return ((sectionIndex * ROWS) + row) * stepMs();
    }

    private int laneAt(int x) {
        int localX = x - GRID_X;
        if (localX < 0 || localX >= GRID_W) return -1;
        int laneW = GRID_W / totalLanes;
        return Math.min(totalLanes - 1, localX / laneW);
    }

    private int rowAt(int y) {
        int localY = y - GRID_Y;
        if (localY < 0 || localY >= GRID_H) return -1;
        int rowH = GRID_H / ROWS;
        return Math.min(ROWS - 1, localY / rowH);
    }

    private NoteData findNote(int sectionIndex, int lane, int row) {
        for (int i = notes.size() - 1; i >= 0; i--) {
            NoteData n = notes.get(i);
            if (n.sectionIndex == sectionIndex && n.lane == lane && n.row == row) {
                return n;
            }
        }
        return null;
    }

    private void toggleNote(int lane, int row) {
        syncFromUi();
        NoteData existing = findNote(currentSection, lane, row);
        if (existing != null) {
            notes.remove(existing);
        } else {
            addNote(lane, row, currentSection, 0.0);
        }
        editorPanel.repaint();
    }

    private void addNote(int lane, int row, int sectionIndex, double sustainMs) {
        double time = timeFromSectionRow(sectionIndex, row);
        boolean mustHit = lane >= Math.max(1, totalLanes / 2);
        NoteData existing = findNote(sectionIndex, lane, row);
        if (existing != null) {
            return;
        }
        notes.add(new NoteData(time, lane, row, sectionIndex, sustainMs, mustHit));
        notes.sort(Comparator.comparingDouble(n -> n.timeMs));
    }

    private void deleteSelected() {
        NoteData existing = findNote(currentSection, selectedLane, selectedRow);
        if (existing != null) {
            notes.remove(existing);
            editorPanel.repaint();
        }
    }

    private void spamFillSide() {
        syncFromUi();
        int split = Math.max(1, totalLanes / 2);
        int startLane = selectedLane < split ? 0 : split;
        int endLane = selectedLane < split ? split : totalLanes;
        int length = Math.max(1, lengthSlider.getValue());
        int stepRows = densityStepRows();

        for (int i = 0; i < length; i++) {
            int row = (selectedRow + (i * stepRows)) % ROWS;
            for (int lane = startLane; lane < endLane; lane++) {
                addNote(lane, row, currentSection, 0.0);
            }
        }
        editorPanel.repaint();
    }

    private void spamFromClick(int lane, int row) {
        syncFromUi();
        int length = Math.max(1, lengthSlider.getValue());
        int stepRows = densityStepRows();

        for (int i = 0; i < length; i++) {
            int r = (row + (i * stepRows)) % ROWS;
            addNote(lane, r, currentSection, 0.0);
        }
        editorPanel.repaint();
    }

    private BufferedImage laneImage(int lane, boolean coming) {
        int dir = Math.floorMod(lane, 4);
        boolean player = lane >= Math.max(1, totalLanes / 2);
        BufferedImage[] set = player
                ? (coming ? rightComing : rightArrow)
                : (coming ? leftComing : leftArrow);

        if (dir < 0 || dir >= set.length) {
            return null;
        }
        return set[dir];
    }

    private void switchTab(int delta) {
        int next = (tabs.getSelectedIndex() + delta + tabs.getTabCount()) % tabs.getTabCount();
        tabs.setSelectedIndex(next);
    }

    private void returnToPlayState() {
        repaintTimer.stop();
        if (frame != null) frame.dispose();
        try {
            Class<?> cls = Class.forName("Source.PlayState");
            Constructor<?> ctor = cls.getConstructor(String.class, boolean.class);
            ctor.newInstance(currentSongName(), botPlay);
        } catch (Throwable t) {
            System.out.println("[ChartEditor] Could not return to PlayState: " + t.getMessage());
        }
    }

    private void syncFromUi() {
        showingPlayerSide = chkShowPlayer.isSelected();
        showGridNumbers = chkShowNumbers.isSelected();
        spamMode = chkSpamMode.isSelected();
        try { bpm = Double.parseDouble(bpmField.getText().trim()); } catch (Exception ignored) {}
        try { speed = Double.parseDouble(speedField.getText().trim()); } catch (Exception ignored) {}
        try {
            int spinnerKeys = ((Number) maniaSpinner.getValue()).intValue();
            if (spinnerKeys != maniaKeys) {
                applyMania(spinnerKeys);
            }
        } catch (Exception ignored) {}
    }

    @Override
    public void paint(Graphics g) {
        super.paint(g);
    }

    private class EditorPanel extends JPanel {
        EditorPanel() {
            setPreferredSize(new Dimension(W, 540));
            setBackground(BG);
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            syncFromUi();

            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            drawTitle(g2);
            drawGrid(g2);
            drawNotes(g2);
            drawSelection(g2);
            drawFooter(g2);

            g2.dispose();
        }

        private void drawTitle(Graphics2D g2) {
            g2.setColor(TEXT);
            g2.setFont(getFont().deriveFont(Font.BOLD, 18f));
            g2.drawString("Chart Editor - " + currentSongName(), 20, 24);

            g2.setFont(getFont().deriveFont(Font.PLAIN, 13f));
            g2.drawString("Section " + currentSection + " | Lane " + selectedLane + " | Row " + selectedRow, 20, 44);
            g2.drawString("BPM " + bpm + " | Speed " + speed + " | Spam " + spamMode + " | Keys " + maniaKeys + "k", 20, 62);
        }

        private void drawGrid(Graphics2D g2) {
            int laneW = GRID_W / totalLanes;
            int rowH = GRID_H / ROWS;

            g2.setColor(GRID_BG);
            g2.fillRoundRect(GRID_X - 4, GRID_Y - 4, GRID_W + 8, GRID_H + 8, 12, 12);

            int split = Math.max(1, totalLanes / 2);
            if (showingPlayerSide) {
                g2.setColor(PLAYER_TINT);
                g2.fillRect(GRID_X + laneW * split, GRID_Y, laneW * (totalLanes - split), GRID_H);
            }
            g2.setColor(OPP_TINT);
            g2.fillRect(GRID_X, GRID_Y, laneW * split, GRID_H);

            for (int row = 0; row <= ROWS; row++) {
                int y = GRID_Y + row * rowH;
                g2.setColor(row % Math.max(1, totalLanes / 2) == 0 ? GRID_LINE : GRID_MINOR);
                g2.drawLine(GRID_X, y, GRID_X + GRID_W, y);

                if (showGridNumbers && row < ROWS) {
                    g2.setColor(new Color(220, 220, 220, 120));
                    g2.drawString(String.valueOf(row), 22, y + rowH / 2);
                }
            }

            for (int lane = 0; lane <= totalLanes; lane++) {
                int x = GRID_X + lane * laneW;
                g2.setColor(lane == totalLanes / 2 ? new Color(130, 180, 255) : GRID_MINOR);
                g2.drawLine(x, GRID_Y, x, GRID_Y + GRID_H);
            }
        }

        private void drawNotes(Graphics2D g2) {
            int laneW = GRID_W / totalLanes;
            int rowH = GRID_H / ROWS;
            int noteSize = Math.max(22, Math.min(laneW - 8, rowH - 6));

            for (NoteData note : notes) {
                if (note.sectionIndex != currentSection) continue;

                int x = GRID_X + note.lane * laneW + (laneW - noteSize) / 2;
                int y = GRID_Y + note.row * rowH + (rowH - noteSize) / 2;

                g2.setColor(NOTE_BG);
                g2.fillRoundRect(x - 2, y - 2, noteSize + 4, noteSize + 4, 10, 10);

                BufferedImage image = laneImage(note.lane, true);
                if (image != null) {
                    g2.drawImage(image, x, y, noteSize, noteSize, null);
                } else {
                    g2.setColor(note.lane < Math.max(1, totalLanes / 2) ? new Color(255, 110, 110) : new Color(110, 160, 255));
                    g2.fillRoundRect(x, y, noteSize, noteSize, 10, 10);
                }
            }
        }

        private void drawSelection(Graphics2D g2) {
            int laneW = GRID_W / totalLanes;
            int rowH = GRID_H / ROWS;
            int x = GRID_X + selectedLane * laneW;
            int y = GRID_Y + selectedRow * rowH;

            g2.setColor(SELECT);
            g2.fillRect(x, y, laneW, rowH);
            g2.setColor(new Color(255, 255, 255, 170));
            g2.drawRect(x + 1, y + 1, laneW - 2, rowH - 2);
        }

        private void drawFooter(Graphics2D g2) {
            int y = GRID_Y + GRID_H + 22;
            g2.setColor(TEXT);
            g2.drawString("Click a box to place/remove a note.  Mouse wheel changes section.  Tab changes tabs.  S saves.  R reloads.", 20, y);
            g2.drawString("Left side is the opponent half. Right side is the player half.", 20, y + 18);
            g2.drawString("Notes use the arrow-coming images from /Notes.", 20, y + 36);
        }
    }

    @Override
    public void mouseClicked(MouseEvent e) {
        int lane = laneAt(e.getX());
        int row = rowAt(e.getY());
        if (lane < 0 || row < 0) return;
        selectedLane = lane;
        selectedRow = row;
        if (spamMode) {
            spamFromClick(lane, row);
        } else {
            toggleNote(lane, row);
        }
    }

    @Override
    public void mousePressed(MouseEvent e) {
        editorPanel.requestFocusInWindow();
    }

    @Override public void mouseReleased(MouseEvent e) {}
    @Override public void mouseEntered(MouseEvent e) {}
    @Override public void mouseExited(MouseEvent e) {}

    @Override
    public void mouseDragged(MouseEvent e) {
        int lane = laneAt(e.getX());
        int row = rowAt(e.getY());
        if (lane >= 0 && row >= 0) {
            selectedLane = lane;
            selectedRow = row;
            editorPanel.repaint();
        }
    }

    @Override public void mouseMoved(MouseEvent e) {}

    @Override
    public void keyTyped(KeyEvent e) {}

    @Override
    public void keyPressed(KeyEvent e) {
        int code = e.getKeyCode();

        if (code == KeyEvent.VK_TAB) {
            switchTab(e.isShiftDown() ? -1 : 1);
            return;
        }
        if (code == KeyEvent.VK_S) {
            saveChart();
            return;
        }
        if (code == KeyEvent.VK_R) {
            loadChart();
            editorPanel.repaint();
            return;
        }
        if (code == KeyEvent.VK_DELETE || code == KeyEvent.VK_BACK_SPACE) {
            deleteSelected();
            return;
        }
        if (code == KeyEvent.VK_SPACE) {
            if (spamMode) {
                spamFromClick(selectedLane, selectedRow);
            } else {
                toggleNote(selectedLane, selectedRow);
            }
            return;
        }
        if (code == KeyEvent.VK_LEFT) {
            selectedLane = Math.max(0, selectedLane - 1);
            editorPanel.repaint();
            return;
        }
        if (code == KeyEvent.VK_RIGHT) {
            selectedLane = Math.min(totalLanes - 1, selectedLane + 1);
            editorPanel.repaint();
            return;
        }
        if (code == KeyEvent.VK_UP) {
            selectedRow = Math.max(0, selectedRow - 1);
            editorPanel.repaint();
            return;
        }
        if (code == KeyEvent.VK_DOWN) {
            selectedRow = Math.min(ROWS - 1, selectedRow + 1);
            editorPanel.repaint();
            return;
        }
        if (code == KeyEvent.VK_PAGE_UP) {
            currentSection = Math.max(0, currentSection - 1);
            editorPanel.repaint();
            return;
        }
        if (code == KeyEvent.VK_PAGE_DOWN) {
            currentSection++;
            editorPanel.repaint();
            return;
        }
        if (code == KeyEvent.VK_E) {
            chkSpamMode.setSelected(!chkSpamMode.isSelected());
            spamMode = chkSpamMode.isSelected();
        }
    }

    @Override
    public void keyReleased(KeyEvent e) {}

    @Override
    public void mouseWheelMoved(MouseWheelEvent e) {
        currentSection = Math.max(0, currentSection + e.getWheelRotation());
        editorPanel.repaint();
    }

    @Override
    public void stateChanged(ChangeEvent e) {
        spamMode = chkSpamMode.isSelected();
        showGridNumbers = chkShowNumbers.isSelected();
        showingPlayerSide = chkShowPlayer.isSelected();
        if (e != null && e.getSource() == maniaSpinner) {
            applyMania(((Number) maniaSpinner.getValue()).intValue());
        }
        spamPreview.repaint();
        editorPanel.repaint();
    }

    @Override
    public void actionPerformed(ActionEvent e) {
        Object src = e.getSource();

        if (src == btnLoad) {
            loadChart();
            editorPanel.repaint();
        } else if (src == btnSave) {
            saveChart();
        } else if (src == btnPrevSection) {
            currentSection = Math.max(0, currentSection - 1);
            editorPanel.repaint();
        } else if (src == btnNextSection) {
            currentSection++;
            editorPanel.repaint();
        } else if (src == btnDelete) {
            deleteSelected();
        } else if (src == btnClearAll) {
            clearAllNotes();
        } else if (src == btnSpam) {
            spamFillSide();
        } else if (src == btnReturn) {
            returnToPlayState();
        } else if (src == chkShowPlayer || src == chkShowNumbers || src == chkSpamMode) {
            stateChanged(null);
        }
    }

    private class SpamPreviewPanel extends JPanel implements MouseListener {
        SpamPreviewPanel() {
            setPreferredSize(new Dimension(250, 260));
            setMaximumSize(new Dimension(Integer.MAX_VALUE, 260));
            setBorder(BorderFactory.createTitledBorder("Spam preview"));
            setBackground(new Color(28, 28, 34));
            addMouseListener(this);
            setToolTipText("Click here to spam the current side using the density slider.");
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            syncFromUi();

            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);

            int w = getWidth();
            int h = getHeight();
            g2.setColor(new Color(20, 20, 24));
            g2.fillRoundRect(10, 26, w - 20, h - 36, 16, 16);
            g2.setColor(new Color(255, 255, 255, 60));
            g2.drawRoundRect(10, 26, w - 20, h - 36, 16, 16);

            int count = Math.max(1, lengthSlider.getValue());
            int stepRows = densityStepRows();
            int split = Math.max(1, totalLanes / 2);

            int availableH = Math.max(60, h - 70);
            int icon = Math.max(10, Math.min(40, w - 80));
            int gap = Math.max(icon, icon + (stepRows * 2));
            int usedH = icon + (count - 1) * gap;
            int startY = 40 + Math.max(0, (availableH - usedH) / 2);

            int dir = Math.floorMod(selectedLane, 4);
            BufferedImage img = selectedLane >= split ? rightComing[dir] : leftComing[dir];
            if (img == null) img = selectedLane >= split ? rightArrow[dir] : leftArrow[dir];

            int x = (w - icon) / 2;
            int y = startY;
            for (int i = 0; i < count; i++) {
                if (img != null) {
                    g2.drawImage(img, x, y, icon, icon, null);
                }
                y += gap;
            }

            g2.setColor(TEXT);
            g2.setFont(getFont().deriveFont(Font.PLAIN, 12f));
            g2.drawString("Density: " + densitySlider.getValue() + "  Length: " + lengthSlider.getValue(), 16, 18);
            g2.dispose();
        }

        @Override
        public void mouseClicked(MouseEvent e) {
            spamFillSide();
        }

        @Override public void mousePressed(MouseEvent e) {}
        @Override public void mouseReleased(MouseEvent e) {}
        @Override public void mouseEntered(MouseEvent e) {}
        @Override public void mouseExited(MouseEvent e) {}
    }

    private static class NoteData {
        final double timeMs;
        final int lane;
        final int row;
        final int sectionIndex;
        final double sustainMs;
        final boolean mustHit;

        NoteData(double timeMs, int lane, int row, int sectionIndex, double sustainMs, boolean mustHit) {
            this.timeMs = timeMs;
            this.lane = lane;
            this.row = row;
            this.sectionIndex = sectionIndex;
            this.sustainMs = sustainMs;
            this.mustHit = mustHit;
        }
    }
}
