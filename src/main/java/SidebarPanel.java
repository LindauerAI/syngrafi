import javax.swing.*;
import javax.swing.text.Document;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import java.awt.*;
import java.awt.event.*;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * A panel that offers four sidebar modes:
 *  1) File tree
 *  2) Version explorer
 *  3) Usage pie chart of AI vs Human
 *  4) Section explorer for headings
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
        
        fileTreePanel = createFileTreePanel();
        versionPanel = createVersionPanel();
        usagePanel = new UsagePiePanel(parentFrame);
        sectionExplorer = new SectionExplorer(parentFrame);
        
        cardContainer.add(fileTreePanel, "File Tree");
        cardContainer.add(versionPanel, "Version Explorer");
        cardContainer.add(usagePanel, "Usage Chart");
        cardContainer.add(sectionExplorer, "Section Explorer");
        
        add(cardContainer, BorderLayout.CENTER);
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
        // In real code, you'd load the file system, build a tree model
        // We'll just do a trivial example
        File defaultDir = parentFrame.getDefaultDirectory();
        
        DefaultMutableTreeNode root = new DefaultMutableTreeNode(defaultDir.getName());
        for (File f : defaultDir.listFiles()) {
            root.add(new DefaultMutableTreeNode(f.getName()));
        }
        JTree tree = new JTree(root);
        
        // Replace the old child
        fileTreePanel.removeAll();
        fileTreePanel.setLayout(new BorderLayout());
        fileTreePanel.add(new JLabel("File Tree of " + defaultDir), BorderLayout.NORTH);
        fileTreePanel.add(new JScrollPane(tree), BorderLayout.CENTER);
        fileTreePanel.revalidate();
        fileTreePanel.repaint();
    }
    
    private JPanel createVersionPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.add(new JLabel("Version Explorer Placeholder"), BorderLayout.NORTH);
        
        // In real code, you might list the "versions" folder, etc.
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
            // figure out AI vs human from the editor
            if (parentFrame == null) return;
            ImprovedTextEditor editor = parentFrame.getTextEditor();
            if (editor == null) return;
            
            int ai = editor.getAICharCount();
            int human = editor.getHumanCharCount();
            int total = ai + human;
            if (total == 0) {
                g.drawString("No usage data yet", 10, 20);
                return;
            }
            double aiFrac = (double) ai / total;
            double humanFrac = (double) human / total;
            
            // Pie chart
            int x = 40, y = 40, w = 120, h = 120;
            Graphics2D g2 = (Graphics2D) g;
            
            // AI slice
            int aiAngle = (int) Math.round(aiFrac * 360);
            
            g2.setColor(Color.RED);
            g2.fillArc(x, y, w, h, 0, aiAngle);
            
            // Human slice
            g2.setColor(Color.BLUE);
            g2.fillArc(x, y, w, h, aiAngle, 360 - aiAngle);
            
            // Legend
            g2.setColor(Color.BLACK);
            g2.drawString("AI: " + ai + " chars", x + w + 20, y + 20);
            g2.drawString("Human: " + human + " chars", x + w + 20, y + 40);
            g2.drawString("Total: " + total + " chars", x + w + 20, y + 60);
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
                            ImprovedTextEditor editor = parentFrame.getTextEditor();
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
            
            ImprovedTextEditor editor = parentFrame.getTextEditor();
            if (editor == null) return;
            
            // We'll scan the entire doc text for <h1> or <h2> tags
            // Then we find their offsets
            try {
                Document doc = editor.getDocument();
                String fullText = doc.getText(0, doc.getLength());
                
                // We'll do a simplistic approach: find <h1> or <h2> in the text
                // (the user is storing them in the doc as actual tags)
                int index = 0;
                while (index < fullText.length()) {
                    int h1Pos = fullText.indexOf("<h1>", index);
                    int h2Pos = fullText.indexOf("<h2>", index);
                    
                    if (h1Pos < 0 && h2Pos < 0) break;
                    
                    int foundPos;
                    boolean isH1;
                    if (h1Pos < 0) {
                        foundPos = h2Pos;
                        isH1 = false;
                    } else if (h2Pos < 0) {
                        foundPos = h1Pos;
                        isH1 = true;
                    } else {
                        // whichever is smaller
                        if (h1Pos < h2Pos) {
                            foundPos = h1Pos;
                            isH1 = true;
                        } else {
                            foundPos = h2Pos;
                            isH1 = false;
                        }
                    }
                    
                    // Then find the closing tag
                    int closeTag = fullText.indexOf(isH1 ? "</h1>" : "</h2>", foundPos);
                    if (closeTag < 0) {
                        // malformed
                        break;
                    }
                    
                    // The heading text is between foundPos+4..closeTag if H1,
                    // or foundPos+4..closeTag if H2 as well
                    String headingText = fullText.substring(foundPos+4, closeTag).trim();
                    headingOffsets.add(foundPos);
                    if (isH1) {
                        headingListModel.addElement("H1: " + headingText);
                    } else {
                        headingListModel.addElement("H2: " + headingText);
                    }
                    
                    index = closeTag+5; // move past
                }
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        }
    }
}
