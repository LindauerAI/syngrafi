import api.APIProvider;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.*;
import javax.swing.text.html.*;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.CompletableFuture;

/**
 * An improved text editor component that provides rich text editing capabilities
 * and automatic suggestions based on AI models.
 */
public class ImprovedTextEditor extends JTextPane {
    private final JLabel statusBar;
    private APIProvider currentProvider;
    private Timer autocompleteTimer;
    private boolean isAutocompleteActive = false;
    private final int AUTOCOMPLETE_DELAY = 1000; // milliseconds
    private JPopupMenu autoCompletePopup;
    private String[] currentSuggestions = new String[3];

    public ImprovedTextEditor(JLabel statusBar) {
        this.statusBar = statusBar;
        initializeEditor();
        setupAutocompleteTimer();
        setupDocumentListener();
        setupKeyBindings();
    }

    public void setAPIProvider(APIProvider provider) {
        this.currentProvider = provider;
    }

    private void initializeEditor() {
        // Use HTMLEditorKit but we manage plain text prompting behind the scenes
        setEditorKit(new HTMLEditorKit());
        setText("");

        // For HTML display, tweak minimal styling
        HTMLEditorKit editorKit = (HTMLEditorKit) getEditorKit();
        StyleSheet styleSheet = editorKit.getStyleSheet();
        styleSheet.addRule("body { font-family: Serif; }");
    }

    private void setupAutocompleteTimer() {
        autocompleteTimer = new Timer("AutocompleteTimer", true);
        autoCompletePopup = new JPopupMenu();
    }

