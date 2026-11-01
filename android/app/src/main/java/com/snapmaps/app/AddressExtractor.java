package com.snapmaps.app;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Selects a spatially connected address above the ride footer, never the last N map labels. */
final class AddressExtractor {
    static final class Line {
        final String text;
        final int left, top, right, bottom;
        Line(String text, int left, int top, int right, int bottom) {
            this.text = text.trim().replaceAll("\\s+", " ");
            this.left = left; this.top = top; this.right = right; this.bottom = bottom;
        }
        int height() { return Math.max(1, bottom - top); }
    }

    private static final Pattern ADDRESS_WORD = Pattern.compile(
            "(?iu)\\b(road|rd|street|st|main|cross|layout|nagar|halli|apartment|apartments|"
            + "residency|tower|towers|avenue|block|sector|phase|colony|floor|building|"
            + "bengaluru|bangalore|karnataka|india)\\b|\\b[1-9][0-9]{5}\\b");
    private static final Pattern LETTER = Pattern.compile("[\\p{L}\\p{M}]");

    private AddressExtractor() { }

    static String extract(List<Line> input, int width, int height) {
        List<Line> lines = new ArrayList<>(input);
        Collections.sort(lines, (a, b) -> a.top == b.top ? Integer.compare(a.left, b.left) : Integer.compare(a.top, b.top));
        int footerTop = height;
        boolean hasFooter = false;
        for (Line line : lines) {
            if (isFooter(line.text)) { footerTop = line.top; hasFooter = true; }
        }

        List<Line> eligible = new ArrayList<>();
        for (Line line : lines) {
            if (line.bottom >= footerTop || line.left > width * 0.28f
                    || line.text.length() < 2 || isChrome(line.text)
                    || !LETTER.matcher(line.text).find()) continue;
            eligible.add(line);
        }
        if (eligible.isEmpty()) return "";
        Line last = eligible.get(eligible.size() - 1);
        // A bottom address card is expected near the footer / bottom edge of this lower-half crop.
        if (footerTop - last.bottom > Math.max(last.height() * 4, height * 0.22f)) return "";
        List<Line> group = new ArrayList<>();
        group.add(last);
        for (int i = eligible.size() - 2; i >= 0; i--) {
            Line previous = eligible.get(i);
            Line next = group.get(0);
            int gap = next.top - previous.bottom;
            if (gap > Math.max(previous.height(), next.height()) * 1.5f
                    || Math.abs(previous.left - next.left) > width * 0.16f) break;
            group.add(0, previous);
        }
        // Passenger headings have no address structure. Keep a business/place heading if it
        // carries address evidence; otherwise remove the short personal-name row.
        if (group.size() >= 2) {
            String first = group.get(0).text;
            if (!hasAddressEvidence(first) && wordCount(first) <= 4
                    && (group.get(0).height() > group.get(1).height() * 1.08f
                    || wordCount(first) == 1)) group.remove(0);
        }
        if (group.isEmpty()) return "";
        StringBuilder result = new StringBuilder();
        for (Line line : group) {
            // OCR lines are visual word wraps, not boundaries between address components.
            if (result.length() > 0) result.append(' ');
            result.append(line.text);
        }
        String address = result.toString();
        if (address.length() < 14 || address.length() > 600) return "";
        boolean structured = ADDRESS_WORD.matcher(address).find();
        boolean commaAddress = address.contains(",") && wordCount(address) >= 5;
        if (!structured && !commaAddress) return "";
        // Without a known ride footer, require a multi-line, structured bottom block.
        if (!hasFooter && (group.size() < 2 || !structured || !commaAddress)) return "";
        return address;
    }

    private static int wordCount(String text) { return text.trim().split("\\s+").length; }
    private static boolean hasAddressEvidence(String text) {
        return text.contains(",") || ADDRESS_WORD.matcher(text).find();
    }
    private static boolean isFooter(String text) {
        String value = text.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}]", "");
        return value.contains("completeride") || value.contains("endride") || value.contains("finishride");
    }
    private static boolean isChrome(String text) {
        String value = text.toLowerCase(Locale.ROOT);
        return isFooter(text) || value.matches(".*(go to drop|drop location|go to pickup|pickup location|"
                + "km/h|google|battery|cancel ride|call customer|call rider).*")
                || value.matches("[\\d\\s:%.]+") || value.equals("uber");
    }
}
