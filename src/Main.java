import java.awt.BorderLayout;
import java.awt.Dimension;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.filechooser.FileNameExtensionFilter;

public class Main {
    private final Path defaultDirectory = resolveDefaultDirectory();
    private final List<Path> selectedFiles = new ArrayList<>();
    private final JList<String> selectedFilesList = new JList<>();
    private final JTextArea outputArea = new JTextArea();

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new Main().createAndShowUi());
    }

    private void createAndShowUi() {
        JFrame frame = new JFrame("Choreo Trajectory X-Axis Mirrorer");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setLayout(new BorderLayout(10, 10));

        JPanel topPanel = new JPanel(new BorderLayout(8, 8));
        topPanel.setBorder(BorderFactory.createEmptyBorder(12, 12, 0, 12));
        topPanel.add(new JLabel("Choreo Trajectory X-Axis Mirrorer"), BorderLayout.NORTH);
        topPanel.add(new JLabel("Default file location: " + defaultDirectory), BorderLayout.SOUTH);
        frame.add(topPanel, BorderLayout.NORTH);

        selectedFilesList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        JScrollPane listScroll = new JScrollPane(selectedFilesList);
        listScroll.setBorder(BorderFactory.createTitledBorder("Selected .traj Files"));
        listScroll.setPreferredSize(new Dimension(640, 220));

        outputArea.setEditable(false);
        JScrollPane outputScroll = new JScrollPane(outputArea);
        outputScroll.setBorder(BorderFactory.createTitledBorder("Results"));
        outputScroll.setPreferredSize(new Dimension(640, 180));

        JPanel centerPanel = new JPanel(new BorderLayout(8, 8));
        centerPanel.setBorder(BorderFactory.createEmptyBorder(0, 12, 0, 12));
        centerPanel.add(listScroll, BorderLayout.CENTER);
        centerPanel.add(outputScroll, BorderLayout.SOUTH);
        frame.add(centerPanel, BorderLayout.CENTER);

        JButton selectButton = new JButton("Select .traj Files");
        selectButton.addActionListener(e -> openFileChooser(frame));

        JButton mirrorButton = new JButton("Mirror Selected");
        mirrorButton.addActionListener(e -> mirrorSelectedFiles(frame));

        JPanel buttonPanel = new JPanel();
        buttonPanel.setBorder(BorderFactory.createEmptyBorder(0, 12, 12, 12));
        buttonPanel.add(selectButton);
        buttonPanel.add(mirrorButton);
        frame.add(buttonPanel, BorderLayout.SOUTH);

        frame.pack();
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }

    private Path resolveDefaultDirectory() {
        try {
            Path codeSourcePath = Paths
                    .get(Main.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                    .toAbsolutePath()
                    .normalize();

            if (Files.isRegularFile(codeSourcePath)) {
                Path parent = codeSourcePath.getParent();
                if (parent != null) {
                    return parent;
                }
            }

            if (Files.isDirectory(codeSourcePath)) {
                return codeSourcePath;
            }
        } catch (Exception ex) {
            // Fall through to current working directory.
        }

        return Paths.get("").toAbsolutePath().normalize();
    }

    private void openFileChooser(JFrame parentFrame) {
        JFileChooser chooser = new JFileChooser(defaultDirectory.toFile());
        chooser.setDialogTitle("Choose .traj files");
        chooser.setMultiSelectionEnabled(true);
        chooser.setFileFilter(new FileNameExtensionFilter("Trajectory Files (*.traj)", "traj"));
        chooser.setAcceptAllFileFilterUsed(false);

        int result = chooser.showOpenDialog(parentFrame);
        if (result != JFileChooser.APPROVE_OPTION) {
            return;
        }

        selectedFiles.clear();
        for (java.io.File file : chooser.getSelectedFiles()) {
            selectedFiles.add(file.toPath());
        }
        refreshSelectedList();
        outputArea.setText("Selected " + selectedFiles.size() + " file(s).\n");
    }

    private void mirrorSelectedFiles(JFrame parentFrame) {
        if (selectedFiles.isEmpty()) {
            JOptionPane.showMessageDialog(parentFrame, "Select at least one .traj file first.");
            return;
        }

        StringBuilder resultLog = new StringBuilder();
        int successCount = 0;

        for (Path inputFile : selectedFiles) {
            try {
                Path outputDirectory = inputFile.getParent();
                String outputPath = PathMirrorUtil.mirrorTrajAcrossXAxis(inputFile.toString(), outputDirectory);
                successCount++;
                resultLog.append("Mirrored: ").append(inputFile).append(" -> ").append(outputPath).append("\n");
            } catch (Exception ex) {
                resultLog.append("Failed: ").append(inputFile).append(" (" + ex.getMessage() + ")\n");
            }
        }

        resultLog.append("\nCompleted: ").append(successCount).append(" / ").append(selectedFiles.size());
        outputArea.setText(resultLog.toString());
    }

    private void refreshSelectedList() {
        String[] paths = selectedFiles.stream().map(Path::toString).toArray(String[]::new);
        selectedFilesList.setListData(paths);
    }
}
