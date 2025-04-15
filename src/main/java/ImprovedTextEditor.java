import api.APIProvider;

import javax.swing.*;
import javax.swing.event.*;
import javax.swing.text.*;
import javax.swing.text.html.HTMLDocument;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.html.StyleSheet;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.*;

import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.CompletableFuture;

public class ImprovedTextEditor extends JTextPane {
    private final JLabel statusBar;
    private final PreferencesManager prefs;
    private APIProvider currentProvider;
    private Timer autocompleteTimer;
    private boolean isAutocompleteActive = false;
    private JPopupMenu autoCompletePopup;
    private String[] currentSuggestions;

    // Character counters
    private int aiCharCount = 0;
    private int humanCharCount = 0;

    // Track if doc has unsaved changes
    private boolean isDirty = false;

    public ImprovedTextEditor(JLabel statusBar, PreferencesManager prefs) {
        this.statusBar = statusBar;
        this.prefs = prefs;
        setupEditorKit();
        setText("");
        autoCompletePopup = new JPopupMenu();

        setupDocumentListener();
        setupAutocompleteTimer();
        setupTypingListener();
        loadNumSuggestions();

        // We'll rely on the default key actions from HTMLEditorKit
        // No SHIFT+ENTER/ENTER override
    }

    private void setupEditorKit() {
        HTMLEditorKit kit = new HTMLEditorKit();
        setEditorKit(kit);

        // Minimal style adjustments
        StyleSheet styles = kit.getStyleSheet();
        // For basic headings, let <h1> be 24pt, <h2> 18pt, normal 12pt, etc.
        styles.addRule("body { font-size: 12pt; font-family: Serif; }");
        styles.addRule("h1 { font-size: 24pt; }");
        styles.addRule("h2 { font-size: 18pt; }");
    }

