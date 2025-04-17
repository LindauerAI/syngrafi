import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;

public class HelpDialog extends JDialog {

    public HelpDialog(Frame owner) {
        super(owner, "Keyboard Shortcuts", false); // Modeless dialog
        setSize(500, 400);
        setLocationRelativeTo(owner);
        setLayout(new BorderLayout());

        // Define shortcut data
        String[] columnNames = {"Action", "Shortcut"};
        Object[][] data = {
                {"New Document", "Ctrl + N"},
                {"Open Document", "Ctrl + O"},
                {"Save Document", "Ctrl + S"},
                {"Export as PDF", "(Menu only)"},
                {"Commit Version", "(Menu only)"},
                {"Settings", "(Menu only)"},
                {"Undo", "Ctrl + Z"},
                {"Redo", "Ctrl + Y (or Ctrl + Shift + Z)"},
                {"Cut", "Ctrl + X"},
                {"Copy", "Ctrl + C"},
                {"Paste", "Ctrl + V"},
                {"Paste and Match Style", "Ctrl + Shift + V"},
                {"Toggle Bold", "Ctrl + B"},
                {"Toggle Italic", "Ctrl + I"},
                {"Toggle Underline", "Ctrl + U"},
                {"Apply Strikethrough", "(Button only)"},
                {"Set Heading 1", "(Button only)"},
                {"Set Heading 2", "(Button only)"},
                {"Set Normal Text", "(Button only)"},
                {"Apply Ordered List", "(Button only)"},
                {"Apply Unordered List", "(Button only)"},
                {"Align Left", "(Button only)"},
                {"Align Center", "(Button only)"},
                {"Align Right", "(Button only)"},
                {"Accept Autocomplete 1", "Ctrl + 1"},
                {"Accept Autocomplete 2", "Ctrl + 2"},
                {"Accept Autocomplete 3+", "Ctrl + 3, ..."},
                {"Cancel Autocomplete", "Esc"},
                // Add more shortcuts as needed
        };

        // Create table model (non-editable)
        DefaultTableModel tableModel = new DefaultTableModel(data, columnNames) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };

        JTable shortcutTable = new JTable(tableModel);
        shortcutTable.setFillsViewportHeight(true);
        shortcutTable.getColumnModel().getColumn(0).setPreferredWidth(200);
        shortcutTable.getColumnModel().getColumn(1).setPreferredWidth(150);

        JScrollPane scrollPane = new JScrollPane(shortcutTable);
        add(scrollPane, BorderLayout.CENTER);

        // Close button
        JPanel bottomPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton closeButton = new JButton("Close");
        closeButton.addActionListener(e -> dispose());
        bottomPanel.add(closeButton);
        add(bottomPanel, BorderLayout.SOUTH);
    }
} 