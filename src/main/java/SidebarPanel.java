import javax.swing.*;
import javax.swing.text.*;
import javax.swing.text.html.HTMLDocument;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.TreePath;
import java.awt.*;
import java.awt.event.*;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

import javax.swing.text.html.HTML;


/**
 * A panel that offers four sidebar modes:
 * 1) File tree
 * 2) Version explorer
 * 3) Usage pie chart of AI vs Human
 * 4) Section explorer for headings
 */
public class SidebarPanel extends JPanel {

    private JComboBox<String> modeCombo;
    private CardLayout cardLayout;
    private JPanel cardContainer;

    // Panels
    private JPanel fileTreePanel;
    private JPanel versionPanel;
    private UsagePiePanel usagePanel;
    private SectionExplorer sectionExplorer;

    // Callback to main editor?
    private Syngrafi parentFrame;

    private JList<File> fileList;
    private DefaultListModel<File> listModel;
    private JLabel wordCountLabel;
    private JLabel aiCharCountLabel;
    private JLabel humanCharCountLabel;
    private Timer updateTimer; // Timer to periodically update stats

    public SidebarPanel(Syngrafi parentFrame) {
        super(new BorderLayout());
        this.parentFrame = parentFrame;
        modeCombo = new JComboBox<>(new String[]{
                "File Tree", "Version Explorer", "Usage Chart", "Section Explorer"
        });
        modeCombo.addActionListener(e -> switchMode());
        add(modeCombo, BorderLayout.NORTH);

        cardLayout = new CardLayout();
        cardContainer = new JPanel(cardLayout);

        // Replace placeholder with our updated file tree panel
        fileTreePanel = createFileTreePanel();
        versionPanel = createVersionPanel();
        usagePanel = new UsagePiePanel(parentFrame);
        sectionExplorer = new SectionExplorer(parentFrame);

        cardContainer.add(fileTreePanel, "File Tree");
        cardContainer.add(versionPanel, "Version Explorer");
        cardContainer.add(usagePanel, "Usage Chart");
        cardContainer.add(sectionExplorer, "Section Explorer");

        add(cardContainer, BorderLayout.CENTER);

        // Auto-populate file tree initially:
        refreshFileTree();

        // --- Bottom: Stats and Actions ---
        JPanel bottomPanel = new JPanel();
        bottomPanel.setLayout(new BoxLayout(bottomPanel, BoxLayout.Y_AXIS));

        // Stats Panel
        JPanel statsPanel = new JPanel(new GridLayout(0, 1, 2, 2));
        statsPanel.setBorder(BorderFactory.createTitledBorder("Document Stats"));
        wordCountLabel = new JLabel("Words: 0");
        aiCharCountLabel = new JLabel("AI Chars: 0");
        humanCharCountLabel = new JLabel("Human Chars: 0");
        statsPanel.add(wordCountLabel);
        statsPanel.add(aiCharCountLabel);
        statsPanel.add(humanCharCountLabel);
        bottomPanel.add(statsPanel);
        bottomPanel.add(Box.createRigidArea(new Dimension(0, 10))); // Spacer

        // Actions Panel
        JPanel actionsPanel = new JPanel(new FlowLayout(FlowLayout.CENTER));
        JButton saveButton = new JButton("Save");
        saveButton.addActionListener(e -> parentFrame.saveDocument());
        actionsPanel.add(saveButton);
        
        JButton findButton = new JButton("Find/Replace");
        findButton.addActionListener(e -> parentFrame.showFindReplaceDialog()); // Call method on parent frame
        actionsPanel.add(findButton);
        bottomPanel.add(actionsPanel);

        add(bottomPanel, BorderLayout.SOUTH);

        // Start timer to update stats periodically
        setupUpdateTimer();
    }


    private void switchMode() {
        String mode = (String) modeCombo.getSelectedItem();
        cardLayout.show(cardContainer, mode);
        if ("Section Explorer".equals(mode)) {
            sectionExplorer.refresh();
        }
        if ("File Tree".equals(mode)) {
            refreshFileTree();
        }
    }

    /**
     * A placeholder that shows the current default directory as a JTree
     */
    private JPanel createFileTreePanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.add(new JLabel("File Tree Placeholder"), BorderLayout.NORTH);