    private void setupDocumentListener() {
        getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                scheduleAutocomplete();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                scheduleAutocomplete();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                // Not typically fired for plain text changes in HTML docs
            }
        });
    }

    private void setupKeyBindings() {
        // Replace the default SHIFT+ENTER with a line-break (BR) insertion
        InputMap im = getInputMap(JComponent.WHEN_FOCUSED);
        ActionMap am = getActionMap();

        im.put(KeyStroke.getKeyStroke("shift ENTER"), "insert-line-break");
        am.put("insert-line-break", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                insertBrTag();
            }
        });
    }

    /**
     * Instead of scheduling a new line with <p>, we insert <br/> at the caret.
     */
    private void insertBrTag() {
        HTMLDocument doc = (HTMLDocument) getDocument();
        int pos = getCaretPosition();
        try {
            // Insert a <br> at the caret position
            ((HTMLEditorKit) getEditorKit()).insertHTML(doc, pos, "<br/>", 0, 0, HTML.Tag.BR);
            setCaretPosition(pos + 1);
            setCaretPosition(pos + 1);
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    /**
     * Returns the plain text of the document (no HTML tags).
     */
    private String getPlainText() {
        try {
            Document doc = getDocument();
            return doc.getText(0, doc.getLength());
        } catch (BadLocationException e) {
            e.printStackTrace();
            return "";
        }
    }

    private void scheduleAutocomplete() {
        if (isAutocompleteActive || currentProvider == null) return;

        // Cancel and restart the timer each time the doc changes
        if (autocompleteTimer != null) {
            autocompleteTimer.cancel();
            autocompleteTimer = new Timer("AutocompleteTimer", true);
        }
        autocompleteTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                SwingUtilities.invokeLater(() -> triggerAutocomplete());
            }
        }, AUTOCOMPLETE_DELAY);
    }

    /**
     * Triggers the autocomplete process
     */
    public void triggerAutocomplete() {
        if (currentProvider == null || isAutocompleteActive) {
            return;
        }

        String contextText = getPlainText();
        if (contextText.length() < 20) {
            return; // skip if doc is too short
        }

        isAutocompleteActive = true;
        statusBar.setText("Generating suggestions...");

        CompletableFuture<String> f1 = CompletableFuture.supplyAsync(() -> {
            try {
                return currentProvider.generateCompletion(
                        AutocompletePromptManager.getPrompt(contextText, 1)
                );
            } catch (Exception ex) {
                ex.printStackTrace();
                return "Error generating suggestion";
            }
        });
        CompletableFuture<String> f2 = CompletableFuture.supplyAsync(() -> {
            try {
                return currentProvider.generateCompletion(
                        AutocompletePromptManager.getPrompt(contextText, 2)
                );
            } catch (Exception ex) {
                ex.printStackTrace();
                return "Error generating suggestion";
            }
        });
        CompletableFuture<String> f3 = CompletableFuture.supplyAsync(() -> {
            try {
                return currentProvider.generateCompletion(
                        AutocompletePromptManager.getPrompt(contextText, 3)
                );
            } catch (Exception ex) {
                ex.printStackTrace();
                return "Error generating suggestion";
            }
        });

        CompletableFuture.allOf(f1, f2, f3).thenRun(() -> {
            try {
                String s1 = cleanOverlap(contextText, f1.get());
                String s2 = cleanOverlap(contextText, f2.get());
                String s3 = cleanOverlap(contextText, f3.get());

                SwingUtilities.invokeLater(() -> {
                    currentSuggestions[0] = s1;
                    currentSuggestions[1] = s2;
                    currentSuggestions[2] = s3;
                    showAutoCompletePopup(new String[]{s1, s2, s3});

                    statusBar.setText("Ready");
                    isAutocompleteActive = false;
                });
            } catch (Exception ex) {
                ex.printStackTrace();
                SwingUtilities.invokeLater(() -> {
                    statusBar.setText("Error getting suggestions");
                    isAutocompleteActive = false;
                });
            }
        });
    }

    /**
     * Attempts to remove duplicated text from the beginning of suggestion
     * if the user’s last typed words appear at the start of it.
     */
    private String cleanOverlap(String existingText, String suggestion) {
        if (suggestion == null) return "";
        suggestion = suggestion.trim();

        // If the suggestion includes leading punctuation or repeated words,
        // attempt to drop overlaps with the last ~30 characters of existing text.
        int overlapZone = Math.min(30, existingText.length());
        String tail = existingText.substring(existingText.length() - overlapZone).toLowerCase();

        // We search up to 30 chars for duplication
        String lowerSuggestion = suggestion.toLowerCase();

        // If the suggestion starts with the tail or part of it, remove that portion
        // We'll do a crude approach: find the largest suffix of 'tail' that's a prefix of 'suggestion'
        int maxLen = 0;
        for (int len = overlapZone; len > 0; len--) {
            String suffix = tail.substring(overlapZone - len); // last 'len' chars of tail
            if (lowerSuggestion.startsWith(suffix)) {
                maxLen = len;
                break;
            }
        }
        if (maxLen > 0) {
            suggestion = suggestion.substring(maxLen).trim();
        }

        // Remove trailing "..." if present
        while (suggestion.endsWith("...")) {
            suggestion = suggestion.substring(0, suggestion.length() - 3).trim();
        }

        return suggestion;
    }

    /**
     * Shows the suggestion popup, allowing the user to click on them to insert.
     */
    private void showAutoCompletePopup(String[] suggestions) {
        autoCompletePopup.removeAll();
        if (suggestions == null) return;

        int validCount = 0;
        for (int i = 0; i < suggestions.length; i++) {
            if (suggestions[i] != null && !suggestions[i].trim().isEmpty()) {
                validCount++;
                final int idx = i;
                JMenuItem item = new JMenuItem("<html><body width='300'>" +
                        suggestions[i].replace("<", "&lt;").replace(">", "&gt;")
                                .replace("\n", "<br>") +
                        "</body></html>");
                item.addActionListener(e -> insertSuggestionAtCaret(suggestions[idx]));
                autoCompletePopup.add(item);
            }
        }
        if (validCount == 0) {
            // No suggestions to show
            return;
        }

        try {
            Rectangle caretCoords = modelToView(getCaretPosition());
            if (caretCoords != null) {
                autoCompletePopup.show(this, caretCoords.x, caretCoords.y + caretCoords.height);
            }
        } catch (BadLocationException ex) {
            ex.printStackTrace();
        }
    }

    /**
     * Inserts the suggestion at the current caret position,
     * applying the same style as the preceding character.
     */
    public void insertSuggestionAtCaret(String suggestion) {
        if (suggestion == null || suggestion.isEmpty()) return;

        int caretPos = getCaretPosition();
        AttributeSet styleForInsertion = null;
        try {
            if (caretPos > 0) {
                styleForInsertion = getStyledDocument().getCharacterElement(caretPos - 1).getAttributes();
            } else {
                // If at start, just use default
                styleForInsertion = getInputAttributes();
            }
            getDocument().insertString(caretPos, suggestion, styleForInsertion);
            setCaretPosition(caretPos + suggestion.length());

            if (autoCompletePopup.isVisible()) {
                autoCompletePopup.setVisible(false);
            }
            statusBar.setText("Suggestion inserted");
        } catch (BadLocationException ex) {
            ex.printStackTrace();
            statusBar.setText("Error inserting suggestion");
        }
    }

    /* ==================== FORMATTING METHODS ==================== */

    public void toggleBold() {
        MutableAttributeSet attr = new SimpleAttributeSet(getInputAttributes());
        StyleConstants.setBold(attr, !StyleConstants.isBold(attr));
        setCharacterAttributes(attr, false);
        requestFocusInWindow();
    }

    public void toggleItalic() {
        MutableAttributeSet attr = new SimpleAttributeSet(getInputAttributes());
        StyleConstants.setItalic(attr, !StyleConstants.isItalic(attr));
        setCharacterAttributes(attr, false);
        requestFocusInWindow();
    }

    public void toggleUnderline() {
        MutableAttributeSet attr = new SimpleAttributeSet(getInputAttributes());
        StyleConstants.setUnderline(attr, !StyleConstants.isUnderline(attr));
        setCharacterAttributes(attr, false);
        requestFocusInWindow();
    }

    /**
     * Sets the paragraph as an H1 or H2 by increasing the font size
     * and weight. (In pure HTML you’d do <h1>/<h2>, but here we rely on
     * paragraph styling.)
     */
    public void setHeadingLevel(int level) {
        int start = getSelectionStart();
        int end = getSelectionEnd();

        if (start == end) {
            // No selection: apply to current paragraph
            Element paragraph = getStyledDocument().getParagraphElement(getCaretPosition());
            setHeadingStyle(paragraph.getStartOffset(), paragraph.getEndOffset() - 1, level);
        } else {
            // Apply to selected paragraphs
            int pos = start;
            while (pos <= end) {
                Element paragraph = getStyledDocument().getParagraphElement(pos);
                int rangeEnd = Math.min(paragraph.getEndOffset() - 1, end);
                setHeadingStyle(paragraph.getStartOffset(), rangeEnd, level);
                pos = paragraph.getEndOffset();
            }
        }
        requestFocusInWindow();
    }

    private void setHeadingStyle(int start, int end, int level) {
        MutableAttributeSet attr = new SimpleAttributeSet();
        if (level == 1) {
            StyleConstants.setFontSize(attr, 24);
            StyleConstants.setBold(attr, true);
        } else {
            // H2
            StyleConstants.setFontSize(attr, 18);
            StyleConstants.setBold(attr, true);
        }
        getStyledDocument().setParagraphAttributes(start, end - start + 1, attr, false);
    }

    /**
     * Reverts text back to a normal style
     */
    public void setNormalText() {
        int start = getSelectionStart();
        int end = getSelectionEnd();

        if (start == end) {
            // No selection: apply to the entire paragraph
            Element paragraph = getStyledDocument().getParagraphElement(getCaretPosition());
            setParagraphNormal(paragraph.getStartOffset(), paragraph.getEndOffset() - 1);
        } else {
            // Apply to selected paragraphs
            int pos = start;
            while (pos <= end) {
                Element paragraph = getStyledDocument().getParagraphElement(pos);
                int rangeEnd = Math.min(paragraph.getEndOffset() - 1, end);
                setParagraphNormal(paragraph.getStartOffset(), rangeEnd);
                pos = paragraph.getEndOffset();
            }
        }
        requestFocusInWindow();
    }

    private void setParagraphNormal(int start, int end) {
        MutableAttributeSet attr = new SimpleAttributeSet();
        StyleConstants.setFontSize(attr, 16);
        StyleConstants.setBold(attr, false);
        StyleConstants.setItalic(attr, false);
        StyleConstants.setUnderline(attr, false);
        getStyledDocument().setParagraphAttributes(start, end - start + 1, attr, false);
    }
}
