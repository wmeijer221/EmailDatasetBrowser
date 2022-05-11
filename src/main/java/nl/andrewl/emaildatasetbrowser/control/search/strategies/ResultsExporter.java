package nl.andrewl.emaildatasetbrowser.control.search.strategies;

import nl.andrewl.email_indexer.data.EmailEntryPreview;
import nl.andrewl.email_indexer.data.EmailRepository;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

public interface ResultsExporter {
    public void writeExport(List<EmailEntryPreview> emails, EmailRepository repo, String query, Path file,
            Consumer<String> messageConsumer) throws IOException;
}
