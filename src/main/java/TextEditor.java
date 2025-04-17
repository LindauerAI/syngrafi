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
import java.io.File;

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

    // Autocomplete trigger tracking
    private int consecutiveCharsTyped = 0;
    private long lastCharTypedTimestamp = 0;
    private static final long TYPING_RESET_THRESHOLD_MS = 1500; // Reset counter if pause > 1.5s

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
     * Override processKeyEvent to handle Enter/Backspace within lists.
     */
    @Override
    protected void processKeyEvent(KeyEvent e) {
        if (e.getID() == KeyEvent.KEY_PRESSED) {
            int caretPos = getCaretPosition();
            HTMLDocument doc = (HTMLDocument) getDocument();
            HTMLEditorKit kit = (HTMLEditorKit) getEditorKit();
            Element currentPara = doc.getParagraphElement(caretPos);
            Element parentLi = findParentElement(currentPara, HTML.Tag.LI);

            if (parentLi != null) {
                // Key pressed within an LI element
                if (e.getKeyCode() == KeyEvent.VK_ENTER) {
                    if (!e.isShiftDown()) {
                        // Handle regular Enter
                        try {
                            handleEnterInListItem(doc, kit, parentLi, caretPos);
                            e.consume(); // Consume the event
                            return;
                        } catch (Exception ex) {
                            System.err.println("Error handling Enter in list item: " + ex.getMessage());
                            // Allow default action as fallback on error
                        }
                    }
                    // Allow default action for Shift+Enter (should insert <br> via default kit behavior)
                
                } else if (e.getKeyCode() == KeyEvent.VK_BACK_SPACE) {
                     // Handle Backspace only if caret is at the very start of the LI content area
                     Element body = findParentElement(parentLi, HTML.Tag.BODY); // Find body to check document start
                     int liContentStartOffset = parentLi.getStartOffset() + parentLi.getName().length() + 2; // Approx start after <li> tag
                     
                     // More robust check for start: Is caret position <= offset after opening tag?
                     try {
                         String liStartText = doc.getText(parentLi.getStartOffset(), caretPos - parentLi.getStartOffset());
                         if (!liStartText.contains(">")) { // If caret is before the closing > of <li>
                            liContentStartOffset = caretPos + 1; // Adjust if needed, but caret is likely *at* content start
                         }
                     } catch (BadLocationException ex) { /* ignore */ }

                     if (caretPos <= liContentStartOffset || caretPos == parentLi.getStartOffset()) {
                        try {
                            handleBackspaceAtListItemStart(doc, kit, parentLi);
                            e.consume(); // Consume the event
                            return;
                        } catch (Exception ex) {
                            System.err.println("Error handling Backspace in list item: " + ex.getMessage());
                            // Allow default action as fallback on error
                        }
                     } // else: Allow default backspace within LI content
                }
            } // else: Key pressed outside LI, fall through to default handling
        } 
        // Default handling for other keys or if not consumed
        super.processKeyEvent(e); 
    }

    /** Handles Enter key press within a list item */
    private void handleEnterInListItem(HTMLDocument doc, HTMLEditorKit kit, Element liElement, int caretPos)
            throws BadLocationException, IOException {
        
        int liStart = liElement.getStartOffset();
        int liEnd = liElement.getEndOffset();
        Element listElement = liElement.getParentElement();
        int listElementStart = listElement.getStartOffset(); // Store original list start

        String liContent = doc.getText(liStart, liEnd - liStart);
        String textContent = liContent.replaceAll("<[^>]*>", "").trim();

        if (textContent.isEmpty()) {
            // --- Enter on an Empty List Item: Exit the list ---
            doc.remove(liStart, liEnd - liStart); // Remove the empty <li>
            
            // Check if list is now empty *after* removal (by checking element count)
            // Re-fetch list element if possible, using original start offset as anchor
            Element updatedListElement = doc.getCharacterElement(listElementStart).getParentElement();
            boolean listRemoved = false;
            int insertPPos = listElementStart; // Default insert position
            if (updatedListElement != null && updatedListElement.getName().matches("ol|ul") && updatedListElement.getElementCount() == 0) { 
                 doc.remove(updatedListElement.getStartOffset(), updatedListElement.getEndOffset() - updatedListElement.getStartOffset());
                 listRemoved = true;
            } else if (updatedListElement != null && updatedListElement.getName().matches("ol|ul")) {
                // List still exists, insert P *after* it
                 insertPPos = updatedListElement.getEndOffset();
            } // else: structure changed unexpectedly, insert at original list start

            kit.insertHTML(doc, insertPPos, "<p></p>", 0, 0, HTML.Tag.P);
            // Set caret just inside the new <p> tag
            setCaretPosition(insertPPos + "<p>".length());

        } else {
            // --- Enter on Non-Empty List Item: Split the item ---
            String htmlToMove = getHtmlFragment(doc, kit, caretPos, liEnd - 1); 
            int originalLengthToMove = (liEnd - 1) - caretPos;
            
            // Remove content to move *before* inserting new structure
            if (originalLengthToMove > 0) {
                doc.remove(caretPos, originalLengthToMove);
            }
            
            // Insert the closing tag for the current li, and the opening for the new one
            // Adjust insertion position slightly if we removed content
            int insertSplitPos = caretPos; 
            kit.insertHTML(doc, insertSplitPos, "</li><li>" + htmlToMove, 0, 1, HTML.Tag.LI);

            // Set caret at the beginning of the new list item's content
            // Position is after the inserted </li><li> tags
            setCaretPosition(insertSplitPos + "</li><li>".length()); 
        }
    }

    /** Handles Backspace key press at the start of a list item */
    private void handleBackspaceAtListItemStart(HTMLDocument doc, HTMLEditorKit kit, Element liElement)
            throws BadLocationException, IOException {
        Element listElement = liElement.getParentElement();
        int liIndex = listElement.getElementIndex(liElement.getStartOffset());
        int liStart = liElement.getStartOffset();
        int liEnd = liElement.getEndOffset();
        int listStartOffset = listElement.getStartOffset(); // Store original list start
        
        // Extract content *before* any removals
        String liInnerHtml = getElementHtml(doc, liElement, false);

        if (liIndex == 0) {
            // --- Backspace on the *first* list item: Convert to paragraph ---
            int currentCaretPos = listStartOffset + 1; // Expected caret pos in new P
            doc.remove(liStart, liEnd - liStart); // Remove the <li> element
            
            // Check if list is now empty *after* removal
            Element updatedListElement = doc.getCharacterElement(listStartOffset).getParentElement();
            boolean listRemoved = false;
            if (updatedListElement != null && updatedListElement.getName().matches("ol|ul") && updatedListElement.getElementCount() == 0) { 
                 doc.remove(updatedListElement.getStartOffset(), updatedListElement.getEndOffset() - updatedListElement.getStartOffset());
                 listRemoved = true;
            } // else: list still has items
            
            // Insert the content as a paragraph where the list started (or where the LI was if list remains)
            int insertPPos = listRemoved ? listStartOffset : liStart; 
            kit.insertHTML(doc, insertPPos, "<p>" + liInnerHtml + "</p>", 0, 0, HTML.Tag.P);
            setCaretPosition(insertPPos + 1); // Position inside the new P tag

        } else {
            // --- Backspace on a subsequent list item: Merge with previous ---
            Element prevLiElement = listElement.getElement(liIndex - 1);
            int prevLiEnd = prevLiElement.getEndOffset() - 1; // Position before closing tag </li>
            int caretTargetPos = prevLiEnd; // Target caret position after merge

            // Insert current content into previous LI *before* removing current LI
            if (!liInnerHtml.isEmpty()) {
                kit.insertHTML(doc, prevLiEnd, liInnerHtml, 0, 0, null); 
            }
            
            // Now remove the current LI element
            // Recalculate start/end as insertion might have changed offsets
            Element currentLiAgain = doc.getParagraphElement(caretTargetPos + liInnerHtml.length()).getParentElement(); // Try to re-find LI after insertion
            if (currentLiAgain != null && currentLiAgain.getName().equals("li")) { // Ensure it's the correct LI
                 doc.remove(currentLiAgain.getStartOffset(), currentLiAgain.getEndOffset() - currentLiAgain.getStartOffset());
            } else { 
                 // Fallback: Remove based on original offsets (might fail if offsets shifted too much)
                 System.err.println("Warning: Could not reliably find LI after merging, attempting removal with original offsets.");
                 doc.remove(liStart, liEnd - liStart);
            }

            // Set caret position to where the merge happened
            setCaretPosition(caretTargetPos);
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
    
    /** Helper to get the inner HTML content of an element */
    private String getElementHtml(HTMLDocument doc, Element el, boolean includeTag) throws BadLocationException, IOException {
        StringWriter sw = new StringWriter();
        int start = el.getStartOffset();
        int end = el.getEndOffset();
        if (!includeTag) {
            String fullText = doc.getText(start, end - start);
            int openTagEnd = fullText.indexOf('>');
            int closeTagStart = fullText.lastIndexOf('<');
            if (openTagEnd != -1 && closeTagStart != -1 && openTagEnd < closeTagStart) {
                start = start + openTagEnd + 1;
                end = start + closeTagStart - (openTagEnd + 1);
            } else {
                 return ""; // Cannot find tags, assume empty content
            }
        }
        start = Math.max(el.getStartOffset(), start);
        end = Math.min(el.getEndOffset(), end);
        if (start >= end) return "";

        HTMLEditorKit kit = (HTMLEditorKit) getEditorKit();
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
            // Stop searching if we hit the body or html tag without finding the target
            if (parent.getName().equals("body") || parent.getName().equals("html")) {
                 return null;
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
                // Check if the change was likely a single character type
                boolean isSingleCharInsert = (e.getLength() == 1);
                boolean isMultiCharInsert = (e.getLength() > 1);

                if (isSingleCharInsert) {
                    long now = System.currentTimeMillis();
                    if (now - lastCharTypedTimestamp > TYPING_RESET_THRESHOLD_MS) {
                        consecutiveCharsTyped = 0; // Reset on pause
                    }
                    consecutiveCharsTyped++;
                    lastCharTypedTimestamp = now;
                } else {
                    // Reset counter for multi-char inserts (paste, etc.) or complex changes
                    consecutiveCharsTyped = 0;
                }
                
                isDirty = true;

                // Schedule autocomplete only if 2+ chars typed recently
                if (consecutiveCharsTyped >= 2) { 
                    scheduleAutocomplete();
                } else {
                    // If fewer than 2 chars typed (or counter reset), cancel any pending/visible autocomplete
                    cancelAutoComplete(); 
                }
                updateStatusBarInfo();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                 isDirty = true;
                 consecutiveCharsTyped = 0; // Reset trigger count
                 cancelAutoComplete(); // Cancel autocomplete on backspace/delete
                 updateStatusBarInfo();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                isDirty = true;
                updateStatusBarInfo();
                // Reset autocomplete trigger on attribute change
                consecutiveCharsTyped = 0; 
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
        if (isAnyDialogVisible()) return; 
        if (isAutocompleteActive || currentProvider == null) return;

        int delay = 1000; // Default delay updated
        try {
            // Update default value used in getPreference call
            delay = Integer.parseInt(prefs.getPreference("autocompleteDelay", "1000")); 
            if (delay < 0) delay = 1000; // Ensure non-negative, use updated default
        } catch (Exception ex) {
            delay = 1000; // Use updated default on error
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
        // Use the standard HTMLEditorKit action fetched from the kit by name
        Action listAction = getActionByName("InsertOrderedList"); // Use String name
        if (listAction != null) {
            listAction.actionPerformed(new ActionEvent(this, ActionEvent.ACTION_PERFORMED, ""));
            isDirty = true;
            requestFocusInWindow(); // Ensure focus remains
        } else {
            System.err.println("Could not find InsertOrderedList action!");
        }
    }

    public void applyUnorderedList() {
        // Use the standard HTMLEditorKit action fetched from the kit by name
        Action listAction = getActionByName("InsertUnorderedList"); // Use String name
        if (listAction != null) {
            listAction.actionPerformed(new ActionEvent(this, ActionEvent.ACTION_PERFORMED, ""));
            isDirty = true;
            requestFocusInWindow(); // Ensure focus remains
        } else {
            System.err.println("Could not find InsertUnorderedList action!");
        }
    }

    /** Helper to get an action from the editor kit's action map by its name */
    private Action getActionByName(String name) {
        return getActionMap().get(name); // JTextComponent provides getActionMap
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
    public int countWords() {
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

    /**
     * Replaces the currently selected text with the provided rewritten text.
     * Tracks the change as AI-generated and adds it to the undo manager.
     * @param rewriteText The text to replace the selection with.
     */
    public void replaceSelectionWithRewrite(String rewriteText) {
        int start = getSelectionStart();
        int end = getSelectionEnd();

        if (start == end) {
            // No selection, maybe insert instead?
            // For now, just do nothing if there's no selection.
            statusBar.setText("Rewrite requires a text selection.");
            return;
        }

        try {
            StyledDocument sdoc = (StyledDocument) getDocument();
            // Get attributes from the start of the selection to apply to the new text
            AttributeSet attrs = sdoc.getCharacterElement(start).getAttributes();

            // Record edit for undo
            undoManager.addEdit(new ReplaceEdit(sdoc, start, end - start, rewriteText, attrs));

            // Perform replacement
            sdoc.remove(start, end - start);
            sdoc.insertString(start, rewriteText, attrs);

            // Update stats and state
            aiCharCount += rewriteText.length(); // Attribute change to AI
            isDirty = true;
            updateStatusBarInfo();
            statusBar.setText("Text rewritten.");

            // Optionally, select the newly inserted text
            // setSelectionStart(start);
            // setSelectionEnd(start + rewriteText.length());

        } catch (BadLocationException ex) {
            ex.printStackTrace();
            statusBar.setText("Error applying rewrite: " + ex.getMessage());
        }
    }

    // Helper class for Undoable Edits during replacement
    private static class ReplaceEdit extends javax.swing.undo.AbstractUndoableEdit {
        private final StyledDocument document;
        private final int offset;
        private final String oldText;
        private final String newText;
        private final AttributeSet attributes;

        ReplaceEdit(StyledDocument doc, int offs, int len, String replacement, AttributeSet attrs) {
            this.document = doc;
            this.offset = offs;
            this.newText = replacement;
            this.attributes = attrs;
            try {
                this.oldText = doc.getText(offs, len);
            } catch (BadLocationException e) {
                throw new RuntimeException("Error creating undo edit", e); // Should not happen
            }
        }

        @Override
        public void undo() throws CannotUndoException {
            super.undo();
            try {
                document.remove(offset, newText.length());
                document.insertString(offset, oldText, attributes); // Restore with original attributes
            } catch (BadLocationException e) {
                throw new CannotUndoException();
            }
        }

        @Override
        public void redo() throws CannotRedoException {
            super.redo();
            try {
                document.remove(offset, oldText.length());
                document.insertString(offset, newText, attributes); // Reapply with original attributes
            } catch (BadLocationException e) {
                throw new CannotRedoException();
            }
        }

        @Override
        public String getPresentationName() {
            return "Rewrite Selection";
        }
    }
}