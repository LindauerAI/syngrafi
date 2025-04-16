import api.APIProvider;

import javax.swing.*;
import javax.swing.event.*;
import javax.swing.text.*;
import javax.swing.text.html.HTMLDocument;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.html.StyleSheet;
import javax.swing.undo.CannotRedoException;
import javax.swing.undo.CannotUndoException;
import javax.swing.undo.UndoManager;
import java.awt.*;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.CompletableFuture;

/**
 * A text editor that uses HTMLEditorKit for formatting,
 * tracks AI/Human character counts, supports toggles for bold/italic/underline,
 * headings (h1/h2) in actual HTML tags, and an adjustable autocomplete timer.
 * <p>
 * Shift+Enter / Enter use the default HTMLEditorKit behavior.
 * If user presses Enter on a heading line, the next line becomes normal text.
 */
public class TextEditor extends JTextPane {
    private final JLabel statusBar;
    private final PreferencesManager prefs;
    private APIProvider currentProvider;
    private UndoManager undoManager = new UndoManager();


    private Timer autocompleteTimer;
    private boolean isAutocompleteActive = false;
    private JPopupMenu autoCompletePopup;
    private String[] currentSuggestions;

    // Character counters
    private int aiCharCount = 0;
    private int humanCharCount = 0;

    // Track if doc has unsaved changes
    private boolean isDirty = false;

    public TextEditor(JLabel statusBar, PreferencesManager prefs) {
        this.statusBar = statusBar;
        this.prefs = prefs;

        // Use an HTML editor kit with minimal styling
        HTMLEditorKit kit = new HTMLEditorKit();
        setEditorKit(kit);

        // Provide some default style for headings, normal text
        StyleSheet styles = kit.getStyleSheet();
        styles.addRule("body { font-size: 12pt; font-family: Serif; }");
        styles.addRule("ul, ol { margin-left: 20px; padding-left: 20px; }");
        styles.addRule("li { margin-left: 0; }");
        styles.addRule("h1 { font-size: 24pt; }");
        styles.addRule("h2 { font-size: 18pt; }");

        setText("");
        autoCompletePopup = new JPopupMenu();
        autoCompletePopup.setFocusable(false);

        getDocument().addUndoableEditListener(e -> undoManager.addEdit(e.getEdit()));

        setupDocumentListener();
        setupAutocompleteTimer();
        setupTypingListener();
        loadNumSuggestions();

        // We rely on the default HTMLEditorKit behavior for Enter/Shift+Enter
        // but we do intercept 'Enter' in processKeyEvent to see if user is in a heading.
    }

    // Undo helper method
    public void undo() {
        try {
            if (undoManager.canUndo()) {
                undoManager.undo();
            }
        } catch (CannotUndoException ex) {
            ex.printStackTrace();
        }
    }

    // Redo helper method
    public void redo() {
        try {
            if (undoManager.canRedo()) {
                undoManager.redo();
            }
        } catch (CannotRedoException ex) {
            ex.printStackTrace();
        }
    }

    /**
     * We override processKeyEvent so that after user presses Enter
     * on a heading line, we set them to normal text for the next line.
     */
    @Override
    protected void processKeyEvent(KeyEvent e) {
        super.processKeyEvent(e);

        // After the default Enter is inserted, check if we were in a heading
        if (e.getID() == KeyEvent.KEY_PRESSED && e.getKeyCode() == KeyEvent.VK_ENTER) {
            SwingUtilities.invokeLater(() -> {
                if (isCaretInHeading()) {
                    setNormalText();
                }
            });
        }
    }


    public void setAPIProvider(APIProvider provider) {
        this.currentProvider = provider;
    }

