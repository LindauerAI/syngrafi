import api.APIProvider;

import javax.swing.*;
import javax.swing.event.*;
import javax.swing.text.*;
import javax.swing.text.html.HTML;
import javax.swing.text.html.HTMLDocument;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.html.StyleSheet;
import javax.swing.undo.CannotRedoException;
import javax.swing.undo.CannotUndoException;
import javax.swing.undo.UndoManager;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.CompletableFuture;
import java.awt.event.ActionEvent;

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
    private int activeHeadingLevel = 0;

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
        styles.addRule("li { margin-left: 0; text-align: left; }");
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
        setupPasteAction();
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
     * Override processKeyEvent to handle Enter/Shift+Enter in lists and headings
     */
    @Override
    protected void processKeyEvent(KeyEvent e) {
        HTMLDocument doc = (HTMLDocument) getDocument();
        HTMLEditorKit kit = (HTMLEditorKit) getEditorKit();
        int caretPos = getCaretPosition();

        if (e.getID() == KeyEvent.KEY_PRESSED) {
            if (e.getKeyCode() == KeyEvent.VK_ENTER) {
                Element currentPara = doc.getParagraphElement(caretPos);
                Element parentLi = findParentElement(currentPara, HTML.Tag.LI);

                if (e.isShiftDown()) {
                    // Shift+Enter in list: insert <br>
                    if (parentLi != null) {
                        try {
                            kit.insertHTML(doc, caretPos, "<br>", 0, 0, HTML.Tag.BR);
                            e.consume();
                            return;
                        } catch (Exception ex) {
                            ex.printStackTrace();
                        }
                    }
                    // Allow default Shift+Enter outside lists
                } else {
                    // Enter in list: Let default action handle creating new <li>
                    // Check if we are exiting a heading
                    if (activeHeadingLevel != 0) {
                         SwingUtilities.invokeLater(this::revertHeadingMode);
                    } else if (isCaretInHeading()) {
                         SwingUtilities.invokeLater(this::setNormalText);
                    }
                    // Allow default Enter action (creates new <p> or <li>)
                }
            } else if (e.getKeyCode() == KeyEvent.VK_BACK_SPACE) {
                // Backspace at start of an empty, single list item: remove the whole list
                Element currentPara = doc.getParagraphElement(caretPos);
                if (currentPara.getStartOffset() == caretPos) { // Caret at the very beginning of the paragraph
                    Element parentLi = findParentElement(currentPara, HTML.Tag.LI);
                    if (parentLi != null) {
                        Element listElement = parentLi.getParentElement(); // Should be UL or OL
                        if (listElement != null && (listElement.getName().equals("ul") || listElement.getName().equals("ol"))) {
                             // Check if the LI is effectively empty
                             int liContentLength = parentLi.getEndOffset() - parentLi.getStartOffset() - 1; // -1 for the implied newline
                             boolean isEmpty = false;
                             if (liContentLength <= 0) {
                                 isEmpty = true;
                             } else {
                                 try {
                                     String liText = doc.getText(parentLi.getStartOffset(), liContentLength).trim();
                                     // Check if it contains only maybe a <br> tag placeholder or nothing
                                     isEmpty = liText.isEmpty() || liText.equals("<br>");
                                     if (!isEmpty) {
                                         // Check if it's an object replacement char (often used for empty elements by Swing)
                                         Element charElem = doc.getCharacterElement(parentLi.getStartOffset());
                                         AttributeSet attrs = charElem.getAttributes();
                                         if (attrs != null && attrs.getAttribute(StyleConstants.NameAttribute) == HTML.Tag.IMPLIED) {
                                             // It might be an "empty" paragraph within the li
                                             if (liContentLength == 1) isEmpty = true;
                                         }
                                     }
                                 } catch (BadLocationException ble) { /* ignore */ }
                             }

                             if (isEmpty && listElement.getElementCount() == 1) { // Is it the *only* LI in the list?
                                 try {
                                     doc.remove(listElement.getStartOffset(), listElement.getEndOffset() - listElement.getStartOffset());
                                     e.consume();
                                     return;
                                 } catch (BadLocationException ex) {
                                     ex.printStackTrace();
                                 }
                             }
                        }
                    }
                }
                // Allow default Backspace otherwise, caret positioning seems handled ok by default
            }
        }
        // Always call super for other keys or unhandled cases
        super.processKeyEvent(e);
    }

    /** Helper to find a parent element of a specific tag */
    private Element findParentElement(Element startElement, HTML.Tag tag) {
        Element parent = startElement.getParentElement();
        while (parent != null) {
            if (parent.getName().equals(tag.toString())) {
                return parent;
            }
            parent = parent.getParentElement();
        }
        return null;
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
            HTMLDocument doc = (HTMLDocument) getDocument();
            Element elem = doc.getParagraphElement(pos);
            String elementName = elem.getParentElement().getName();
            return elementName.equals("h1") || elementName.equals("h2");
        } catch (Exception ex) {
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

    private void scheduleAutocomplete() {
        if (isAnyDialogVisible()) return; // Don't schedule if a dialog is open
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
                SwingUtilities.invokeLater(() -> {
                    if (!isAnyDialogVisible()) { // Double check before triggering
                         triggerAutocomplete();
                    }
                });
            }
        }, delay);
    }

    private String cleanupSuggestion(String contextText, String suggestion) {
        suggestion = suggestion.trim();
        while (suggestion.startsWith("...")) {
            suggestion = suggestion.substring(3).trim();
        }
        while (suggestion.endsWith("...")) {
            suggestion = suggestion.substring(0, suggestion.length() - 3).trim();
        }
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
         if (isAnyDialogVisible()) return; // Don't trigger if a dialog is open
         if (isAutocompleteActive || currentProvider == null || getDocument().getLength() == 0) {
            return;
         }

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

        @SuppressWarnings("unchecked")
        CompletableFuture<String>[] futures = new CompletableFuture[n];
        for (int i = 0; i < n; i++) {
            final int variation = i + 1;
            futures[i] = CompletableFuture.supplyAsync(() -> {
                try {
                    int caret = getCaretPosition();
                    String full = getPlainText();
                    String before = full.substring(0, caret);
                    String after  = full.substring(caret);

                    String prompt = AutocompletePromptManager.getPrompt(before, after, variation, prefs);
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
                    raw = cleanOverlap(getPlainText(), raw);
                    raw = cleanupSuggestion(getPlainText(), raw);
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

    private String cleanOverlap(String existingText, String suggestion) {
        suggestion = suggestion.trim();
        if (suggestion.isEmpty()) return suggestion;

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
                item.setFocusable(false);
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
        StyledDocument sdoc = (StyledDocument) getDocument();

        AttributeSet insertionAttrs;
        if (caretPos > 0) {
            Element charElem = sdoc.getCharacterElement(caretPos - 1);
            insertionAttrs = charElem.getAttributes();
        } else {
            insertionAttrs = getCharacterAttributes();
        }

        SimpleAttributeSet attrs = new SimpleAttributeSet(insertionAttrs);

        try {
            sdoc.insertString(caretPos, suggestion, attrs);
            aiCharCount += suggestion.length();
            setCaretPosition(caretPos + suggestion.length());

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

    // --- Paste and match style ---
    public void pasteAndMatchStyle() {
        try {
            String clipboardText = (String) Toolkit.getDefaultToolkit()
                    .getSystemClipboard().getData(DataFlavor.stringFlavor);
            if (clipboardText != null && !clipboardText.isEmpty()) {
                int caretPos = getCaretPosition();
                StyledDocument sdoc = (StyledDocument) getDocument();

                // Get current attributes at caret position
                AttributeSet currentAttrs;
                if (caretPos > 0) {
                    Element charElem = sdoc.getCharacterElement(caretPos - 1);
                    currentAttrs = charElem.getAttributes();
                } else {
                    currentAttrs = getCharacterAttributes();
                }

                sdoc.insertString(caretPos, clipboardText, currentAttrs);
                humanCharCount += clipboardText.length();
                setCaretPosition(caretPos + clipboardText.length());
                isDirty = true;
            }
        } catch (Exception ex) {
            ex.printStackTrace();
            statusBar.setText("Error pasting text.");
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

    public void setHeadingLevel(int level) {
        int start = getSelectionStart();
        int end = getSelectionEnd();

        if (start == end) {
            if (activeHeadingLevel == level) {
                revertHeadingMode();
            } else {
                activeHeadingLevel = level;
                MutableAttributeSet attrs = new SimpleAttributeSet();
                if (level == 1) {
                    StyleConstants.setFontSize(attrs, 24);
                } else if (level == 2) {
                    StyleConstants.setFontSize(attrs, 18);
                }
                setCharacterAttributes(attrs, false);
                statusBar.setText("Heading mode H" + level + " enabled. Continue typing; press Enter to end heading mode.");
            }
        } else {
            try {
                HTMLDocument doc = (HTMLDocument) getDocument();
                String selectedText = doc.getText(start, end - start);
                String tag = (level == 1) ? "h1" : "h2";
                doc.remove(start, end - start);
                String html = "<" + tag + ">" + escapeHTML(selectedText) + "</" + tag + ">";
                ((HTMLEditorKit) getEditorKit()).insertHTML(doc, start, html, 0, 0, null);
                activeHeadingLevel = 0;
                statusBar.setText("Applied heading to selected text.");
                isDirty = true;
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        }
    }

    private void revertHeadingMode() {
        activeHeadingLevel = 0;
        MutableAttributeSet attrs = new SimpleAttributeSet();
        StyleConstants.setFontFamily(attrs, "Serif");
        StyleConstants.setFontSize(attrs, 12);
        setCharacterAttributes(attrs, true);
        statusBar.setText("Normal text mode enabled.");
    }

    public void setNormalText() {
        try {
            HTMLDocument doc = (HTMLDocument) getDocument();
            int start = getSelectionStart();
            int end = getSelectionEnd();

            // If no selection, operate on current paragraph
            if (start == end) {
                Element para = doc.getParagraphElement(start);
                start = para.getStartOffset();
                end = para.getEndOffset();
                if (start == end) return; // Prevent invalid remove
            }

            // Get text and check if it's in a heading
            String selectedHTML = doc.getText(start, end - start);
            if (!selectedHTML.trim().isEmpty()) {
                doc.remove(start, end - start);

                // Remove heading tags and preserve content
                String replaced = selectedHTML
                        .replaceAll("(?i)</?h[1-2]>", "")
                        .replaceAll("(?i)</?li>", "")
                        .replaceAll("(?i)</?ul>", "")
                        .replaceAll("(?i)</?ol>", "");

                // Insert as plain text with default attributes
                SimpleAttributeSet attrs = new SimpleAttributeSet();
                StyleConstants.setFontFamily(attrs, "Serif");
                StyleConstants.setFontSize(attrs, 12);
                ((HTMLEditorKit) getEditorKit()).insertHTML(doc, start, escapeHTML(replaced), 0, 0, null);

                isDirty = true;
                statusBar.setText("Formatting removed.");
            }
        } catch (Exception ex) {
            ex.printStackTrace();
            statusBar.setText("Error removing formatting.");
        }
    }

    // --- Formatting toggle methods ---

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
        setCharacterAttributes(attr, false); // false to preserve other attributes
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
        setCharacterAttributes(attr, false);
    }

    public void applyOrderedList() {
        HTMLEditorKit kit = (HTMLEditorKit) getEditorKit();
        HTMLDocument doc = (HTMLDocument) getDocument();
        // Fetch the action from the kit's action map
        Action listAction = null;
        for (Action a : kit.getActions()) {
            if (a.getValue(Action.NAME).equals("InsertOrderedList")) {
                listAction = a;
                break;
            }
        }

        if (listAction == null) {
            System.err.println("Could not find InsertOrderedList action!");
            return; // Cannot proceed without the action
        }

        int initialCaretPos = getCaretPosition();

        // Apply the action
        listAction.actionPerformed(new ActionEvent(this, ActionEvent.ACTION_PERFORMED, ""));

        // Find the list element potentially created or modified near the caret
        SwingUtilities.invokeLater(() -> { // Use invokeLater to allow document structure updates
            try {
                Element paragraphElement = doc.getParagraphElement(initialCaretPos);
                Element liElement = findParentElement(paragraphElement, HTML.Tag.LI);

                 if (liElement != null) {
                     // Set caret inside the first LI
                     setCaretPosition(liElement.getStartOffset());
                 } else {
                     // Fallback: Try finding the list element itself if LI wasn't found directly
                     Element listElement = findParentElement(paragraphElement, HTML.Tag.OL);
                     if (listElement != null && listElement.getElementCount() > 0) {
                          Element firstLi = listElement.getElement(0);
                          setCaretPosition(firstLi.getStartOffset());
                     } else {
                          // If still not found, maybe place caret back where it was or at end of doc change
                           setCaretPosition(initialCaretPos); // Revert position if list creation failed/was complex
                     }
                 }
                  requestFocusInWindow(); // Ensure editor gets focus back
            } catch (Exception e) {
                System.err.println("Error setting caret after applying ordered list: " + e.getMessage());
                 setCaretPosition(initialCaretPos); // Reset position on error
                 requestFocusInWindow();
            }
        });
    }

    public void applyUnorderedList() {
        HTMLEditorKit kit = (HTMLEditorKit) getEditorKit();
        HTMLDocument doc = (HTMLDocument) getDocument();
        // Fetch the action from the kit's action map
        Action listAction = null;
        for (Action a : kit.getActions()) {
            if (a.getValue(Action.NAME).equals("InsertUnorderedList")) {
                listAction = a;
                break;
            }
        }

        if (listAction == null) {
            System.err.println("Could not find InsertUnorderedList action!");
            return; // Cannot proceed without the action
        }

        int initialCaretPos = getCaretPosition();

         // Apply the action
        listAction.actionPerformed(new ActionEvent(this, ActionEvent.ACTION_PERFORMED, ""));

        // Find the list element potentially created or modified near the caret
        SwingUtilities.invokeLater(() -> { // Use invokeLater to allow document structure updates
             try {
                 Element paragraphElement = doc.getParagraphElement(initialCaretPos);
                 Element liElement = findParentElement(paragraphElement, HTML.Tag.LI);

                 if (liElement != null) {
                      // Set caret inside the first LI
                      setCaretPosition(liElement.getStartOffset());
                 } else {
                     // Fallback: Try finding the list element itself if LI wasn't found directly
                      Element listElement = findParentElement(paragraphElement, HTML.Tag.UL);
                      if (listElement != null && listElement.getElementCount() > 0) {
                          Element firstLi = listElement.getElement(0);
                          setCaretPosition(firstLi.getStartOffset());
                      } else {
                          // If still not found, maybe place caret back where it was or at end of doc change
                           setCaretPosition(initialCaretPos); // Revert position if list creation failed/was complex
                     }
                 }
                 requestFocusInWindow(); // Ensure editor gets focus back
             } catch (Exception e) {
                 System.err.println("Error setting caret after applying unordered list: " + e.getMessage());
                  setCaretPosition(initialCaretPos); // Reset position on error
                  requestFocusInWindow();
             }
        });
    }

    public void applyAlignment(int alignment) {
        isDirty = true;
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

    private void toggleOrApplyAttributeOnSelection(int start, int end, String attrType) {
        isDirty = true;
        StyledDocument sdoc = (StyledDocument) getDocument();
        boolean allSet = true;

        // Check if the attribute is applied to all characters in selection
        for (int pos = start; pos < end; pos++) {
            Element el = sdoc.getCharacterElement(pos);
            AttributeSet as = el.getAttributes();
            switch (attrType) {
                case "bold":
                    if (!StyleConstants.isBold(as)) allSet = false;
                    break;
                case "italic":
                    if (!StyleConstants.isItalic(as)) allSet = false;
                    break;
                case "underline":
                    if (!StyleConstants.isUnderline(as)) allSet = false;
                    break;
                case "strikethrough":
                    if (!StyleConstants.isStrikeThrough(as)) allSet = false;
                    break;
            }
            if (!allSet) break;
        }

        // Toggle the attribute: remove if all set, apply if not all set
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
        // Basic escaping for inserting plain text into HTML context if needed elsewhere
        // Correctly escape quotes within the Java string literals
        return str.replace("&", "&amp;")
                  .replace("<", "&lt;")
                  .replace(">", "&gt;")
                  .replace("\"", "&quot;") // Use \" to represent the double quote character
                  .replace("'", "&#39;");
    }

    // --- Custom Paste Action ---
    private void setupPasteAction() {
        Action defaultPasteAction = getActionMap().get(DefaultEditorKit.pasteAction);
        getActionMap().put(DefaultEditorKit.pasteAction, new CustomPasteAction(defaultPasteAction));
    }

    private class CustomPasteAction extends TextAction {
        private Action defaultPasteAction;

        public CustomPasteAction(Action defaultPasteAction) {
            super((String)DefaultEditorKit.pasteAction);
            this.defaultPasteAction = defaultPasteAction;
        }

        @Override
        public void actionPerformed(ActionEvent e) {
            JTextComponent target = getTextComponent(e);
            if (target != null && target.isEditable() && target.isEnabled()) {
                int caretPosBefore = target.getCaretPosition();
                int docLengthBefore = target.getDocument().getLength();

                // Execute default paste action
                if (defaultPasteAction != null) {
                    defaultPasteAction.actionPerformed(e);
                } else {
                    target.paste(); // Fallback if default action wasn't found
                }

                // Check document afterwards
                SwingUtilities.invokeLater(() -> { // Use invokeLater to ensure paste processing is complete
                    Document doc = target.getDocument();
                    int docLengthAfter = doc.getLength();
                    if (docLengthAfter > docLengthBefore) { // Content was actually pasted
                        try {
                            // Check if the very last character is a newline that might be superfluous
                            if (doc.getText(docLengthAfter - 1, 1).equals("\n")) {
                                // More sophisticated check: look at the element structure at the end
                                Element root = ((HTMLDocument)doc).getDefaultRootElement();
                                Element lastElement = root.getElement(root.getElementCount() - 1);
                                // Check if the last element is an empty paragraph added by the paste
                                // This check needs refinement based on how HTMLDocument represents newlines/paragraphs
                                // A simpler check might be to see if the pasted text itself ended in \n\n

                                // Simple check: Was the character *right before* the paste also a newline?
                                // If so, the paste might have added an extra one.
                                if (caretPosBefore > 0 && caretPosBefore <= docLengthBefore) {
                                    String charBeforePaste = doc.getText(caretPosBefore-1, 1);
                                    String lastPastedChar = doc.getText(docLengthAfter-1, 1);
                                    if (charBeforePaste.equals("\n") && lastPastedChar.equals("\n")) {
                                        // Potentially remove the last newline, but be careful not to remove intended ones
                                        // Let's test without removal first, as this is complex to get right universally.
                                        // Consider trimming clipboard data *before* pasting if possible,
                                        // but that might alter intended formatting.

                                        // For now, let's log if we detect a potential extra newline
                                         System.out.println("Potential extra newline detected after paste.");
                                         // To remove: doc.remove(docLengthAfter - 1, 1);
                                    }
                                }
                            }
                        } catch (BadLocationException ex) {
                            // Error checking end of document
                        }
                    }
                });
            }
        }
    }

     // --- Helper for Autocomplete Suppression ---
     private boolean isAnyDialogVisible() {
         Window owner = SwingUtilities.getWindowAncestor(this);
         if (owner != null) {
             for (Window ownedWindow : owner.getOwnedWindows()) {
                 // Check if it's a JDialog, visible, and potentially modal (though visible is often enough)
                 if (ownedWindow instanceof JDialog && ownedWindow.isVisible()) {
                     // Could add check: && ((JDialog) ownedWindow).isModal() if needed
                     return true;
                 }
             }
         }
         // Also check for OptionPanes which might not be direct children
         for (Window window : Window.getWindows()) {
              if (window instanceof JDialog && window.isVisible() && window.getOwner() == owner) {
                   // This might catch dialogs like JOptionPane shown relative to the main frame
                   if (window.isFocused() || window.isFocusOwner()) return true;
              }
         }
         return false;
     }
}