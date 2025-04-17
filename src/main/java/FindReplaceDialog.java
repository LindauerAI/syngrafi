import javax.swing.*;
import javax.swing.text.BadLocationException;
import javax.swing.text.Document;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

public class FindReplaceDialog extends JDialog {

    private JTextField findField;
    private JTextField replaceField;
    private JCheckBox matchCaseCheckBox;
    private JCheckBox wrapSearchCheckBox;
    private JTextPane textPane; // Reference to the editor pane

    private int lastFoundIndex = -1;

    public FindReplaceDialog(Frame owner, JTextPane textPane) {
        super(owner, "Find and Replace", false); // Modeless
        this.textPane = textPane;

        // Components
        findField = new JTextField(20);
        replaceField = new JTextField(20);
        matchCaseCheckBox = new JCheckBox("Match Case");
        wrapSearchCheckBox = new JCheckBox("Wrap Search", true);

        JButton findNextButton = new JButton("Find Next");
        JButton replaceButton = new JButton("Replace");
        JButton replaceAllButton = new JButton("Replace All");
        JButton cancelButton = new JButton("Cancel");

        // Layout
        JPanel mainPanel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(5, 5, 5, 5);
        gbc.anchor = GridBagConstraints.WEST;

        // Row 0: Find
        gbc.gridx = 0; gbc.gridy = 0; mainPanel.add(new JLabel("Find What:"), gbc);
        gbc.gridx = 1; gbc.gridwidth = 2; gbc.fill = GridBagConstraints.HORIZONTAL; mainPanel.add(findField, gbc);
        gbc.gridx = 3; gbc.gridwidth = 1; gbc.fill = GridBagConstraints.NONE; mainPanel.add(findNextButton, gbc);

        // Row 1: Replace
        gbc.gridx = 0; gbc.gridy = 1; mainPanel.add(new JLabel("Replace With:"), gbc);
        gbc.gridx = 1; gbc.gridwidth = 2; gbc.fill = GridBagConstraints.HORIZONTAL; mainPanel.add(replaceField, gbc);
        gbc.gridx = 3; gbc.gridwidth = 1; gbc.fill = GridBagConstraints.NONE; mainPanel.add(replaceButton, gbc);

        // Row 2: Options & Replace All
        gbc.gridx = 0; gbc.gridy = 2; mainPanel.add(matchCaseCheckBox, gbc);
        gbc.gridx = 1; mainPanel.add(wrapSearchCheckBox, gbc);
        gbc.gridx = 3; gbc.gridy = 2; mainPanel.add(replaceAllButton, gbc);
        
        // Row 3: Cancel
        gbc.gridx = 3; gbc.gridy = 3; mainPanel.add(cancelButton, gbc);

        // Actions
        findNextButton.addActionListener(this::findNextAction);
        replaceButton.addActionListener(this::replaceAction);
        replaceAllButton.addActionListener(this::replaceAllAction);
        cancelButton.addActionListener(e -> dispose());

        // Reset lastFoundIndex when find text changes
        findField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void changedUpdate(javax.swing.event.DocumentEvent e) { resetSearch(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { resetSearch(); }
            public void insertUpdate(javax.swing.event.DocumentEvent e) { resetSearch(); }
            void resetSearch() { lastFoundIndex = -1; }
        });

        // Reset lastFoundIndex when dialog is made visible
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowActivated(WindowEvent e) {
                 lastFoundIndex = textPane.getCaretPosition(); // Start search from caret
            }
        });

        setContentPane(mainPanel);
        pack();
        setLocationRelativeTo(owner);
        setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
    }

    private void findNextAction(ActionEvent e) {
        String findText = findField.getText();
        if (findText.isEmpty()) return;

        Document doc = textPane.getDocument();
        boolean matchCase = matchCaseCheckBox.isSelected();
        boolean wrap = wrapSearchCheckBox.isSelected();
        int searchStart = (lastFoundIndex == -1) ? textPane.getCaretPosition() : lastFoundIndex + 1; // Start after last find or caret

        try {
            String content = doc.getText(0, doc.getLength());
            String searchContent = matchCase ? content : content.toLowerCase();
            String searchText = matchCase ? findText : findText.toLowerCase();

            lastFoundIndex = searchContent.indexOf(searchText, searchStart);

            if (lastFoundIndex == -1 && wrap && searchStart > 0) { // Wrap search
                lastFoundIndex = searchContent.indexOf(searchText, 0);
            }

            if (lastFoundIndex != -1) {
                textPane.select(lastFoundIndex, lastFoundIndex + findText.length());
                textPane.requestFocusInWindow();
            } else {
                JOptionPane.showMessageDialog(this, "Text not found.", "Find", JOptionPane.INFORMATION_MESSAGE);
                 lastFoundIndex = -1; // Reset if not found
            }
        } catch (BadLocationException ex) {
            ex.printStackTrace(); // Should not happen
        }
    }

    private void replaceAction(ActionEvent e) {
        String findText = findField.getText();
        String replaceText = replaceField.getText();
        if (findText.isEmpty()) return;

        // Check if there is a current selection matching the find text
        String selectedText = textPane.getSelectedText();
        boolean matchCase = matchCaseCheckBox.isSelected();
        String compareSelected = matchCase ? selectedText : (selectedText != null ? selectedText.toLowerCase() : null);
        String compareFind = matchCase ? findText : findText.toLowerCase();

        if (compareSelected != null && compareSelected.equals(compareFind)) {
            textPane.replaceSelection(replaceText);
             lastFoundIndex = textPane.getSelectionStart(); // Update index after replace
             findNextAction(null); // Find the next one immediately
        } else {
            // If no matching selection, just find the next occurrence
            findNextAction(e);
        }
    }

    private void replaceAllAction(ActionEvent e) {
        String findText = findField.getText();
        String replaceText = replaceField.getText();
        if (findText.isEmpty()) return;

        Document doc = textPane.getDocument();
        boolean matchCase = matchCaseCheckBox.isSelected();
        int replacements = 0;
        lastFoundIndex = -1; // Start from beginning

        try {
             // It's safer to get text once and work with indices, but direct replaceAll isn't trivial with JTextPane styling.
             // Simple approach: repeatedly find and replace.
             // NOTE: This can be inefficient for large documents.
             int searchFrom = 0;
             while (true) {
                 String content = doc.getText(0, doc.getLength());
                 String searchContent = matchCase ? content : content.toLowerCase();
                 String searchText = matchCase ? findText : findText.toLowerCase();
                 
                 int foundIndex = searchContent.indexOf(searchText, searchFrom);
                 if (foundIndex == -1) break; // No more occurrences

                 textPane.select(foundIndex, foundIndex + findText.length());
                 textPane.replaceSelection(replaceText);
                 replacements++;
                 searchFrom = foundIndex + replaceText.length(); // Start next search after the replacement
             }
             JOptionPane.showMessageDialog(this, replacements + " replacement(s) made.", "Replace All", JOptionPane.INFORMATION_MESSAGE);
             lastFoundIndex = -1; // Reset search
        } catch (BadLocationException ex) {
             ex.printStackTrace();
        }
    }
} 