package Source;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.BiConsumer;

/**
 * Universal chart parser for FNF Java Engine
 * Supports: Psych Engine 0.6+, Psych 1.0, Kade Engine, Codename
 * Drop-in replacement for PlayState's inner parser
 */
public class ChartParserUniversal {

    public static class ChartMeta {
        public double bpm = 120.0;
        public double speed = 1.0;
        public String song = "unknown";
    }

    @FunctionalInterface
    public interface NoteConsumer {
        void accept(double timeMs, int lane, double sustainMs, boolean mustHit);
    }

    public static ChartMeta parseFile(Path chartPath, NoteConsumer out) throws IOException {
        String raw = Files.readString(chartPath);
        return parseString(raw, out);
    }

    public static ChartMeta parseString(String json, NoteConsumer out) throws IOException {
        MiniJson p = new MiniJson(json);
        Object root = p.parse();
        Map<String,Object> rootObj = asObj(root);

        // Detect format
        Map<String,Object> songObj = rootObj;
        if (rootObj.containsKey("song") && rootObj.get("song") instanceof Map) {
            songObj = asObj(rootObj.get("song"));
        }

        ChartMeta meta = new ChartMeta();
        meta.song = str(songObj.get("song"), meta.song);
        meta.bpm = num(songObj.get("bpm"), meta.bpm);
        meta.speed = num(songObj.get("speed"), meta.speed);

        // Psych 0.6+ / Kade format: notes: [ { sectionNotes: [...] } ]
        List<Object> sections = asList(songObj.get("notes"));
        if (!sections.isEmpty()) {
            for (Object sec : sections) {
                Map<String,Object> s = asObj(sec);
                boolean mustHit = bool(s.get("mustHitSection"), true);
                // Kade sometimes uses "sectionNotes" or "sectionNotes" with extra data
                List<Object> notes = asList(s.get("sectionNotes"));
                for (Object n : notes) {
                    List<Object> arr = asList(n);
                    if (arr.size() < 2) continue;
                    double time = num(arr.get(0), 0);
                    int lane = (int) Math.round(num(arr.get(1), 0));
                    double sustain = arr.size() > 2 ? num(arr.get(2), 0) : 0;
                    boolean hit = mustHit;
                    if (arr.size() > 3 && arr.get(3) instanceof Boolean) {
                        hit = (Boolean) arr.get(3);
                    } else if (arr.size() > 3 && arr.get(3) instanceof Number) {
                        // Kade type: 0 = normal, ignore for now
                        hit = mustHit;
                    }
                    // Normalize lane for mania (Psych uses 0-3 opponent, 4-7 player for 4k)
                    out.accept(time, lane, sustain, hit);
                }
            }
            return meta;
        }

        // Psych 1.0 legacy: notes: [ [time, lane, sustain], ... ] at top level
        List<Object> flatNotes = asList(rootObj.get("notes"));
        if (!flatNotes.isEmpty() && flatNotes.get(0) instanceof List) {
            for (Object n : flatNotes) {
                List<Object> arr = asList(n);
                if (arr.size() < 2) continue;
                double time = num(arr.get(0), 0);
                int lane = (int) Math.round(num(arr.get(1), 0));
                double sustain = arr.size() > 2 ? num(arr.get(2), 0) : 0;
                out.accept(time, lane, sustain, lane >= 4);
            }
            return meta;
        }

        // Codename format: "strumLines" -> "notes"
        List<Object> strumLines = asList(songObj.get("strumLines"));
        if (!strumLines.isEmpty()) {
            int lineIdx = 0;
            for (Object line : strumLines) {
                Map<String,Object> l = asObj(line);
                boolean mustHit = bool(l.get("mustHit"), lineIdx == 1);
                List<Object> notes = asList(l.get("notes"));
                for (Object n : notes) {
                    Map<String,Object> nn = asObj(n);
                    double time = num(nn.get("time"), 0);
                    int lane = (int) num(nn.get("id"), 0) + (lineIdx * 4);
                    double sustain = num(nn.get("sLen"), 0);
                    out.accept(time, lane, sustain, mustHit);
                }
                lineIdx++;
            }
        }

        return meta;
    }

    // --- helpers ---
    private static Map<String,Object> asObj(Object o) {
        if (o instanceof Map) {
            Map<String,Object> m = new LinkedHashMap<>();
            ((Map<?,?>)o).forEach((k,v)-> m.put(String.valueOf(k), v));
            return m;
        }
        return new LinkedHashMap<>();
    }
    private static List<Object> asList(Object o) {
        if (o instanceof List) return (List<Object>) o;
        return Collections.emptyList();
    }
    private static double num(Object o, double d) {
        if (o instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(String.valueOf(o)); } catch(Exception e) { return d; }
    }
    private static String str(Object o, String d) { return o == null ? d : String.valueOf(o); }
    private static boolean bool(Object o, boolean d) { return o instanceof Boolean b ? b : d; }

    // Minimal JSON parser (same as your editor, trimmed)
    private static class MiniJson {
        final String s; int i=0;
        MiniJson(String s){this.s=s;}
        Object parse() throws IOException { skip(); return val(); }
        void skip(){ while(i<s.length() && s.charAt(i)<=32) i++; }
        Object val() throws IOException { skip(); if(i>=s.length()) return null; char c=s.charAt(i);
            if(c=='{') return obj(); if(c=='[') return arr(); if(c=='"') return str(); if(c=='t'){i+=4;return true;} if(c=='f'){i+=5;return false;} if(c=='n'){i+=4;return null;} return num(); }
        Map<String,Object> obj() throws IOException { i++; Map<String,Object> m=new LinkedHashMap<>(); skip(); if(i<s.length()&&s.charAt(i)=='}'){i++;return m;} while(true){ String k=str(); skip(); i++; Object v=val(); m.put(k,v); skip(); if(i<s.length()&&s.charAt(i)==','){i++;continue;} if(i<s.length()&&s.charAt(i)=='}'){i++;break;} } return m; }
        List<Object> arr() throws IOException { i++; List<Object> l=new ArrayList<>(); skip(); if(i<s.length()&&s.charAt(i)==']'){i++;return l;} while(true){ l.add(val()); skip(); if(i<s.length()&&s.charAt(i)==','){i++;continue;} if(i<s.length()&&s.charAt(i)==']'){i++;break;} } return l; }
        String str() throws IOException { i++; StringBuilder b=new StringBuilder(); while(i<s.length()){ char c=s.charAt(i++); if(c=='"') break; if(c=='\\'){ char e=s.charAt(i++); b.append(switch(e){case 'n'->'\n';case 'r'->'\r';case 't'->'\t';case 'b'->'\b';case 'f'->'\f';case '"','\\','/'->e;default->e;}); } else b.append(c);} return b.toString(); }
        Number num() { int a=i; while(i<s.length()){ char c=s.charAt(i); if((c>='0'&&c<='9')||c=='-'||c=='+'||c=='.'||c=='e'||c=='E') i++; else break; } String t=s.substring(a,i); return t.contains(".")||t.contains("e")||t.contains("E")?Double.parseDouble(t):Long.parseLong(t); }
    }
}