        // minimal placeholder
        JTree tree = new JTree(new DefaultMutableTreeNode("Empty"));
        panel.add(new JScrollPane(tree), BorderLayout.CENTER);
        return panel;
    }

    private void refreshFileTree() {
        File defaultDir = parentFrame.getDefaultDirectory();
        DefaultMutableTreeNode root = new DefaultMutableTreeNode(defaultDir);
        addFileNodes(root, defaultDir);
        JTree tree = new JTree(root);

        // Set custom renderer to show file names
        tree.setCellRenderer(new DefaultTreeCellRenderer() {
            @Override
            public Component getTreeCellRendererComponent(JTree tree, Object value,
                                                          boolean sel, boolean expanded,
                                                          boolean leaf, int row, boolean hasFocus) {
                JLabel label = (JLabel) super.getTreeCellRendererComponent(
                        tree, value, sel, expanded, leaf, row, hasFocus);
                if (value instanceof DefaultMutableTreeNode) {
                    DefaultMutableTreeNode node = (DefaultMutableTreeNode) value;
                    Object userObject = node.getUserObject();
                    if (userObject instanceof File) {
                        File file = (File) userObject;
                        label.setText(file.getName());
                    }
                }
                return label;
            }
        });

        // Add mouse listener to open HTML files on double-click
        tree.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    TreePath path = tree.getPathForLocation(e.getX(), e.getY());
                    if (path != null) {
                        DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent();
                        Object userObject = node.getUserObject();
                        if (userObject instanceof File) {
                            File file = (File) userObject;
                            if (file.isFile() && file.getName().toLowerCase().endsWith(".html")) {
                                parentFrame.publicHandleOpenDocument(file);
                            }
                        }
                    }
                }
            }
        });

        fileTreePanel.removeAll();
        fileTreePanel.setLayout(new BorderLayout());
        fileTreePanel.add(new JLabel("File Tree of " + defaultDir.getAbsolutePath()), BorderLayout.NORTH);
        fileTreePanel.add(new JScrollPane(tree), BorderLayout.CENTER);
        fileTreePanel.revalidate();
        fileTreePanel.repaint();
    }

    // Helper method: Only add directories and HTML files to the tree.
    private void addFileNodes(DefaultMutableTreeNode node, File file) {
        if (file.isDirectory()) {
            File[] files = file.listFiles();
            if (files != null) {
                for (File f : files) {
                    // Only add directories and files ending with .html
                    if (f.isDirectory() || (f.isFile() && f.getName().toLowerCase().endsWith(".html"))) {
                        DefaultMutableTreeNode childNode = new DefaultMutableTreeNode(f);
                        node.add(childNode);
                        if (f.isDirectory()) {
                            addFileNodes(childNode, f);
                        }
                    }
                }
            }
        }
    }


    private JPanel createVersionPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.add(new JLabel("Version Explorer"), BorderLayout.NORTH);

        File versionDir = new File("versions");
        if (!versionDir.exists()) {
            versionDir.mkdir();
        }

        DefaultListModel<File> versionListModel = new DefaultListModel<>();
        JList<File> versionList = new JList<>(versionListModel);
        versionList.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                          boolean isSelected, boolean cellHasFocus) {
                JLabel label = (JLabel) super.getListCellRendererComponent(list, value, index,
                        isSelected, cellHasFocus);
                if (value instanceof File) {
                    label.setText(((File) value).getName());
                }
                return label;
            }
        });

        // Populate the list with version files
        File[] files = versionDir.listFiles((dir, name) -> name.toLowerCase().endsWith(".html"));
        if (files != null) {
            for (File file : files) {
                versionListModel.addElement(file);
            }
        }

        versionList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        versionList.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    File selectedVersion = versionList.getSelectedValue();
                    if (selectedVersion != null) {
                        int option = JOptionPane.showConfirmDialog(panel,
                                "Load this version into the editor? Unsaved changes will be lost.",
                                "Open Version", JOptionPane.YES_NO_OPTION);
                        if (option == JOptionPane.YES_OPTION) {
                            ((Syngrafi) SwingUtilities.getWindowAncestor(panel)).openDocument(selectedVersion);
                        }
                    }
                }
            }
        });

        JButton refreshButton = new JButton("Refresh");
        refreshButton.addActionListener(e -> {
            versionListModel.clear();
            File[] newFiles = versionDir.listFiles((dir, name) -> name.toLowerCase().endsWith(".html"));
            if (newFiles != null) {
                for (File file : newFiles) {
                    versionListModel.addElement(file);
                }
            }
        });

        panel.add(new JScrollPane(versionList), BorderLayout.CENTER);
        panel.add(refreshButton, BorderLayout.SOUTH);
        return panel;
    }


    /**
     * A sub-panel that draws a simple pie chart of AI vs. Human usage
     */
    private static class UsagePiePanel extends JPanel {
        private Syngrafi parentFrame;
        public UsagePiePanel(Syngrafi parentFrame) {
            this.parentFrame = parentFrame;
        }
        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            if (parentFrame == null) return;
            TextEditor editor = parentFrame.getTextEditor();
            if (editor == null) return;

            int ai = editor.getAICharCount();
            int human = editor.getHumanCharCount();
            int total = ai + human;
            if (total == 0) {
                g.drawString("No usage data yet", 10, 20);
                return;
            }
            double aiFrac = (double) ai / total;

            // Draw pie chart
            int x = 40, y = 40, w = 120, h = 120;
            Graphics2D g2 = (Graphics2D) g;
            int aiAngle = (int) Math.round(aiFrac * 360);
            g2.setColor(Color.RED);
            g2.fillArc(x, y, w, h, 0, aiAngle);
            g2.setColor(Color.BLUE);
            g2.fillArc(x, y, w, h, aiAngle, 360 - aiAngle);

            // Reposition legend below the pie chart instead of to the right
            int legendX = x;
            int legendY = y + h + 25;
//            if light theme do black, if dark theme do white
            if (UIManager.getLookAndFeel().getName().contains("Dark")) {
                g2.setColor(Color.WHITE);
            } else {
                g2.setColor(Color.BLACK);
            }
            g2.drawString("AI: " + ai + " chars", legendX, legendY);
            g2.drawString("Human: " + human + " chars", legendX, legendY + 15);
            g2.drawString("Total: " + total + " chars", legendX, legendY + 30);
        }
    }

    /**
     * A panel that lists the headings (H1, H2) in the doc;
     * clicking jumps the caret to that offset.
     */
    private static class SectionExplorer extends JPanel {
        private Syngrafi parentFrame;
        private DefaultListModel<String> headingListModel;
        private JList<String> headingList;
        private List<Integer> headingOffsets;

        public SectionExplorer(Syngrafi parentFrame) {
            super(new BorderLayout());
            this.parentFrame = parentFrame;
            headingListModel = new DefaultListModel<>();
            headingList = new JList<>(headingListModel);
            headingOffsets = new ArrayList<>();

            headingList.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    if (e.getClickCount() == 2) {
                        int index = headingList.locationToIndex(e.getPoint());
                        if (index >= 0 && index < headingOffsets.size()) {
                            int offset = headingOffsets.get(index);
                            // jump there
                            TextEditor editor = parentFrame.getTextEditor();
                            editor.setCaretPosition(Math.min(offset, editor.getDocument().getLength()));
                            editor.requestFocusInWindow();
                        }
                    }
                }
            });

            add(new JLabel("Section Explorer (H1/H2)"), BorderLayout.NORTH);
            add(new JScrollPane(headingList), BorderLayout.CENTER);
        }

        public void refresh() {
            headingListModel.clear();
            headingOffsets.clear();

            TextEditor editor = parentFrame.getTextEditor();
            if (editor == null) return;

            HTMLDocument htmlDoc = (HTMLDocument) editor.getDocument();
            ElementIterator iterator = new ElementIterator(htmlDoc);
            Element elem;
            while ((elem = iterator.next()) != null) {
                AttributeSet as = elem.getAttributes();
                Object elementName = as.getAttribute(StyleConstants.NameAttribute);
                if (elementName != null && elementName instanceof HTML.Tag) {
                    HTML.Tag tag = (HTML.Tag) elementName;
                    if (tag == HTML.Tag.H1 || tag == HTML.Tag.H2) {
                        int start = elem.getStartOffset();
                        int end = elem.getEndOffset();
                        try {
                            String headingText = htmlDoc.getText(start, end - start).trim();
                            headingOffsets.add(start);
                            // For H1, no indentation; for H2, indent with four spaces.
                            if (tag == HTML.Tag.H1) {
                                headingListModel.addElement(headingText);
                            } else {
                                headingListModel.addElement("    " + headingText);
                            }
                        } catch (BadLocationException ex) {
                            ex.printStackTrace();
                        }
                    }
                }
            }
        }
    }

    private void setupUpdateTimer() {
        updateTimer = new Timer(2000, e -> updateStats()); // Update every 2 seconds
        updateTimer.setInitialDelay(500); // Initial update after 0.5s
        updateTimer.start();
    }
    
    // Public method to stop the timer when the panel is no longer needed
    public void stopUpdateTimer() {
         if (updateTimer != null && updateTimer.isRunning()) {
             updateTimer.stop();
         }
    }

    // Call this method to update the stats display
    public void updateStats() {
        TextEditor editor = parentFrame.getTextEditor();
        if (editor != null) {
            // Call editor's methods to get current stats
            int words = editor.countWords(); // Assuming countWords is now public
            int aiChars = editor.getAICharCount();
            int humanChars = editor.getHumanCharCount();

            // Update labels (ensure Swing updates happen on EDT)
            SwingUtilities.invokeLater(() -> {
                wordCountLabel.setText("Words: " + words);
                aiCharCountLabel.setText("AI Chars: " + aiChars);
                humanCharCountLabel.setText("Human Chars: " + humanChars);
            });
        }
    }
}