    /**
     * Returns true if the caret is inside <h1>...</h1> or <h2>...</h2>
     */
    private boolean isCaretInHeading() {
        try {
            int pos = getCaretPosition();
            Document doc = getDocument();
            String text = doc.getText(0, doc.getLength());

            // Find the last <h1> or <h2> before 'pos'
            int h1Start = text.lastIndexOf("<h1>", pos);
            int h2Start = text.lastIndexOf("<h2>", pos);
            if (h1Start < 0 && h2Start < 0) return false;

            boolean isH1 = (h1Start > h2Start); // which is bigger?
            int startTag = isH1 ? h1Start : h2Start;
            String closeTag = isH1 ? "</h1>" : "</h2>";

            // find matching closeTag after startTag
            int closePos = text.indexOf(closeTag, startTag);
            if (closePos < 0) return false;

            // If 'pos' is between startTag and closePos, we are in heading
            return (pos >= startTag && pos <= closePos);
        } catch (BadLocationException ex) {
            ex.printStackTrace();
            return false;
        }
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

    /**
     * Reads the user's chosen "autocompleteDelay" in ms from settings
     * and waits that long after the last typed character before triggering suggestions.
     */
    private void scheduleAutocomplete() {
        if (isAutocompleteActive || currentProvider == null) return;

        int delay = 600;
        try {
            delay = Integer.parseInt(prefs.getPreference("autocompleteDelay", "600"));
            if (delay < 0) delay = 600;
        } catch (Exception ex) {
            delay = 600;
        }

        autocompleteTimer.cancel();
        autocompleteTimer = new Timer("AutocompleteTimer", true);

        autocompleteTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                SwingUtilities.invokeLater(() -> triggerAutocomplete());
            }
        }, delay);
    }

    /**
     * Removes leading/trailing ellipses and inserts a space if needed.
     */
    private String cleanupSuggestion(String contextText, String suggestion) {
        // 1) Trim leading/trailing whitespace
        suggestion = suggestion.trim();

        // 2) Remove leading/trailing "..."
        while (suggestion.startsWith("...")) {
            suggestion = suggestion.substring(3).trim();
        }
        while (suggestion.endsWith("...")) {
            suggestion = suggestion.substring(0, suggestion.length() - 3).trim();
        }

        // 3) If contextText ends with punctuation and suggestion does not start with a space, add one.
        if (!contextText.isEmpty()) {
            char lastChar = contextText.charAt(contextText.length() - 1);
            if (lastChar == '.' || lastChar == '?' || lastChar == '!') {
                if (!suggestion.startsWith(" ")) {
                    suggestion = " " + suggestion;
                }
            }
        }
        return suggestion;
    }

    public void cancelAutoComplete() {
        autocompleteTimer.cancel();
        autocompleteTimer = new Timer("AutocompleteTimer", true);
        isAutocompleteActive = false;

        if (autoCompletePopup.isVisible()) {
            autoCompletePopup.setVisible(false);
        }
    }


    public void triggerAutocomplete() {
        if (currentProvider == null || isAutocompleteActive) return;
        String contextText = getPlainText();
        if (contextText.length() < 20) {
            return; // skip if doc is too short
        }

        // Re-load how many suggestions we have
        int n = 3;
        try {
            n = Integer.parseInt(prefs.getPreference("numSuggestions", "3"));
            if (n < 1) n = 1;
            if (n > 10) n = 10;
        } catch (Exception ex) {
            n = 3;
        }

        currentSuggestions = new String[n];
        isAutocompleteActive = true;
        statusBar.setText("Generating suggestions...");

        // Kick off parallel tasks
        @SuppressWarnings("unchecked")
        CompletableFuture<String>[] futures = new CompletableFuture[n];
        for (int i = 0; i < n; i++) {
            final int variation = i + 1; // 1-based
            futures[i] = CompletableFuture.supplyAsync(() -> {
                try {
                    String prompt = AutocompletePromptManager.getPrompt(contextText, variation, prefs);
                    return currentProvider.generateCompletion(prompt);
                } catch (Exception ex) {
                    ex.printStackTrace();
                    return "";
                }
            });
        }

        int finalN = n;
        CompletableFuture.allOf(futures).thenRun(() -> {
            try {
                for (int i = 0; i < finalN; i++) {
                    String raw = futures[i].get().trim();
                    raw = cleanOverlap(contextText, raw);
                    raw = cleanupSuggestion(contextText, raw);
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


    private String getPlainText() {
        try {
            return getDocument().getText(0, getDocument().getLength());
        } catch (BadLocationException ex) {
            ex.printStackTrace();
            return "";
        }
    }

    /**
     * Thorough overlap detection, removing repeated leading words if they appear at the tail of the doc
     */
    private String cleanOverlap(String existingText, String suggestion) {
        suggestion = suggestion.trim();
        if (suggestion.isEmpty()) return suggestion;

        // 1) Overlap detection
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

        // 2) remove trailing ellipses
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
                JMenuItem item = new JMenuItem("ctrl+" + (i + 1) + ") " + s);
                item.setFocusable(false); // Ensure menu items do not steal focus
                item.addActionListener(e -> insertSuggestionAtCaret(suggestions[idx]));
                autoCompletePopup.add(item);
            }
        }
        if (validCount == 0) return;
        // Display popup near caret
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
        StyledDocument sdoc = (StyledDocument) getDocument();

        // Use the previous character’s attributes if possible:
        AttributeSet insertionAttrs;
        if (caretPos > 0) {
            Element charElem = sdoc.getCharacterElement(caretPos - 1);
            insertionAttrs = charElem.getAttributes();
        } else {
            // Fallback if we're at position 0
            insertionAttrs = getCharacterAttributes();
        }

        SimpleAttributeSet attrs = new SimpleAttributeSet(insertionAttrs);

        try {
            sdoc.insertString(caretPos, suggestion, attrs);

            aiCharCount += suggestion.length();
            setCaretPosition(caretPos + suggestion.length());

            // Close the popup
            if (autoCompletePopup.isVisible()) {
                autoCompletePopup.setVisible(false);
            }
            statusBar.setText("Suggestion inserted. AI chars: " + aiCharCount
                    + " / Human chars: " + humanCharCount);
            isDirty = true;
        } catch (BadLocationException ex) {
            ex.printStackTrace();
            statusBar.setText("Error inserting suggestion.");
        }
    }


    // --- Dirty-tracking and char counts ---
    public boolean isDirty() {
        return isDirty;
    }

    public void markClean() {
        isDirty = false;
    }

    public int getAICharCount() {
        return aiCharCount;
    }

    public int getHumanCharCount() {
        return humanCharCount;
    }

    public void setAICharCount(int n) {
        aiCharCount = n;
    }

    public void setHumanCharCount(int n) {
        humanCharCount = n;
    }

    public void resetCharacterCounts() {
        aiCharCount = 0;
        humanCharCount = 0;
    }

    // --- Formatting methods ---

    /**
     * Insert <h1> or <h2> around the selected text (or paragraph).
     */
    public void setHeadingLevel(int level) {
        try {
            HTMLDocument doc = (HTMLDocument) getDocument();
            int start = getSelectionStart();
            int end = getSelectionEnd();

            if (start == end) {
                // no selection => heading for entire paragraph
                Element para = doc.getParagraphElement(start);
                start = para.getStartOffset();
                end = para.getEndOffset();
            }

            String selectedText = doc.getText(start, end - start);
            String tag = (level == 1) ? "h1" : "h2";

            doc.remove(start, end - start);

            String html = "<" + tag + ">" + escapeHTML(selectedText) + "</" + tag + ">";
            ((HTMLEditorKit) getEditorKit()).insertHTML(doc, start, html, 0, 0, null);
            isDirty = true;
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    /**
     * Revert selected text or the current paragraph to normal text (removing <h1> or <h2>).
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

            doc.remove(start, end - start);

            String replaced = selectedHTML
                    .replaceAll("(?i)</?h1>", "")
                    .replaceAll("(?i)</?h2>", "");

            ((HTMLEditorKit) getEditorKit()).insertHTML(doc, start, escapeHTML(replaced), 0, 0, null);
            isDirty = true;
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    // B, I, U toggling

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

    private void toggleBold() {
        isDirty = true;
        MutableAttributeSet attr = new SimpleAttributeSet(getInputAttributes());
        boolean isBold = StyleConstants.isBold(attr);
        StyleConstants.setBold(attr, !isBold);
        setCharacterAttributes(attr, true);
    }

    private void toggleItalic() {
        isDirty = true;
        MutableAttributeSet attr = new SimpleAttributeSet(getInputAttributes());
        boolean isItalic = StyleConstants.isItalic(attr);
        StyleConstants.setItalic(attr, !isItalic);
        setCharacterAttributes(attr, true);
    }

    private void toggleUnderline() {
        isDirty = true;
        MutableAttributeSet attr = new SimpleAttributeSet(getInputAttributes());
        boolean isUnderline = StyleConstants.isUnderline(attr);
        StyleConstants.setUnderline(attr, !isUnderline);
        setCharacterAttributes(attr, true);
    }

    /**
     * If there's a selection, apply that font family to it.
     * Else toggle future text style.
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

    /**
     * If there's a selection, apply that size to it.
     * Else sets future text style.
     */
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

    // Strikethrough
    public void applyStrikethrough() {
        int start = getSelectionStart();
        int end = getSelectionEnd();
        if (start < end) {
            toggleOrApplyAttributeOnSelection(start, end, "strikethrough");
        } else {
            toggleStrikethrough();
        }
    }

    private void toggleStrikethrough() {
        isDirty = true;
        MutableAttributeSet attr = new SimpleAttributeSet(getInputAttributes());
        boolean isStrike = StyleConstants.isStrikeThrough(attr);
        StyleConstants.setStrikeThrough(attr, !isStrike);
        setCharacterAttributes(attr, true);
    }

    /**
     * Convert selected lines to ordered list (<ol><li>...).
     * If there's no selection, apply to the current paragraph.
     */
    public void applyOrderedList() {
        try {
            HTMLDocument doc = (HTMLDocument) getDocument();
            int start = getSelectionStart();
            int end = getSelectionEnd();
            if (start == end) {
                // no selection => entire paragraph
                Element para = doc.getParagraphElement(start);
                start = para.getStartOffset();
                end = para.getEndOffset();
            }

            String selectedText = doc.getText(start, end - start);
            // Remove from doc
            doc.remove(start, end - start);

            // Wrap in <ol><li>...</li></ol>
            String[] lines = selectedText.split("\n");
            StringBuilder sb = new StringBuilder("<ol>");
            for (String line : lines) {
                line = escapeHTML(line).trim();
                if (!line.isEmpty()) {
                    sb.append("<li>").append(line).append("</li>");
                }
            }
            sb.append("</ol>");

            ((HTMLEditorKit) getEditorKit()).insertHTML(doc, start, sb.toString(), 0, 0, null);
            isDirty = true;
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    /**
     * Convert selected lines to unordered list (<ul><li>...).
     * If no selection, apply to current paragraph.
     */
    public void applyUnorderedList() {
        try {
            HTMLDocument doc = (HTMLDocument) getDocument();
            int start = getSelectionStart();
            int end = getSelectionEnd();
            if (start == end) {
                // no selection => entire paragraph
                Element para = doc.getParagraphElement(start);
                start = para.getStartOffset();
                end = para.getEndOffset();
            }

            String selectedText = doc.getText(start, end - start);
            // Remove from doc
            doc.remove(start, end - start);

            // Wrap in <ul><li>...</li></ul>
            String[] lines = selectedText.split("\n");
            StringBuilder sb = new StringBuilder("<ul>");
            for (String line : lines) {
                line = escapeHTML(line).trim();
                if (!line.isEmpty()) {
                    sb.append("<li>").append(line).append("</li>");
                }
            }
            sb.append("</ul>");

            ((HTMLEditorKit) getEditorKit()).insertHTML(doc, start, sb.toString(), 0, 0, null);
            isDirty = true;
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    /**
     * Apply alignment to the selected paragraphs (or the paragraph containing the caret).
     * alignment should be one of StyleConstants.ALIGN_LEFT, ALIGN_CENTER, ALIGN_RIGHT, etc.
     */
    public void applyAlignment(int alignment) {
        isDirty = true;
        // We can do this via paragraph attributes:
        int start = getSelectionStart();
        int end = getSelectionEnd();
        if (start == end) {
            Element para = ((HTMLDocument) getDocument()).getParagraphElement(start);
            start = para.getStartOffset();
            end = para.getEndOffset();
        }
        SimpleAttributeSet sas = new SimpleAttributeSet();
        StyleConstants.setAlignment(sas, alignment);
        ((StyledDocument) getDocument()).setParagraphAttributes(start, end - start, sas, false);
    }

    /**
     * Extend toggleOrApplyAttributeOnSelection to handle strikethrough as well.
     */
    private void toggleOrApplyAttributeOnSelection(int start, int end, String attrType) {
        isDirty = true;
        StyledDocument sdoc = (StyledDocument) getDocument();
        boolean allSet = true;

        for (int pos = start; pos < end; pos++) {
            Element el = sdoc.getCharacterElement(pos);
            AttributeSet as = el.getAttributes();
            switch (attrType) {
                case "bold":
                    if (!StyleConstants.isBold(as)) {
                        allSet = false;
                    }
                    break;
                case "italic":
                    if (!StyleConstants.isItalic(as)) {
                        allSet = false;
                    }
                    break;
                case "underline":
                    if (!StyleConstants.isUnderline(as)) {
                        allSet = false;
                    }
                    break;
                case "strikethrough":
                    if (!StyleConstants.isStrikeThrough(as)) {
                        allSet = false;
                    }
                    break;
            }
            if (!allSet) break;
        }

        MutableAttributeSet newAttr = new SimpleAttributeSet();
        switch (attrType) {
            case "bold":
                StyleConstants.setBold(newAttr, !allSet);
                break;
            case "italic":
                StyleConstants.setItalic(newAttr, !allSet);
                break;
            case "underline":
                StyleConstants.setUnderline(newAttr, !allSet);
                break;
            case "strikethrough":
                StyleConstants.setStrikeThrough(newAttr, !allSet);
                break;
        }
        sdoc.setCharacterAttributes(start, end - start, newAttr, false);
    }

    private String escapeHTML(String str) {
        return str.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

}
