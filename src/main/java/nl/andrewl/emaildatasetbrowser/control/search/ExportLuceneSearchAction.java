package nl.andrewl.emaildatasetbrowser.control.search;

import nl.andrewl.email_indexer.data.EmailEntryPreview;
import nl.andrewl.email_indexer.data.EmailRepository;
import nl.andrewl.email_indexer.data.search.EmailIndexSearcher;
import nl.andrewl.emaildatasetbrowser.control.search.strategies.PdfExporter;
import nl.andrewl.emaildatasetbrowser.control.search.strategies.PlainTextExporter;
import nl.andrewl.emaildatasetbrowser.control.search.strategies.ResultsExporter;
import nl.andrewl.emaildatasetbrowser.view.ProgressDialog;
import nl.andrewl.emaildatasetbrowser.view.search.LuceneSearchPanel;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

public class ExportLuceneSearchAction implements ActionListener {
    private final LuceneSearchPanel searchPanel;

    public ExportLuceneSearchAction(LuceneSearchPanel searchPanel) {
        this.searchPanel = searchPanel;
    }

    @Override
    public void actionPerformed(ActionEvent e) {
        String query = searchPanel.getQuery();
        if (query == null || searchPanel.getDataset() == null)
            return;

        JFileChooser fc = new JFileChooser(".");
        fc.setFileFilter(new FileNameExtensionFilter("Text files", "txt"));
        fc.setFileFilter(new FileNameExtensionFilter("PDF files", "pdf"));
        fc.setFileSelectionMode(JFileChooser.FILES_AND_DIRECTORIES);
        fc.setAcceptAllFileFilterUsed(true);
        int result = fc.showSaveDialog(searchPanel);
        if (result != JFileChooser.APPROVE_OPTION)
            return;

        Path file = fc.getSelectedFile().toPath();
        String filename = file.getFileName().toString().toLowerCase();
        String ext = filename.substring(filename.lastIndexOf("."));

        ProgressDialog progress = ProgressDialog.minimalText(searchPanel, "Exporting Query Results");
        progress.append("Generating export for query: \"%s\"".formatted(query));
        var repo = new EmailRepository(searchPanel.getDataset());
        new EmailIndexSearcher().searchAsync(searchPanel.getDataset(), query)
                .handleAsync((emailIds, throwable) -> {
                    if (throwable != null) {
                        progress.append("An error occurred while searching: " + throwable.getMessage());
                    } else {
                        progress.append("Found %d emails.".formatted(emailIds.size()));
                        progress.appendF("Exporting the top %d emails.", searchPanel.getResultCount());
                        try {
                            List<EmailEntryPreview> emails = emailIds.parallelStream()
                                    .map(id -> repo.findPreviewById(id).orElse(null))
                                    .filter(Objects::nonNull)
                                    .limit(searchPanel.getResultCount())
                                    .peek(repo::loadRepliesRecursive)
                                    .toList();
                            ResultsExporter exporter = switch (ext) {
                                case ".pdf" -> new PdfExporter();
                                case ".txt" -> new PlainTextExporter();
                                default -> throw new IllegalArgumentException("Unsupported file type: " + ext);
                            };
                            exporter.writeExport(emails, repo, query, file, progress);
                        } catch (IOException ex) {
                            progress.append("An error occurred while exporting: " + ex.getMessage());
                            ex.printStackTrace();
                        }
                    }
                    progress.done();
                    return null;
                });
    }
}
