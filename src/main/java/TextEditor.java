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
import javax.swing.undo.AbstractUndoableEdit;
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
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import java.net.URL;
import java.net.MalformedURLException;
import java.io.File;
import javax.swing.InputMap;
import javax.swing.ActionMap;
import javax.swing.KeyStroke;
import javax.swing.JComponent;
import javax.swing.event.PopupMenuListener;
import javax.swing.event.PopupMenuEvent;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    private String[] currentAutocompleteSuggestions;

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

    // Rewrite fields (New)
    private JPopupMenu rewritePopup;
    private List<String> currentRewriteSuggestions;
    private boolean isRewritePopupActive = false;
    private int rewriteSelectionStart = -1;
    private int rewriteSelectionEnd = -1;

    private static final int REWRITE_POPUP_VERTICAL_OFFSET = 15; // Increased from 5 to 15 for better visibility

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
        
        // Initialize popups with proper focus handling
        autoCompletePopup = new JPopupMenu();
        // Allow focus - needed for keyboard handling
        autoCompletePopup.setFocusable(true);
        
        rewritePopup = new JPopupMenu();
        // Allow focus - needed for keyboard handling
        rewritePopup.setFocusable(true);
        
        // Add popup menu listeners to prevent closing on focus loss
        setupPopupListeners();

        getDocument().addUndoableEditListener(e -> undoManager.addEdit(e.getEdit()));

        setupDocumentListener();
        setupAutocompleteTimer();
        setupTypingListener();
        loadNumSuggestions();
        setupPasteAction();
        initializeJOrtho(); // Initialize JOrtho spellchecker
        setupRewriteKeys(); // Setup keybindings for rewrite popup
        setupGlobalEscapeKey(); // Add call to setup global escape key
    }

    /**
     * Sets up popup menu listeners to maintain state when focus changes
     */
    private void setupPopupListeners() {
        // For autocomplete popup
        autoCompletePopup.addPopupMenuListener(new PopupMenuListener() {
            @Override
            public void popupMenuWillBecomeVisible(PopupMenuEvent e) {
                // Ensure flag is set when popup becomes visible
                isAutocompleteActive = true;
                System.out.println("Autocomplete popup becoming visible");
            }

            @Override
            public void popupMenuWillBecomeInvisible(PopupMenuEvent e) {
                System.out.println("Autocomplete popup will become invisible event");
                // DO NOT MODIFY isAutocompleteActive FLAG HERE
                // Let cancelAutoComplete handle this to avoid race conditions
            }

            @Override
            public void popupMenuCanceled(PopupMenuEvent e) {
                System.out.println("Autocomplete popup canceled event");
                // Handle popup cancellation that's not through our cancelAutoComplete method
                // This happens when user clicks outside the popup
                if (isAutocompleteActive) {
                    SwingUtilities.invokeLater(() -> {
                        if (isAutocompleteActive) {
                            System.out.println("Calling cancelAutoComplete from popupMenuCanceled event");
                            cancelAutoComplete();
                        }
                    });
                }
            }
        });
        
        // For rewrite popup
        rewritePopup.addPopupMenuListener(new PopupMenuListener() {
            @Override
            public void popupMenuWillBecomeVisible(PopupMenuEvent e) {
                // Ensure flag is set when popup becomes visible
                isRewritePopupActive = true;
                System.out.println("Rewrite popup becoming visible");
            }

            @Override
            public void popupMenuWillBecomeInvisible(PopupMenuEvent e) {
                System.out.println("Rewrite popup will become invisible event");
                // DO NOT MODIFY isRewritePopupActive FLAG HERE
                // Let cancelRewritePopup handle this to avoid race conditions
            }

            @Override
            public void popupMenuCanceled(PopupMenuEvent e) {
                System.out.println("Rewrite popup canceled event");
                // Handle popup cancellation that's not through our cancelRewritePopup method
                // This happens when user clicks outside the popup
                if (isRewritePopupActive) {
                    SwingUtilities.invokeLater(() -> {
                        if (isRewritePopupActive) {
                            System.out.println("Calling cancelRewritePopup from popupMenuCanceled event");
                            cancelRewritePopup();
                        }
                    });
                }
            }
        });
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
        // Add Escape key handling at the very beginning
        if (e.getID() == KeyEvent.KEY_PRESSED && e.getKeyCode() == KeyEvent.VK_ESCAPE) {
            System.out.println("Escape key pressed in processKeyEvent");
            boolean handled = false;
            
            // First check for autocomplete popup
            if (isAutocompleteActive) {
                System.out.println("Escape detected in processKeyEvent: Cancelling Autocomplete (active=" + isAutocompleteActive + 
                                  ", visible=" + (autoCompletePopup != null ? autoCompletePopup.isVisible() : "null") + ")");
                cancelAutoComplete();
                e.consume(); // Consume the event to prevent other handlers from processing it
                handled = true;
            }
            
            // Then check for rewrite popup
            if (isRewritePopupActive) {
                System.out.println("Escape detected in processKeyEvent: Cancelling Rewrite Popup (active=" + isRewritePopupActive + 
                                  ", visible=" + (rewritePopup != null ? rewritePopup.isVisible() : "null") + ")");
                cancelRewritePopup();
                e.consume();
                handled = true;
            }
            
            if (handled) {
                System.out.println("Escape key handled in processKeyEvent");
                return; // Skip the rest of the method if we handled the event
            } else {
                System.out.println("Escape key not handled in processKeyEvent - no active popups detected");
            }
        }
        
        // Handle Ctrl+1 through Ctrl+9 for suggestion selection
        if (e.getID() == KeyEvent.KEY_PRESSED && 
            (e.getModifiersEx() & KeyEvent.CTRL_DOWN_MASK) != 0 &&
            e.getKeyCode() >= KeyEvent.VK_1 && e.getKeyCode() <= KeyEvent.VK_9) {
            
            int index = e.getKeyCode() - KeyEvent.VK_1; // Convert to 0-based index
            
            // Detailed debugging of current state when key is pressed
            System.out.println("========================");
            System.out.println("ProcessKeyEvent: Ctrl+" + (index + 1) + " key detected");
            System.out.println("isRewritePopupActive: " + isRewritePopupActive);
            System.out.println("rewritePopup visible: " + (rewritePopup != null ? rewritePopup.isVisible() : "null"));
            System.out.println("currentRewriteSuggestions: " + (currentRewriteSuggestions != null ? 
                            "not null, size: " + currentRewriteSuggestions.size() : "null"));
            
            System.out.println("isAutocompleteActive: " + isAutocompleteActive);
            System.out.println("autoCompletePopup visible: " + (autoCompletePopup != null ? autoCompletePopup.isVisible() : "null"));
            System.out.println("currentAutocompleteSuggestions: " + (currentAutocompleteSuggestions != null ? 
                            "not null, length: " + currentAutocompleteSuggestions.length : "null"));
            
            // For rewrite: Check if we have valid suggestions at the given index
            boolean hasValidRewriteSuggestion = isRewritePopupActive && 
                    currentRewriteSuggestions != null && 
                    index < currentRewriteSuggestions.size() &&
                    currentRewriteSuggestions.get(index) != null && 
                    !currentRewriteSuggestions.get(index).isEmpty();
            
            // For autocomplete: Check if we have valid suggestions at the given index
            boolean hasValidAutocompleteSuggestion = isAutocompleteActive && 
                    currentAutocompleteSuggestions != null && 
                    index < currentAutocompleteSuggestions.length &&
                    currentAutocompleteSuggestions[index] != null && 
                    !currentAutocompleteSuggestions[index].isEmpty();
            
            // Now try to apply the suggestions, prioritizing rewrite
            boolean handled = false;
            if (hasValidRewriteSuggestion) {
                System.out.println("ProcessKeyEvent: Ctrl+" + (index + 1) + " for rewrite");
                insertRewriteSuggestion(currentRewriteSuggestions.get(index));
                handled = true;
            } else if (hasValidAutocompleteSuggestion) {
                System.out.println("ProcessKeyEvent: Ctrl+" + (index + 1) + " for autocomplete");
                insertAutocompleteSuggestion(currentAutocompleteSuggestions[index]);
                handled = true;
            } else {
                System.out.println("ProcessKeyEvent: Ctrl+" + (index + 1) + " - No valid suggestions to select");
            }
            System.out.println("========================");
            
            if (handled) {
                e.consume();
                return;
            }
        }
        
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
                boolean isSingleCharInsert = (e.getLength() == 1);

                if (isSingleCharInsert) {
                    long now = System.currentTimeMillis();
                    if (now - lastCharTypedTimestamp > TYPING_RESET_THRESHOLD_MS) {
                        consecutiveCharsTyped = 0; // Reset on pause
                    }
                    consecutiveCharsTyped++;
                    lastCharTypedTimestamp = now;

                    // --- Modified Autocomplete Logic --- 
                    if (isAutocompleteActive) {
                        // If popup is already active or being generated, cancel it because user continued typing
                        cancelAutoComplete(); 
                        consecutiveCharsTyped = 1; // Reset count after cancel, but consider this char as 1st
                    } 
                    
                    // Schedule autocomplete if AI not disabled and >= 1 char typed recently
                    if (!prefs.isAiFeaturesDisabled() && consecutiveCharsTyped >= 1) { 
                scheduleAutocomplete();
                    } else {
                        // Explicitly cancel if conditions aren't met (e.g., AI disabled)
                        cancelAutoComplete();
                    }
                    // --- End Modified Logic --- 

                } else {
                    // Reset trigger counter for multi-char inserts (paste, etc.)
                    consecutiveCharsTyped = 0;
                    // Also cancel autocomplete on paste/complex insert
                    cancelAutoComplete(); 
                }
                
                isDirty = true;
                updateStatusBarInfo();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                isDirty = true;
                consecutiveCharsTyped = 0; // Reset trigger count on delete/backspace
                cancelAutoComplete(); // Cancel autocomplete on backspace/delete
                updateStatusBarInfo();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                // Attribute changes (like formatting) - might want to cancel autocomplete?
                isDirty = true;
                consecutiveCharsTyped = 0; // Reset trigger count
                cancelAutoComplete(); // Cancel autocomplete on style changes
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
        currentAutocompleteSuggestions = new String[n];
    }

    private void scheduleAutocomplete() {
        if (prefs.isAiFeaturesDisabled()) return;
        if (isAnyDialogVisible() || isRewritePopupActive) return;
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
                    if (!isAnyDialogVisible() && !isRewritePopupActive) { // Double check popups
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
         System.out.println("cancelAutoComplete called, isAutocompleteActive=" + isAutocompleteActive + 
                           ", popup visible=" + (autoCompletePopup != null && autoCompletePopup.isVisible()));
         
         // Always cancel pending timer
         autocompleteTimer.cancel(); 
         autocompleteTimer = new Timer("AutocompleteTimer", true); // Recreate timer
         
         // Set flag to false first
         isAutocompleteActive = false;
         
         // Then hide popup if visible
         if (autoCompletePopup != null && autoCompletePopup.isVisible()) {
             System.out.println("Hiding autocomplete popup");
             autoCompletePopup.setVisible(false);
             statusBar.setText("Autocomplete cancelled."); // Provide feedback
             currentAutocompleteSuggestions = null; // Clear the suggestions array
         }
         
         // Restart the autocomplete timer after cancellation
         // This ensures pressing Escape won't permanently disable autocomplete
         if (!prefs.isAiFeaturesDisabled() && currentProvider != null && !isRewritePopupActive) {
             int delay = 1000; // Default delay
             try {
                 delay = Integer.parseInt(prefs.getPreference("autocompleteDelay", "1000")); 
                 if (delay < 0) delay = 1000;
             } catch (Exception ex) {
                 delay = 1000;
             }
             
             final int finalDelay = delay;
             // Schedule with a small additional delay to prevent immediate reactivation
             autocompleteTimer.schedule(new TimerTask() {
                 @Override
                 public void run() {
                     SwingUtilities.invokeLater(() -> {
                         if (!isAnyDialogVisible() && !isRewritePopupActive && !isAutocompleteActive) {
                             System.out.println("Restarting autocomplete after cancellation");
                             scheduleAutocomplete();
                         }
                     });
                 }
             }, finalDelay); // Use the same delay as normal autocomplete
         }
    }

    public void triggerAutocomplete() {
        if (prefs.isAiFeaturesDisabled()) return;
        if (isAnyDialogVisible() || isRewritePopupActive) return;
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

        currentAutocompleteSuggestions = new String[n];
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
                             currentAutocompleteSuggestions[i] = null; // Clear potential error message suggestion
                             continue; // Skip processing this suggestion
                         }
                         // Add checks for other known error patterns if necessary

                         raw = cleanOverlap(getPlainText(), raw);
                         raw = cleanupSuggestion(getPlainText(), raw);
                    currentAutocompleteSuggestions[i] = raw;
                    } catch (Exception futureEx) {
                        // Handle exception fetching individual future result
                        System.err.println("Error fetching suggestion future: " + futureEx.getMessage());
                        futureEx.printStackTrace();
                        errorMessage = "Autocomplete failed: Error fetching suggestion";
                        errorOccurred = true;
                        currentAutocompleteSuggestions[i] = null;
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
                if (!finalErrorOccurred && !isRewritePopupActive) {
                    // Only show popup if no errors occurred and we have valid suggestions
                    boolean hasValidSuggestions = false;
                    for(String s : currentAutocompleteSuggestions) {
                        if (s != null && !s.trim().isEmpty()) {
                            hasValidSuggestions = true;
                            break;
                        }
                    }
                    if (hasValidSuggestions) {
                showAutoCompletePopup(currentAutocompleteSuggestions);
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

    /**
     * Displays the autocomplete suggestions in a popup menu.
     */
    private void showAutoCompletePopup(String[] suggestions) {
        if (prefs.isAiFeaturesDisabled() || suggestions == null || suggestions.length == 0) {
            statusBar.setText("No suggestions available");
            return;
        }

        // Store for later access by keyboard shortcuts
        currentAutocompleteSuggestions = suggestions;
        autoCompletePopup.removeAll();

        for (int i = 0; i < suggestions.length; i++) {
            if (suggestions[i] != null && !suggestions[i].trim().isEmpty()) {
                final int index = i;
                final String cleanedSuggestion = suggestions[i].trim();
                final String displayText = "<html><b>Ctrl+" + (i + 1) + ":</b> " + 
                                      cleanedSuggestion.replaceAll("\n", "<br/>") + "</html>";
                
                JMenuItem item = new JMenuItem(displayText);
                item.setToolTipText("Press Ctrl+" + (i + 1) + " to select this option");
                item.addActionListener(e -> insertAutocompleteSuggestion(cleanedSuggestion));
                autoCompletePopup.add(item);
            }
        }

        if (autoCompletePopup.getComponentCount() > 0) {
            try {
                Rectangle caretCoords = modelToView(getCaretPosition());
                autoCompletePopup.show(this, caretCoords.x, caretCoords.y + caretCoords.height);
                // Set flag before showing popup to ensure it's set when key is pressed
                isAutocompleteActive = true;
                
                // Ensure popup maintains focus in the text editor
                SwingUtilities.invokeLater(() -> {
                    requestFocusInWindow();
                    System.out.println("Autocomplete popup active: " + isAutocompleteActive + 
                                      ", visible: " + autoCompletePopup.isVisible() + 
                                      ", suggestions: " + (currentAutocompleteSuggestions != null ? 
                                      currentAutocompleteSuggestions.length : "null"));
                });
                
                statusBar.setText("Select suggestion with mouse or Ctrl+[1-" + 
                                  Math.min(suggestions.length, 9) + "] | Esc to cancel");
            } catch (BadLocationException e) {
                e.printStackTrace();
                statusBar.setText("Error showing suggestions.");
            }
        } else {
            statusBar.setText("No valid suggestions received.");
        }
    }

    public void insertAutocompleteSuggestion(String suggestion) {
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
        } finally { cancelAutoComplete(); }
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
         if (isRewritePopupActive) return true; // Check rewrite popup
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

    // --- Rewrite Methods (New) --- 

    /**
     * Sets up a global Escape key binding on the TextEditor component
     * to cancel active popups like Autocomplete or Rewrite.
     */
    private void setupGlobalEscapeKey() {
        InputMap inputMap = getInputMap(JComponent.WHEN_FOCUSED);
        ActionMap actionMap = getActionMap();

        KeyStroke escapeKeyStroke = KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0);
        String actionKey = "cancelActivePopup";

        inputMap.put(escapeKeyStroke, actionKey);
        actionMap.put(actionKey, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                System.out.println("Global Escape key handler triggered");
                boolean popupCancelled = false;
                if (isAutocompleteActive && autoCompletePopup.isVisible()) {
                    System.out.println("Global Escape: Cancelling Autocomplete");
                    cancelAutoComplete();
                    popupCancelled = true;
                }
                // Check rewrite popup separately, ensuring it's visible
                if (isRewritePopupActive && rewritePopup.isVisible()) {
                    System.out.println("Global Escape: Cancelling Rewrite Popup");
                    cancelRewritePopup();
                    popupCancelled = true;
                }
                if (popupCancelled) {
                    // Optionally provide feedback or consume the event if needed
                    System.out.println("Popup cancelled via global Escape key handler");
                } else {
                     // If no popups were active, allow default Escape behavior (if any)
                     System.out.println("No popups cancelled via global Escape - none were active/visible");
                }
            }
        });
        
        // Also register Escape directly on both popups with WHEN_IN_FOCUSED_WINDOW
        registerEscapeOnPopups();
        
        // Set up global Ctrl+number keys to work with both autocomplete and rewrite
        for (int i = 1; i <= 9; i++) {
            final int index = i - 1;
            // Fix: use the actual number key codes directly, not arithmetic with VK_1
            int keyCode;
            switch (i) {
                case 1: keyCode = KeyEvent.VK_1; break;
                case 2: keyCode = KeyEvent.VK_2; break;
                case 3: keyCode = KeyEvent.VK_3; break;
                case 4: keyCode = KeyEvent.VK_4; break;
                case 5: keyCode = KeyEvent.VK_5; break;
                case 6: keyCode = KeyEvent.VK_6; break;
                case 7: keyCode = KeyEvent.VK_7; break;
                case 8: keyCode = KeyEvent.VK_8; break;
                case 9: keyCode = KeyEvent.VK_9; break;
                default: keyCode = KeyEvent.VK_1; // Should never happen
            }
            
            // Create proper KeyStroke with correct modifier
            KeyStroke ks = KeyStroke.getKeyStroke(keyCode, KeyEvent.CTRL_DOWN_MASK); 
            String actionName = "selectSuggestion" + i;
            
            // Register on multiple input maps to ensure it's caught regardless of focus state
            InputMap focusedMap = getInputMap(JComponent.WHEN_FOCUSED);
            InputMap ancestorMap = getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT);
            InputMap windowMap = getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
            
            focusedMap.put(ks, actionName);
            ancestorMap.put(ks, actionName);
            windowMap.put(ks, actionName);
            
            actionMap.put(actionName, new AbstractAction() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    // Detailed debugging of current state when key is pressed
                    System.out.println("========================");
                    System.out.println("Global Ctrl+" + (index + 1) + " caught: Processing suggestion selection");
                    System.out.println("isRewritePopupActive: " + isRewritePopupActive);
                    System.out.println("rewritePopup visible: " + (rewritePopup != null ? rewritePopup.isVisible() : "null"));
                    System.out.println("currentRewriteSuggestions: " + (currentRewriteSuggestions != null ? 
                                    "not null, size: " + currentRewriteSuggestions.size() : "null"));
                    
                    System.out.println("isAutocompleteActive: " + isAutocompleteActive);
                    System.out.println("autoCompletePopup visible: " + (autoCompletePopup != null ? autoCompletePopup.isVisible() : "null"));
                    System.out.println("currentAutocompleteSuggestions: " + (currentAutocompleteSuggestions != null ? 
                                    "not null, length: " + currentAutocompleteSuggestions.length : "null"));
                    
                    // First try to force the popup to be visible if it's been lost
                    if (isRewritePopupActive && !rewritePopup.isVisible() && currentRewriteSuggestions != null) {
                        System.out.println("Reactivating rewrite popup that lost visibility");
                        try {
                            Rectangle r = modelToView(getSelectionStart());
                            if (r != null) {
                                rewritePopup.show(TextEditor.this, r.x, r.y + r.height + REWRITE_POPUP_VERTICAL_OFFSET);
                            }
                        } catch (Exception ex) {
                            System.err.println("Error reshowing rewrite popup: " + ex.getMessage());
                        }
                    }
                    
                    if (isAutocompleteActive && !autoCompletePopup.isVisible() && currentAutocompleteSuggestions != null) {
                        System.out.println("Reactivating autocomplete popup that lost visibility");
                        try {
                            Rectangle r = modelToView(getCaretPosition());
                            if (r != null) {
                                autoCompletePopup.show(TextEditor.this, r.x, r.y + r.height);
                            }
                        } catch (Exception ex) {
                            System.err.println("Error reshowing autocomplete popup: " + ex.getMessage());
                        }
                    }
                    
                    // Handle either rewrite or autocomplete, prioritizing rewrite
                    // For rewrite: First check if we have valid suggestions at the given index
                    boolean hasValidRewriteSuggestion = isRewritePopupActive && 
                            currentRewriteSuggestions != null && 
                            index < currentRewriteSuggestions.size() &&
                            currentRewriteSuggestions.get(index) != null && 
                            !currentRewriteSuggestions.get(index).isEmpty();
                    
                    // For autocomplete: Check if we have valid suggestions at the given index
                    boolean hasValidAutocompleteSuggestion = isAutocompleteActive && 
                            currentAutocompleteSuggestions != null && 
                            index < currentAutocompleteSuggestions.length &&
                            currentAutocompleteSuggestions[index] != null && 
                            !currentAutocompleteSuggestions[index].isEmpty();
                    
                    // Now try to apply the suggestions, prioritizing rewrite
                    if (hasValidRewriteSuggestion) {
                        System.out.println("Global Ctrl+" + (index + 1) + ": Selecting rewrite suggestion");
                        insertRewriteSuggestion(currentRewriteSuggestions.get(index));
                    } else if (hasValidAutocompleteSuggestion) {
                        System.out.println("Global Ctrl+" + (index + 1) + ": Selecting autocomplete suggestion");
                        insertAutocompleteSuggestion(currentAutocompleteSuggestions[index]);
                    } else {
                        System.out.println("Ctrl+" + (index + 1) + ": No active suggestions to select");
                    }
                    System.out.println("========================");
                }
            });
        }
    }
    
    /**
     * Registers Escape key handlers directly on both popups
     */
    private void registerEscapeOnPopups() {
        // For autocomplete popup
        Action autocompleteEscapeAction = new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                System.out.println("Autocomplete popup direct Escape handler triggered");
                if (isAutocompleteActive) {
                    cancelAutoComplete();
                }
            }
        };
        
        // For rewrite popup
        Action rewriteEscapeAction = new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                System.out.println("Rewrite popup direct Escape handler triggered");
                if (isRewritePopupActive) {
                    cancelRewritePopup();
                }
            }
        };
        
        // Register on both popups with WHEN_IN_FOCUSED_WINDOW
        KeyStroke escKeyStroke = KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0);
        
        // For autocomplete
        autoCompletePopup.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(escKeyStroke, "cancelAutocomplete");
        autoCompletePopup.getActionMap().put("cancelAutocomplete", autocompleteEscapeAction);
        
        // For rewrite
        rewritePopup.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(escKeyStroke, "cancelRewrite");
        rewritePopup.getActionMap().put("cancelRewrite", rewriteEscapeAction);
    }
    
    /**
     * Sets up the Escape key and Ctrl+<number> keys for the rewrite popup.
     */
    private void setupRewriteKeys() {
        // Actions remain the same
        Action cancelAction = new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                System.out.println("Rewrite popup-specific Escape key action triggered.");
                if (isRewritePopupActive) {
                    System.out.println("Calling cancelRewritePopup().");
                    cancelRewritePopup();
                } else if (isAutocompleteActive) {
                    // Keep the ability for Esc to cancel autocomplete if needed
                    // but the primary binding for this action is on the rewrite popup
                    System.out.println("Calling cancelAutoComplete() from rewrite binding (fallback).");
                    cancelAutoComplete(); 
                }
            }
        };

        // Register Escape key directly on the popup so it can be closed by pressing Escape
        InputMap popupInputMap = rewritePopup.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
        ActionMap popupActionMap = rewritePopup.getActionMap();

        // Bind Escape Key to the popup
        popupInputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "cancelRewritePopup");
        popupActionMap.put("cancelRewritePopup", cancelAction);

        // Note: We don't register Ctrl+1-9 here anymore - they are handled by setupGlobalEscapeKey
        // This method is kept for popup-specific keybindings (Escape key)
    }

    /**
     * Displays the rewrite suggestions in a popup menu near the original selection.
     *
     * @param suggestions A list of rewrite suggestions.
     */
    public void showRewritePopup(List<String> suggestions) {
        if (prefs.isAiFeaturesDisabled() || suggestions == null || suggestions.isEmpty()) {
            statusBar.setText("No rewrite suggestions generated.");
            return;
        }
        
        cancelAutoComplete(); // Ensure autocomplete is hidden
        cancelRewritePopup(); // Cancel any previous rewrite popup

        currentRewriteSuggestions = new ArrayList<>(suggestions);
        rewritePopup.removeAll();

        for (int i = 0; i < currentRewriteSuggestions.size(); i++) {
            String suggestion = currentRewriteSuggestions.get(i);
            if (suggestion != null && !suggestion.isEmpty()) {
                final int index = i;
                // Use HTML for wrapping
                String wrappedSuggestion = "<html><body style='width: 300px'>" + // Set a width for wrapping
                                           "<b>Ctrl+" + (i + 1) + ":</b> " + 
                                           suggestion.replaceAll("\n", "<br>") + // Replace newlines with <br>
                                           "</body></html>";
                                           
                JMenuItem item = new JMenuItem(wrappedSuggestion);
                // Make menu items focusable for keyboard navigation
                item.setFocusable(true);
                item.setToolTipText("Press Ctrl+" + (i + 1) + " to select this option");
                item.addActionListener(e -> insertRewriteSuggestion(currentRewriteSuggestions.get(index)));
                rewritePopup.add(item);
            }
        }

        if (rewritePopup.getComponentCount() == 0) {
            statusBar.setText("No valid rewrite suggestions found.");
            return;
        }

        try {
            rewriteSelectionStart = getSelectionStart();
            rewriteSelectionEnd = getSelectionEnd();
            if (rewriteSelectionStart == rewriteSelectionEnd) { 
                statusBar.setText("No text selected for rewrite.");
                return; 
            } 

            Rectangle r = modelToView(rewriteSelectionStart);
            if (r != null) {
                // Set active flag BEFORE showing the popup
                isRewritePopupActive = true;
                
                // Show the popup
                rewritePopup.show(this, r.x, r.y + r.height + REWRITE_POPUP_VERTICAL_OFFSET);
                
                // Ensure popup maintains focus in the text editor and debug popup state
                SwingUtilities.invokeLater(() -> {
                    requestFocusInWindow();
                    System.out.println("Rewrite popup active: " + isRewritePopupActive + 
                                      ", visible: " + rewritePopup.isVisible() + 
                                      ", suggestions: " + (currentRewriteSuggestions != null ? 
                                      currentRewriteSuggestions.size() : "null"));
                });
                
                statusBar.setText("Rewrite suggestions available. Press Ctrl+[1-" + currentRewriteSuggestions.size() + "] or Esc.");
            } else {
                 // Fallback positioning
                 isRewritePopupActive = true;
                 rewritePopup.show(this, 100, 100 + REWRITE_POPUP_VERTICAL_OFFSET);
                 
                 // Debug popup state
                 SwingUtilities.invokeLater(() -> {
                     requestFocusInWindow();
                     System.out.println("Rewrite popup (fallback) active: " + isRewritePopupActive + 
                                       ", visible: " + rewritePopup.isVisible());
                 });
                 
                 statusBar.setText("Rewrite suggestions available (popup position approximate). Press Ctrl+[1-" + currentRewriteSuggestions.size() + "] or Esc.");
            }
        } catch (BadLocationException ex) {
            ex.printStackTrace();
            statusBar.setText("Error showing rewrite popup.");
        }
    }

    /**
     * Replaces the original selection with the chosen rewrite suggestion.
     * Handles undo/redo.
     *
     * @param suggestion The chosen rewrite text.
     */
    public void insertRewriteSuggestion(String suggestion) {
        if (suggestion == null || suggestion.isEmpty() || rewriteSelectionStart < 0 || rewriteSelectionEnd < 0 || rewriteSelectionStart == rewriteSelectionEnd) {
            cancelRewritePopup();
            return;
        }

        try {
            StyledDocument sdoc = (StyledDocument) getDocument();
            AttributeSet attrs = sdoc.getCharacterElement(rewriteSelectionStart).getAttributes();
            int originalLength = rewriteSelectionEnd - rewriteSelectionStart;

            // Use the existing ReplaceEdit helper class for undo/redo
            ReplaceEdit edit = new ReplaceEdit(sdoc, rewriteSelectionStart, originalLength, suggestion, attrs);
            
            sdoc.remove(rewriteSelectionStart, originalLength);
            sdoc.insertString(rewriteSelectionStart, suggestion, attrs);
            undoManager.addEdit(edit);

            aiCharCount += suggestion.length(); 
            isDirty = true;
            updateStatusBarInfo();
            statusBar.setText("Rewrite applied.");

        } catch (BadLocationException ex) {
            ex.printStackTrace();
            statusBar.setText("Error applying rewrite suggestion: " + ex.getMessage());
        } finally {
            cancelRewritePopup();
        }
    }

    /**
     * Cancels the rewrite suggestion popup and resets its state.
     */
    public void cancelRewritePopup() {
        // Make sure rewritePopup exists before trying to hide it
        if (isRewritePopupActive && rewritePopup != null) {
            rewritePopup.setVisible(false);
            isRewritePopupActive = false;
            currentRewriteSuggestions = null;
            rewriteSelectionStart = -1;
            rewriteSelectionEnd = -1;
            statusBar.setText("Rewrite cancelled."); // Provide feedback
            updateStatusBarInfo(); // Refresh status bar in case it showed suggestion count
        } else {
             // If not active, ensure flag is false just in case
             isRewritePopupActive = false;
        }
    }

    // --- Overridden replaceSelectionWithRewrite (Now delegates to popup) ---
    /**
     * @deprecated Use showRewritePopup instead to display suggestions.
     */
    @Deprecated
    public void replaceSelectionWithRewrite(String rewriteText) {
        // This method is kept temporarily for compatibility but shouldn't be called directly.
        // The flow now goes through Syngrafi -> RewriteManager -> showRewritePopup -> insertRewriteSuggestion.
        System.err.println("Warning: replaceSelectionWithRewrite called directly. Flow should use showRewritePopup.");
        // For safety, maybe call insertRewriteSuggestion if needed, but it lacks context.
         if (getSelectionStart() != getSelectionEnd()) {
             rewriteSelectionStart = getSelectionStart();
             rewriteSelectionEnd = getSelectionEnd();
             insertRewriteSuggestion(rewriteText); // Risky without proper setup
         } else {
             statusBar.setText("Rewrite requires selection (legacy call).");
         }
    }

    // Helper class for Undoable Edits during replacement
    private static class ReplaceEdit extends AbstractUndoableEdit {
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
                // Get the text *before* removal
                this.oldText = doc.getText(offs, len);
            } catch (BadLocationException e) {
                // This should ideally not happen if offsets/len are correct
                throw new RuntimeException("Error creating undo edit: Cannot get old text at offset " + offs + " len " + len, e);
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
            return "Rewrite Selection"; // Or be more specific
        }
    }

    /**
     * Inserts the autocomplete suggestion corresponding to the given index.
     * Called by keyboard shortcuts (Ctrl+1, etc.).
     * Ensures the method is public and accessible.
     */
    public void insertSuggestionByIndex(int idx) { // For Autocomplete
         if (isRewritePopupActive) return; // Don't act if rewrite popup is active
         if (idx < 0 || currentAutocompleteSuggestions == null || idx >= currentAutocompleteSuggestions.length) return;
         
         String suggestion = currentAutocompleteSuggestions[idx];
         if (suggestion != null && !suggestion.isEmpty()) {
             insertAutocompleteSuggestion(suggestion); // Call the insertion logic
         }
    }
}