import api.APIProvider;
import io.github.geniot.jortho.FileUserDictionary;
import io.github.geniot.jortho.SpellChecker;

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
import java.io.IOException;
import java.io.StringWriter;
import javax.swing.text.Highlighter;
import javax.swing.text.DefaultHighlighter;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import java.net.URL;
import java.net.MalformedURLException;

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
    private PreferencesManager prefs;
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
        styles.addRule("body { font-size: 12pt; font-family: Georgia; }");
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
        initializeJOrtho(); // Initialize JOrtho spellchecker
    }

    private void initializeJOrtho() {
        try {
            // Define the base URL for dictionaries (using classpath)
            // Assumes a 'dictionaries' folder in resources
            URL dictionaryUrl = getClass().getClassLoader().getResource("dictionaries/");
            if (dictionaryUrl == null) {
                System.err.println("Error: Could not find 'dictionaries' folder in classpath/resources.");
                 statusBar.setText("Spellcheck dictionary path not found.");
                return;
            }

            // Set JOrtho properties (including dictionary path)
            SpellChecker.setUserDictionaryProvider(new FileUserDictionary());
            // Register the English dictionary (adjust file name if needed)
             // JOrtho might automatically find .ortho or .dic/.aff files
             SpellChecker.registerDictionaries(null, "en"); 
             // Or specify exact file: SpellChecker.registerDictionary(dictionaryUrl.toString(), "dictionary_en.ortho");

            // Register this text component with the SpellChecker
            SpellChecker.register(this);

            // Optional: Enable the right-click suggestions menu
            // SpellChecker.enableChecker(this, true); // Might conflict with other popups, enable if needed

            System.out.println("JOrtho initialized.");
             statusBar.setText("Spellchecker initialized.");

        } catch (Exception e) {
            System.err.println("Error initializing JOrtho: " + e.getMessage());
            e.printStackTrace();
            statusBar.setText("Spellcheck initialization failed.");
            // JOrtho might be disabled if setup fails
        }
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
            // --- Enter Key Handling ---
            if (e.getKeyCode() == KeyEvent.VK_ENTER) {
                Element currentPara = doc.getParagraphElement(caretPos);
                Element parentLi = findParentElement(currentPara, HTML.Tag.LI);

                if (parentLi != null) {
                    // ENTER pressed within an LI element
                    try {
                        if (e.isShiftDown()) {
                            // Shift+Enter: Insert <br>
                            kit.insertHTML(doc, caretPos, "<br>", 0, 0, HTML.Tag.BR);
                        } else {
                            // Regular Enter:
                            handleEnterInListItem(doc, kit, parentLi, caretPos);
                        }
                        e.consume(); // Consume the event as we handled it
                        return;
                    } catch (Exception ex) {
                        ex.printStackTrace();
                        // Let super handle if error occurs?
                    }
                } else {
                    // ENTER pressed outside a list item
                    // Check if we are exiting a heading
                if (activeHeadingLevel != 0) {
                    SwingUtilities.invokeLater(this::revertHeadingMode);
                         // Default action (insert paragraph) will execute after revert
                } else if (isCaretInHeading()) {
                    SwingUtilities.invokeLater(this::setNormalText);
                         // Default action will execute after setting normal
                    }
                    // Allow default Enter action (creates new <p>) if not in list or heading
                }
            } 
            // --- Backspace Key Handling ---
            else if (e.getKeyCode() == KeyEvent.VK_BACK_SPACE) {
                Element currentPara = doc.getParagraphElement(caretPos);
                Element parentLi = findParentElement(currentPara, HTML.Tag.LI);

                if (parentLi != null && caretPos == parentLi.getStartOffset()) {
                     // BACKSPACE pressed at the very beginning of an LI element
                     try {
                         handleBackspaceAtListItemStart(doc, kit, parentLi);
                         e.consume(); // Consume the event as we handled it
                         return;
                     } catch (Exception ex) {
                         ex.printStackTrace();
                         // Let super handle if error occurs?
                     }
                } 
                 // Allow default Backspace action if not at the start of LI or if outside a list
            }
        }
        // Always call super for other keys or unhandled/errored cases
        super.processKeyEvent(e);
    }

    /** Handles Enter key press within a list item */
    private void handleEnterInListItem(HTMLDocument doc, HTMLEditorKit kit, Element liElement, int caretPos)
            throws BadLocationException, IOException {

        int liStart = liElement.getStartOffset();
        int liEnd = liElement.getEndOffset();
        String liContent = doc.getText(liStart, liEnd - liStart);

        // Check if the list item is effectively empty (might contain formatting tags or just whitespace)
        String textContent = liContent.replaceAll("<[^>]*>", "").trim();

        if (textContent.isEmpty()) {
            // --- Enter on an Empty List Item: Exit the list ---
            Element listElement = liElement.getParentElement(); // UL or OL
            int listStart = listElement.getStartOffset();
            int listEnd = listElement.getEndOffset();

            // Remove the empty <li>
            doc.remove(liStart, liEnd - liStart);

            // Check if the list itself is now empty
            if (listElement.getElementCount() <= 1) { // <= 1 because structure updates are pending
                doc.remove(listStart, listEnd - listStart); // Remove the list tags
                 // Insert a new paragraph where the list was
                 kit.insertHTML(doc, listStart, "<p></p>", 0, 0, HTML.Tag.P);
                 setCaretPosition(listStart + "<p>".length()); // Position in new P
            } else {
                // List not empty, insert paragraph *after* the list
                kit.insertHTML(doc, listEnd, "<p></p>", 0, 0, HTML.Tag.P);
                setCaretPosition(listEnd + "<p>".length()); // Position in new P
            }
        } else {
            // --- Enter on Non-Empty List Item: Split the item ---
            // Extract content from caret position to the end of the LI
            String htmlToMove = getHtmlFragment(doc, kit, caretPos, liEnd - 1); // Get HTML fragment to move

            // Remove the content that will be moved
            if (caretPos < liEnd -1) {
                 doc.remove(caretPos, (liEnd - 1) - caretPos);
            }

            // Insert new <li> tag after the current one
            // Using insertHTML at the *end* of the current LI's content seems most reliable
            int insertPos = liElement.getEndOffset() - 1; // Position before closing </li>
            kit.insertHTML(doc, insertPos, "</li><li>" + htmlToMove, 0, 1, HTML.Tag.LI);

            // Find the newly created LI element (heuristic: next element after original LI end)
            Element newLi = doc.getParagraphElement(liElement.getEndOffset() + 1); // Approximate position
             if (newLi != null && newLi.getName().equals("li")) {
                 setCaretPosition(newLi.getStartOffset()); // Position caret at start of new LI
             } else {
                 // Fallback if new LI not found easily
                 setCaretPosition(liElement.getEndOffset()); // Place caret after original LI
             }
        }
    }

    /** Handles Backspace key press at the start of a list item */
    private void handleBackspaceAtListItemStart(HTMLDocument doc, HTMLEditorKit kit, Element liElement)
            throws BadLocationException, IOException {

        Element listElement = liElement.getParentElement();
        int liIndex = listElement.getElementIndex(liElement.getStartOffset());

        if (liIndex == 0) {
            // --- Backspace on the *first* list item ---
            // Convert this item back to a paragraph
            String liInnerHtml = getElementHtml(doc, liElement, false);
            doc.remove(liElement.getStartOffset(), liElement.getEndOffset() - liElement.getStartOffset());

            // If this was the ONLY item, remove the list tag too
            if (listElement.getElementCount() <= 1) {
                doc.remove(listElement.getStartOffset(), listElement.getEndOffset() - listElement.getStartOffset());
                // Insert the content as a paragraph where the list started
                kit.insertHTML(doc, listElement.getStartOffset(), "<p>" + liInnerHtml + "</p>", 0, 0, HTML.Tag.P);
                setCaretPosition(listElement.getStartOffset() + 1); // Position inside the new P
            } else {
                // List has other items, insert the content as a paragraph *before* the list
                kit.insertHTML(doc, listElement.getStartOffset(), "<p>" + liInnerHtml + "</p>", 0, 0, HTML.Tag.P);
                 setCaretPosition(listElement.getStartOffset() + 1); // Position inside the new P
            }
        } else {
            // --- Backspace on a subsequent list item: Merge with previous item ---
            Element prevLiElement = listElement.getElement(liIndex - 1);
            int prevLiEnd = prevLiElement.getEndOffset() - 1; // Before closing tag

            // Get content of current LI and append it to the previous one
            String currentLiInnerHtml = getElementHtml(doc, liElement, false);
            kit.insertHTML(doc, prevLiEnd, currentLiInnerHtml, 0, 0, null);

            // Remove the current LI element
            doc.remove(liElement.getStartOffset(), liElement.getEndOffset() - liElement.getStartOffset());

            // Set caret position to where the merge happened
            setCaretPosition(prevLiEnd);
        }
    }

    /** Helper to get an HTML fragment between two positions */
    private String getHtmlFragment(HTMLDocument doc, HTMLEditorKit kit, int start, int end)
            throws BadLocationException, IOException {
        if (start >= end) return "";
        StringWriter sw = new StringWriter();
        kit.write(sw, doc, start, end - start);
        return sw.toString();
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
                updateStatusBarInfo(); // Update status bar on insert
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                isDirty = true;
                scheduleAutocomplete();
                updateStatusBarInfo(); // Update status bar on remove
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                isDirty = true;
                 // Attribute changes (like bold) might not affect word count
                 // but we can update status bar anyway for consistency or if needed later
                 updateStatusBarInfo();
            }
        });
    }

    /** Updates the status bar with current info (word count, etc.) */
    public void updateStatusBarInfo() {
        int words = countWords();
        int ai = getAICharCount();
        int human = getHumanCharCount();
        // Keep the status bar text concise
        statusBar.setText(String.format("Words: %d | AI Chars: %d | Human Chars: %d", words, ai, human));
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
            boolean errorOccurred = false;
            String errorMessage = "Ready"; // Default status
            try {
                for (int i = 0; i < finalN; i++) {
                    try {
                        String raw = futures[i].get().trim(); // Get result for each future
                         // Check for common API error messages (example for Gemini quota)
                         if (raw.contains("exceeded your current quota")) {
                             System.err.println("API Error detected: " + raw);
                             errorMessage = "Autocomplete failed: API Quota Exceeded";
                             errorOccurred = true;
                             currentSuggestions[i] = null; // Clear potential error message suggestion
                             continue; // Skip processing this suggestion
                         }
                         // Add checks for other known error patterns if necessary

                         raw = cleanOverlap(getPlainText(), raw);
                         raw = cleanupSuggestion(getPlainText(), raw);
                    currentSuggestions[i] = raw;
                    } catch (Exception futureEx) {
                        // Handle exception fetching individual future result
                        System.err.println("Error fetching suggestion future: " + futureEx.getMessage());
                        futureEx.printStackTrace();
                        errorMessage = "Autocomplete failed: Error fetching suggestion";
                        errorOccurred = true;
                        currentSuggestions[i] = null;
                    }
                }
            } catch (Exception ex) {
                 // Handle general exceptions during the completion phase
                 System.err.println("Error processing suggestions: " + ex.getMessage());
                ex.printStackTrace();
                 errorMessage = "Autocomplete failed: Processing Error";
                 errorOccurred = true;
            }

            String finalErrorMessage = errorMessage; // Effectively final for lambda
            boolean finalErrorOccurred = errorOccurred; // Effectively final

            SwingUtilities.invokeLater(() -> {
                if (!finalErrorOccurred) {
                    // Only show popup if no errors occurred and we have valid suggestions
                    boolean hasValidSuggestions = false;
                    for(String s : currentSuggestions) {
                        if (s != null && !s.trim().isEmpty()) {
                            hasValidSuggestions = true;
                            break;
                        }
                    }
                    if (hasValidSuggestions) {
                showAutoCompletePopup(currentSuggestions);
                    }
                }
                isAutocompleteActive = false;
                statusBar.setText(finalErrorMessage);
            });
        }).exceptionally(ex -> { // Catch exceptions from CompletableFuture.allOf itself
            System.err.println("Error waiting for autocomplete futures: " + ex.getMessage());
            ex.printStackTrace();
            SwingUtilities.invokeLater(() -> {
                statusBar.setText("Autocomplete failed: Network/API Error");
                isAutocompleteActive = false;
                cancelAutoComplete(); // Ensure popup is hidden etc.
            });
            return null;
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
        StyleConstants.setFontFamily(attrs, "Georgia");
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
                StyleConstants.setFontFamily(attrs, "Georgia");
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
        applyList(HTML.Tag.OL);
    }

    public void applyUnorderedList() {
        applyList(HTML.Tag.UL);
    }

    /** Generic method to apply/toggle lists based on selection, using manual HTML manipulation */
    private void applyList(HTML.Tag listTag) {
        HTMLEditorKit kit = (HTMLEditorKit) getEditorKit();
            HTMLDocument doc = (HTMLDocument) getDocument();
            int start = getSelectionStart();
            int end = getSelectionEnd();
        int initialCaretPos = getCaretPosition();

        // Determine the range of paragraphs affected by the selection
        Element startPara = doc.getParagraphElement(start);
        Element endPara = doc.getParagraphElement(end == start ? start : end - 1); // Handle caret position vs selection end

        // Check if the *entire* affected range is already within a list of the *same* type
        boolean isAlreadyList = checkSelectionIsListType(doc, startPara, endPara, listTag);

        try {
            if (isAlreadyList) {
                // --- TOGGLE OFF: Convert selected list items back to paragraphs ---
                convertListItemsToParagraphs(doc, kit, startPara, endPara, listTag);
                statusBar.setText("Removed list formatting.");
            } else {
                // --- TOGGLE ON: Convert selected paragraphs to list items ---
                convertParagraphsToListItems(doc, kit, startPara, endPara, listTag);
                statusBar.setText("Applied list formatting.");
            }
                isDirty = true;
            // Caret positioning might need adjustment after major structure changes
            // Placing it at the start of the modified section is a safe default
            // More sophisticated positioning could be added later if needed.
             SwingUtilities.invokeLater(() -> setCaretPosition(startPara.getStartOffset()));

        } catch (BadLocationException | IOException ex) {
            ex.printStackTrace();
            statusBar.setText("Error applying list formatting: " + ex.getMessage());
        }
    }

    /** Checks if all paragraphs from startPara to endPara are LI elements within the specified listTag */
    private boolean checkSelectionIsListType(HTMLDocument doc, Element startPara, Element endPara, HTML.Tag listTag) {
        Element commonListParent = null;
        Element currentPara = startPara;

        while (currentPara != null && currentPara.getStartOffset() <= endPara.getStartOffset()) {
            Element li = findParentElement(currentPara, HTML.Tag.LI);
            if (li == null) return false; // Not a list item

            Element list = findParentElement(li, listTag);
            if (list == null) return false; // Not in the correct list type

            if (commonListParent == null) {
                commonListParent = list; // First list found
            } else if (commonListParent != list) {
                return false; // Selection spans different lists
            }

            if (currentPara.getEndOffset() >= doc.getLength() || currentPara == endPara) break;
            currentPara = doc.getParagraphElement(currentPara.getEndOffset()); // Move to the next paragraph
        }
        return commonListParent != null; // True if all were LIs in the same listTag parent
    }

    /** Converts P elements within the range into LI elements of the specified listTag */
    private void convertParagraphsToListItems(HTMLDocument doc, HTMLEditorKit kit, Element startPara, Element endPara, HTML.Tag listTag)
            throws BadLocationException, IOException {

        int rangeStart = startPara.getStartOffset();
        // Ensure end offset covers the *entire* last paragraph selected
        int rangeEnd = endPara.getEndOffset(); 

        // Extract the inner HTML of all paragraphs within the determined range
        StringBuilder contentToConvert = new StringBuilder();
        Element currentPara = startPara;
        while (currentPara != null && currentPara.getStartOffset() < rangeEnd) {
             // Ensure we don't go beyond the intended end paragraph
             if (currentPara.getStartOffset() >= endPara.getEndOffset()) break;
             
             String pInnerHtml = getElementHtml(doc, currentPara, false); // Get content only
             // Wrap content in LI tags
             contentToConvert.append("<li>").append(pInnerHtml.isEmpty() ? " " : pInnerHtml).append("</li>");

            // Move to the next paragraph element logically
            int nextParaOffset = currentPara.getEndOffset();
            if (nextParaOffset >= doc.getLength()) break;
            currentPara = doc.getParagraphElement(nextParaOffset);
             // Safety check for loops or unexpected structure
             if (currentPara == null || currentPara.getStartOffset() < nextParaOffset) break; 
        }

        // Build the final list HTML
        String listHtml = "<" + listTag.toString() + ">" + contentToConvert.toString() + "</" + listTag.toString() + ">";

        // Perform a single replacement: remove original range, insert new list
        if (rangeEnd > rangeStart) { // Ensure valid range
             doc.remove(rangeStart, rangeEnd - rangeStart);
             kit.insertHTML(doc, rangeStart, listHtml, 0, 0, listTag);
            } else {
            // If range is zero/negative (e.g., empty selection at start), just insert an empty list
             kit.insertHTML(doc, rangeStart, "<" + listTag.toString() + "><li> </li></" + listTag.toString() + ">", 0, 0, listTag);
        }
    }

    /** Converts LI elements within the range back to P elements */
    private void convertListItemsToParagraphs(HTMLDocument doc, HTMLEditorKit kit, Element startLiPara, Element endLiPara, HTML.Tag listTag)
            throws BadLocationException, IOException {

        Element listElement = findParentElement(startLiPara, listTag);
        if (listElement == null) return; 

        // Determine the actual start and end LI elements based on the paragraph elements
        Element startLi = findParentElement(startLiPara, HTML.Tag.LI);
        Element endLi = findParentElement(endLiPara, HTML.Tag.LI);

        if (startLi == null || endLi == null || findParentElement(startLi, listTag) != listElement || findParentElement(endLi, listTag) != listElement) {
             System.err.println("Error: Selection for list removal is invalid or spans multiple lists.");
             return;
        }

        // Define the exact range to replace (from start of first LI to end of last LI)
        int rangeStart = startLi.getStartOffset();
        int rangeEnd = endLi.getEndOffset();

        // Extract inner HTML of all selected LI elements
        StringBuilder contentToConvert = new StringBuilder();
        Element currentLi = startLi;
        while (currentLi != null && currentLi.getStartOffset() < rangeEnd) {
             // Ensure we don't go beyond the intended end LI
             if (currentLi.getStartOffset() >= endLi.getEndOffset()) break;

             String liInnerHtml = getElementHtml(doc, currentLi, false); // Get content only
             // Wrap content in P tags
             contentToConvert.append("<p>").append(liInnerHtml.isEmpty() ? " " : liInnerHtml).append("</p>");

             // Move to the next LI element logically
             int nextLiOffset = currentLi.getEndOffset();
             if (nextLiOffset >= listElement.getEndOffset()) break; // Stop if we reach end of parent list
             
             // Find the next element - might not be immediately adjacent paragraph
             Element nextElement = doc.getCharacterElement(nextLiOffset); 
             if(nextElement == null) break; // Should not happen
             
             Element nextLi = findParentElement(nextElement, HTML.Tag.LI);
             // Ensure the next LI is still within the same parent list
             if (nextLi == null || findParentElement(nextLi, listTag) != listElement) break;
             
              // Safety check for loops
              if (nextLi.getStartOffset() <= currentLi.getStartOffset()) break;
              
             currentLi = nextLi;
        }

        // Perform a single replacement: remove original range, insert new paragraphs
        if (rangeEnd > rangeStart) { // Ensure valid range
             doc.remove(rangeStart, rangeEnd - rangeStart);
             if (contentToConvert.length() > 0) {
                kit.insertHTML(doc, rangeStart, contentToConvert.toString(), 0, 0, HTML.Tag.P);
             }
        } 
        // If range is invalid, do nothing
    }

    /** Helper to get the HTML content of an element */
    private String getElementHtml(HTMLDocument doc, Element el, boolean includeTag) throws BadLocationException, IOException {
        StringWriter sw = new StringWriter();
        int start = el.getStartOffset();
        int end = el.getEndOffset();
        if (!includeTag) {
             // Try to get offsets inside the tag
             start++; // Skip opening tag char like '>' (approximate)
             end--;   // Skip closing tag char like '<' (approximate)
             // Find actual content boundaries more accurately
             String fullText = doc.getText(el.getStartOffset(), el.getEndOffset() - el.getStartOffset());
             int openTagEnd = fullText.indexOf('>');
             int closeTagStart = fullText.lastIndexOf('<');
             if(openTagEnd != -1 && closeTagStart != -1 && openTagEnd < closeTagStart) {
                 start = el.getStartOffset() + openTagEnd + 1;
                 end = el.getStartOffset() + closeTagStart;
             }
        }
        // Ensure start and end are valid
        start = Math.max(el.getStartOffset(), start);
        end = Math.min(el.getEndOffset(), end);
        if (start >= end) return ""; // No inner content

        HTMLEditorKit kit = (HTMLEditorKit) getEditorKit();
        kit.write(sw, doc, start, end - start);
        return sw.toString();
    }

    /** Helper to find the lowest common ancestor element in the document tree */
    private Element findLowestCommonAncestor(Element e1, Element e2) {
        if (e1 == null || e2 == null) return null;
        java.util.List<Element> path1 = getPathToRoot(e1);
        java.util.List<Element> path2 = getPathToRoot(e2);

        Element ancestor = null;
        int i = 0;
        while (i < path1.size() && i < path2.size() && path1.get(i) == path2.get(i)) {
            ancestor = path1.get(i);
            i++;
        }
        return ancestor;
    }

    /** Helper to get the path from an element to the root */
    private java.util.List<Element> getPathToRoot(Element e) {
        java.util.List<Element> path = new java.util.ArrayList<>();
        while (e != null) {
            path.add(0, e); // Add at the beginning to build path from root
            e = e.getParentElement();
        }
        return path;
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
                String tempTextBeforePaste = ""; // Temporary variable
                try {
                     // Get text around caret before paste for context
                     tempTextBeforePaste = target.getDocument().getText(0, docLengthBefore);
                } catch (BadLocationException ex) { /* ignore */ }
                final String textBeforePaste = tempTextBeforePaste; // Final variable for lambda

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
                    int caretPosAfter = target.getCaretPosition();
                    int pastedLength = docLengthAfter - docLengthBefore;

                    if (pastedLength > 0) { // Content was actually pasted
                        try {
                             // Check for unwanted leading newline added *by* the paste operation
                             // This happens if caret was at line start AND first char pasted is newline
                             boolean caretWasAtLineStart = (caretPosBefore == 0 ||
                                     textBeforePaste.charAt(caretPosBefore - 1) == '\n');

                             if (caretWasAtLineStart && pastedLength >= 1) {
                                 String firstPastedChar = doc.getText(caretPosBefore, 1);
                                 if (firstPastedChar.equals("\n")) {
                                     // Check if the actual clipboard content *started* with a newline
                                     // If not, the paste action likely added it.
                                     try {
                                         String clipboardText = (String) Toolkit.getDefaultToolkit()
                                                 .getSystemClipboard().getData(DataFlavor.stringFlavor);
                                         if (clipboardText != null && !clipboardText.startsWith("\n") && !clipboardText.startsWith("\r\n")) {
                                             // Likely an unwanted leading newline, remove it
                                             doc.remove(caretPosBefore, 1);
                                             System.out.println("Removed likely unwanted leading newline from paste.");
                                             // Caret position might need adjustment after removal, but default seems okay.
                                         }
                                     } catch (Exception clipEx) { /* Ignore clipboard access issues */ }
                                 }
                             }

                            // Original check for trailing newline (kept for reference/logging)
                            if (doc.getText(docLengthAfter - 1, 1).equals("\n")) {
                                Element root = ((HTMLDocument)doc).getDefaultRootElement();
                                Element lastElement = root.getElement(root.getElementCount() - 1);
                                // ... (rest of trailing newline check) ...
                                if (caretPosBefore > 0 && caretPosBefore <= docLengthBefore) {
                                    String charBeforePaste = doc.getText(caretPosBefore-1, 1);
                                    String lastPastedChar = doc.getText(docLengthAfter-1, 1);
                                    if (charBeforePaste.equals("\n") && lastPastedChar.equals("\n")) {
                                         System.out.println("Potential extra *trailing* newline detected after paste.");
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

    /** Calculates word count from the editor's HTML content */
    private int countWords() {
         String htmlText = getText(); // Get current HTML text
         // Regex to remove HTML tags
         String textOnly = htmlText.replaceAll("<[^>]+>", " ");
         // Replace non-breaking spaces and trim
         textOnly = textOnly.replace("&nbsp;", " ").trim();
         // Remove extra whitespace sequences
         textOnly = textOnly.replaceAll("\\s+", " "); 
         if (textOnly.isEmpty()) return 0;
         // Split by whitespace to count words
         return textOnly.split("\\s+").length;
    }

    /** Removes all formatting (bold, italic, lists, etc.) from the current selection */
    public void clearFormatting() {
        int start = getSelectionStart();
        int end = getSelectionEnd();

        if (start == end) {
            // No selection, maybe clear formatting for the current paragraph?
            // For now, let's only act on selections.
            statusBar.setText("Select text to clear formatting.");
            return;
        }

        try {
            HTMLDocument doc = (HTMLDocument) getDocument();
            String selectedText = doc.getText(start, end - start);

            // Replace the selection with its plain text equivalent
            // First remove the original formatted content
            doc.remove(start, end - start);
            
            // Prepare default attributes (simple paragraph, default font)
            SimpleAttributeSet attrs = new SimpleAttributeSet();
            StyleConstants.setFontFamily(attrs, "Georgia"); // Match default font
            StyleConstants.setFontSize(attrs, 12); // Match default size
            // Ensure basic paragraph structure if needed
            // HTMLEditorKit might handle this when inserting plain text.
            
            // Insert plain text using HTMLEditorKit to handle paragraph structure
            HTMLEditorKit kit = (HTMLEditorKit) getEditorKit();
            // Escape HTML in the plain text before inserting to prevent misinterpretation
            kit.insertHTML(doc, start, escapeHTML(selectedText), 0, 0, null); 
            
            isDirty = true;
            statusBar.setText("Formatting cleared from selection.");
            updateStatusBarInfo(); // Update word count etc.

        } catch (BadLocationException | IOException ex) {
            ex.printStackTrace();
            statusBar.setText("Error clearing formatting: " + ex.getMessage());
        }
    }
}