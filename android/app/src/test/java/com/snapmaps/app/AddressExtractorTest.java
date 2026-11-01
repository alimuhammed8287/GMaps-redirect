package com.snapmaps.app;

import org.junit.Test;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import static org.junit.Assert.assertEquals;

/** OCR geometry fixtures. These test address selection, not OCR recognition or device capture. */
public final class AddressExtractorTest {
    private static AddressExtractor.Line line(String text, int y, int height) {
        return new AddressExtractor.Line(text, 30, y, 650, y + height);
    }
    private static AddressExtractor.Line footer() { return line("Complete Ride", 680, 28); }
    private static String extract(AddressExtractor.Line... lines) {
        return AddressExtractor.extract(Arrays.asList(lines), 720, 800);
    }

    @Test public void bottomCardWinsOverMapLabelsAndPassengerName() {
        assertEquals("Brigade Cornerstone Utopia, Paradise, Brigade Cornerstone Utopia, Bengaluru, Karnataka, India",
                extract(line("HSR LAYOUT", 20, 24), line("Bommasandra", 330, 24),
                        line("Rider Name", 520, 29),
                        line("Brigade Cornerstone Utopia, Paradise, Brigade", 560, 24),
                        line("Cornerstone Utopia, Bengaluru, Karnataka, India", 600, 24), footer()));
    }

    @Test public void longAddressKeepsWordsAcrossVisualWraps() {
        assertEquals("AJ Power Solutions, 4th Main Road, Muneswara Layout, NGEF Layout, Sadanandanagar, Bennigana Halli, Bengaluru, Karnataka, India",
                extract(line("HSR LAYOUT", 300, 24), line("Md", 480, 29),
                        line("AJ Power Solutions, 4th Main Road, Muneswara", 520, 24),
                        line("Layout, NGEF Layout, Sadanandanagar, Bennigana", 560, 24),
                        line("Halli, Bengaluru, Karnataka, India", 600, 24), footer()));
    }

    @Test public void ocrBlockOrderDoesNotChangeTheDestination() {
        List<AddressExtractor.Line> lines = new ArrayList<>(Arrays.asList(
                line("Rider", 520, 29), line("Example Apartments, 4th Main Road,", 560, 24),
                line("Bengaluru, Karnataka, India", 600, 24), footer()));
        String expected = AddressExtractor.extract(lines, 720, 800);
        Collections.reverse(lines);
        assertEquals("Example Apartments, 4th Main Road, Bengaluru, Karnataka, India", expected);
        assertEquals(expected, AddressExtractor.extract(lines, 720, 800));
    }

    @Test public void mapOnlyScreenshotDoesNotBecomeAnAddress() {
        assertEquals("", extract(line("Marathahalli", 300, 24), line("HSR Layout", 590, 24)));
    }

    @Test public void footerAloneDoesNotPullInDistantMapLabels() {
        assertEquals("", extract(line("HSR Layout", 200, 24), footer()));
    }

    @Test public void unrelatedMessageDoesNotBecomeADestination() {
        assertEquals("", extract(line("Customer", 520, 29), line("Please call when you arrive", 560, 24), footer()));
    }

    @Test public void placeNameIsKeptWhenWrappedWithoutPassengerHeader() {
        assertEquals("Brigade Cornerstone Utopia Bengaluru, Karnataka, India",
                extract(line("Brigade Cornerstone Utopia", 560, 24),
                        line("Bengaluru, Karnataka, India", 600, 24), footer()));
    }

    @Test public void rightHandMapLabelIsExcluded() {
        assertEquals("Example Apartments, 4th Main Road, Bengaluru, Karnataka, India",
                extract(line("Example Apartments, 4th Main Road,", 560, 24),
                        line("Bengaluru, Karnataka, India", 600, 24),
                        new AddressExtractor.Line("Other Layout", 440, 640, 700, 664), footer()));
    }

    @Test public void bottomMultilineAddressCanWorkWithoutRideButton() {
        assertEquals("Example Apartments, 4th Main Road, Bengaluru, Karnataka, India",
                extract(line("Example Apartments, 4th Main Road,", 660, 24),
                        line("Bengaluru, Karnataka, India", 700, 24)));
    }

    @Test public void kannadaCombiningMarksRemainInTheAddress() {
        assertEquals("ಉದಾಹರಣೆ ಅಪಾರ್ಟ್ಮೆಂಟ್, ನಾಲ್ಕನೇ ಮುಖ್ಯ ರಸ್ತೆ, ಬೆಂಗಳೂರು, ಕರ್ನಾಟಕ, ಭಾರತ",
                extract(line("ಉದಾಹರಣೆ ಅಪಾರ್ಟ್ಮೆಂಟ್, ನಾಲ್ಕನೇ ಮುಖ್ಯ ರಸ್ತೆ,", 560, 24),
                        line("ಬೆಂಗಳೂರು, ಕರ್ನಾಟಕ, ಭಾರತ", 600, 24), footer()));
    }
}