    private void setupDocumentListener() {
        getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                isDirty = true;
                scheduleAutocomplete();
            }
            @Override
            public void removeUpdate(DocumentEvent e) {
                isDirty = true;
                scheduleAutocomplete();
            }
            @Override
            public void changedUpdate(DocumentEvent e) {
                isDirty = true;
            }
        });
    }

    public boolean isDirty() {
        return isDirty;
    }
    public void markClean() {
        isDirty = false;
    }

    private void setupAutocompleteTimer() {
        autocompleteTimer = new Timer("AutocompleteTimer", true);
    }

    private void setupTypingListener() {
        addKeyListener(new KeyAdapter() {
            @Override
            public void keyTyped(KeyEvent e) {
                char c = e.getKeyChar();
                if (!Character.isISOControl(c)) {
                    humanCharCount++;
                }
            }
        });
    }

    private void loadNumSuggestions() {
        int n = 3;
        try {
            n = Integer.parseInt(prefs.getPreference("numSuggestions", "3"));
            if (n < 1) n = 1;
            if (n > 10) n = 10;
        } catch (Exception ex) {
            n = 3;
        }
        currentSuggestions = new String[n];
    }

    public void setAPIProvider(APIProvider provider) {
        this.currentProvider = provider;
    }

    public void resetCharacterCounts() {
        aiCharCount = 0;
        humanCharCount = 0;
    }
    public void setAICharCount(int n) { aiCharCount = n; }
    public void setHumanCharCount(int n) { humanCharCount = n; }
    public int getAICharCount() { return aiCharCount; }
    public int getHumanCharCount() { return humanCharCount; }

    private void scheduleAutocomplete() {
        if (isAutocompleteActive || currentProvider == null) return;

        autocompleteTimer.cancel();
        autocompleteTimer = new Timer("AutocompleteTimer", true);

        autocompleteTimer.schedule(new java.util.TimerTask() {
            @Override
            public void run() {
                SwingUtilities.invokeLater(() -> triggerAutocomplete());
            }
        }, 1000); // 1 second
    }

    public void triggerAutocomplete() {
        if (currentProvider == null || isAutocompleteActive) return;
        String contextText = getPlainText();
        if (contextText.length() < 20) {
            return; // skip if doc too short
        }

        loadNumSuggestions();
        isAutocompleteActive = true;
        statusBar.setText("Generating suggestions...");

        CompletableFuture<String>[] futures = new CompletableFuture[currentSuggestions.length];
        for (int i = 0; i < currentSuggestions.length; i++) {
            final int variation = (i % 3) + 1;
            futures[i] = CompletableFuture.supplyAsync(() -> {
                try {
                    // We'll assume your new base prompt has a line about capitalization
                    String prompt = AutocompletePromptManager.getPrompt(contextText, variation);
                    return currentProvider.generateCompletion(prompt);
                } catch (Exception ex) {
                    ex.printStackTrace();
                    return "";
                }
            });
        }

        CompletableFuture.allOf(futures).thenRun(() -> {
            try {
                for (int i = 0; i < futures.length; i++) {
                    String raw = futures[i].get().trim();
                    raw = cleanOverlap(contextText, raw);
                    currentSuggestions[i] = raw;
                }
            } catch (Exception ex) {
                ex.printStackTrace();
            }
            SwingUtilities.invokeLater(() -> {
                showAutoCompletePopup(currentSuggestions);
                isAutocompleteActive = false;
                statusBar.setText("Ready");
            });
        });
    }

    /**
     * Return plain text (no HTML tags).
     */
    private String getPlainText() {
        try {
            Document doc = getDocument();
            return doc.getText(0, doc.getLength());
        } catch (BadLocationException ex) {
            ex.printStackTrace();
            return "";
        }
    }

    /**
     * The older approach with more thorough overlap detection
     */
    private String cleanOverlap(String existingText, String suggestion) {
        suggestion = suggestion.trim();
        if (suggestion.isEmpty()) return suggestion;

        // We'll do a multi-step approach:
        // 1) remove repeated leading words from suggestion if they appear at the tail of existingText
        // 2) remove ellipses if any
        // 3) optionally fix spacing or capitalization if needed
        // We'll keep it straightforward.

        // step 1: overlap
        int overlapZone = Math.min(40, existingText.length());
        String tail = existingText.substring(existingText.length() - overlapZone).toLowerCase();
        String lowerSug = suggestion.toLowerCase();

        int maxLen = 0;
        for (int len = overlapZone; len > 0; len--) {
            String suffix = tail.substring(overlapZone - len);
            if (lowerSug.startsWith(suffix)) {
                maxLen = len;
                break;
            }
        }
        if (maxLen > 0) {
            suggestion = suggestion.substring(maxLen).trim();
        }

        // step 2: remove trailing ellipses
        while (suggestion.endsWith("...")) {
            suggestion = suggestion.substring(0, suggestion.length() - 3).trim();
        }

        return suggestion;
    }

    private void showAutoCompletePopup(String[] suggestions) {
        autoCompletePopup.removeAll();
        int validCount = 0;
        for (int i = 0; i < suggestions.length; i++) {
            String s = suggestions[i];
            if (s != null && !s.isEmpty()) {
                validCount++;
                final int idx = i;
                JMenuItem item = new JMenuItem((i+1) + ") " + s);
                item.addActionListener(e -> insertSuggestionAtCaret(suggestions[idx]));
                autoCompletePopup.add(item);
            }
        }
        if (validCount == 0) return;
        try {
            int caretPos = getCaretPosition();
            Rectangle r = modelToView(caretPos);
            if (r != null) {
                autoCompletePopup.show(this, r.x, r.y + r.height);
            }
        } catch (BadLocationException ex) {
            ex.printStackTrace();
        }
    }

    public void insertSuggestionByIndex(int idx) {
        if (idx < 0 || idx >= currentSuggestions.length) return;
        String suggestion = currentSuggestions[idx];
        if (suggestion != null && !suggestion.isEmpty()) {
            insertSuggestionAtCaret(suggestion);
        }
    }

    public void insertSuggestionAtCaret(String suggestion) {
        if (suggestion == null || suggestion.isEmpty()) return;
        int caretPos = getCaretPosition();
        try {
            AttributeSet attrs = getCharacterAttributes();
            getDocument().insertString(caretPos, suggestion, attrs);
            aiCharCount += suggestion.length();
            setCaretPosition(caretPos + suggestion.length());
            if (autoCompletePopup.isVisible()) {
                autoCompletePopup.setVisible(false);
            }
            statusBar.setText("Suggestion inserted. AI chars: "
                    + aiCharCount + " / Human chars: " + humanCharCount);
            isDirty = true;
        } catch (BadLocationException ex) {
            ex.printStackTrace();
            statusBar.setText("Error inserting suggestion.");
        }
    }

    /* ========== Formatting ========== */

    /**
     * We'll actually insert <h1> or <h2> tags around the selection or paragraph
     * so that it gets saved as HTML headings.
     */
    public void setHeadingLevel(int level) {
        try {
            HTMLDocument doc = (HTMLDocument) getDocument();
            int start = getSelectionStart();
            int end = getSelectionEnd();

            if (start == end) {
                // no selection => apply heading to current paragraph
                Element para = doc.getParagraphElement(start);
                start = para.getStartOffset();
                end = para.getEndOffset();
            }

            String selectedText = doc.getText(start, end - start);
            String tag = (level == 1) ? "h1" : "h2";

            // remove the selection
            doc.remove(start, end - start);

            // insert <h1>selected text</h1>
            // note: insertHTML offsets can be tricky
            // We'll pass the entire <hX> block as a snippet
            String html = "<" + tag + ">" + escapeHTML(selectedText) + "</" + tag + ">";
            ((HTMLEditorKit) getEditorKit()).insertHTML(doc, start, html, 0, 0, null);
            isDirty = true;
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    /**
     * Revert selected text or current paragraph to normal text by removing h1/h2 tags.
     * We'll do this by removing them from the HTML.
     */
    public void setNormalText() {
        try {
            HTMLDocument doc = (HTMLDocument) getDocument();
            int start = getSelectionStart();
            int end = getSelectionEnd();
            if (start == end) {
                Element para = doc.getParagraphElement(start);
                start = para.getStartOffset();
                end = para.getEndOffset();
            }
            String selectedHTML = doc.getText(start, end - start);

            // remove
            doc.remove(start, end - start);

            // strip h1/h2 tags if present
            String replaced = selectedHTML
                    .replaceAll("(?i)</?h1>", "")
                    .replaceAll("(?i)</?h2>", "");
            // keep the raw text or any inline <b>/<i> etc. alone

            // insert as normal text (no heading tag)
            ((HTMLEditorKit) getEditorKit()).insertHTML(doc, start, escapeHTML(replaced), 0, 0, null);
            isDirty = true;
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    /**
     * Basic HTML escaping
     */
    private String escapeHTML(String str) {
        return str.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    /**
     * Applies bold/italic/underline inline for the selection or toggles if no selection
     */
    public void applyBoldToSelectionOrToggle() {
        int start = getSelectionStart();
        int end = getSelectionEnd();
        if (start < end) {
            toggleOrApplyAttributeOnSelection(start, end, "bold");
        } else {
            toggleBold();
        }
    }

    public void applyItalicToSelectionOrToggle() {
        int start = getSelectionStart();
        int end = getSelectionEnd();
        if (start < end) {
            toggleOrApplyAttributeOnSelection(start, end, "italic");
        } else {
            toggleItalic();
        }
    }

    public void applyUnderlineToSelectionOrToggle() {
        int start = getSelectionStart();
        int end = getSelectionEnd();
        if (start < end) {
            toggleOrApplyAttributeOnSelection(start, end, "underline");
        } else {
            toggleUnderline();
        }
    }

    private void toggleOrApplyAttributeOnSelection(int start, int end, String attrType) {
        isDirty = true;
        StyledDocument sdoc = (StyledDocument) getDocument();
        boolean allSet = true;

        for (int pos = start; pos < end; pos++) {
            Element el = sdoc.getCharacterElement(pos);
            AttributeSet as = el.getAttributes();
            if ("bold".equals(attrType) && !StyleConstants.isBold(as)) {
                allSet = false; break;
            }
            if ("italic".equals(attrType) && !StyleConstants.isItalic(as)) {
                allSet = false; break;
            }
            if ("underline".equals(attrType) && !StyleConstants.isUnderline(as)) {
                allSet = false; break;
            }
        }

        MutableAttributeSet newAttr = new SimpleAttributeSet();
        if ("bold".equals(attrType)) {
            StyleConstants.setBold(newAttr, !allSet);
        } else if ("italic".equals(attrType)) {
            StyleConstants.setItalic(newAttr, !allSet);
        } else if ("underline".equals(attrType)) {
            StyleConstants.setUnderline(newAttr, !allSet);
        }

        sdoc.setCharacterAttributes(start, end - start, newAttr, false);
    }

    private void toggleBold() {
        isDirty = true;
        MutableAttributeSet attr = new SimpleAttributeSet(getInputAttributes());
        boolean isBold = StyleConstants.isBold(attr);
        StyleConstants.setBold(attr, !isBold);
        setCharacterAttributes(attr, false);
    }

    private void toggleItalic() {
        isDirty = true;
        MutableAttributeSet attr = new SimpleAttributeSet(getInputAttributes());
        boolean isItalic = StyleConstants.isItalic(attr);
        StyleConstants.setItalic(attr, !isItalic);
        setCharacterAttributes(attr, false);
    }

    private void toggleUnderline() {
        isDirty = true;
        MutableAttributeSet attr = new SimpleAttributeSet(getInputAttributes());
        boolean isUnderline = StyleConstants.isUnderline(attr);
        StyleConstants.setUnderline(attr, !isUnderline);
        setCharacterAttributes(attr, false);
    }

    /**
     * If there's a selection, apply that font family to it. Else toggle future text style.
     */
    public void setFontFamily(String family) {
        isDirty = true;
        int start = getSelectionStart();
        int end = getSelectionEnd();
        MutableAttributeSet attr = new SimpleAttributeSet();
        StyleConstants.setFontFamily(attr, family);

        if (start < end) {
            ((StyledDocument) getDocument()).setCharacterAttributes(start, end - start, attr, false);
        } else {
            setCharacterAttributes(attr, false);
        }
    }

    public void setFontSize(int size) {
        isDirty = true;
        int start = getSelectionStart();
        int end = getSelectionEnd();
        MutableAttributeSet attr = new SimpleAttributeSet();
        StyleConstants.setFontSize(attr, size);

        if (start < end) {
            ((StyledDocument) getDocument()).setCharacterAttributes(start, end - start, attr, false);
        } else {
            setCharacterAttributes(attr, false);
        }
    }
}
