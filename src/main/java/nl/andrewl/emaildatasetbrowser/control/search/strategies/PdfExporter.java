package nl.andrewl.emaildatasetbrowser.control.search.strategies;

import java.io.IOException;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.function.Consumer;
import java.util.HashSet;

import com.itextpdf.text.BaseColor;
import com.itextpdf.text.Chunk;
import com.itextpdf.text.Document;
import com.itextpdf.text.DocumentException;
import com.itextpdf.text.Font;
import com.itextpdf.text.FontFactory;
import com.itextpdf.text.Paragraph;
import com.itextpdf.text.pdf.BaseFont;
import com.itextpdf.text.pdf.PdfWriter;

import nl.andrewl.email_indexer.data.EmailEntryPreview;
import nl.andrewl.email_indexer.data.EmailRepository;

/**
 * Exports complete threads of emails as separate PDF files.
 */
public final class PdfExporter implements ResultsExporter {

    public static Font HEADER_TEXT = FontFactory.getFont(FontFactory.COURIER, FontFactory.defaultEncoding,
            BaseFont.EMBEDDED, 16, Font.BOLD, BaseColor.BLACK);
    public static Font SUBHEADER_TEXT = FontFactory.getFont(FontFactory.COURIER, FontFactory.defaultEncoding,
            BaseFont.EMBEDDED, 12, Font.BOLD, BaseColor.BLACK);
    public static Font REGULAR_TEXT = FontFactory.getFont(FontFactory.COURIER, FontFactory.defaultEncoding,
            BaseFont.EMBEDDED, 11, Font.NORMAL, BaseColor.BLACK);

    // HACK: this circumvents a bug in the recursiveLoad method in the email
    // repository.
    private HashSet<String> processedMailTracker;

    @Override
    public void writeExport(List<EmailEntryPreview> emails, EmailRepository repo, String query, Path file,
            Consumer<String> messageConsumer) throws IOException {

        this.processedMailTracker = new HashSet<String>();

        File workingDirFile = new File(file.getParent().toString() + "/output/");
        workingDirFile.mkdirs();
        Path workingDir = workingDirFile.toPath();

        try {
            makeMetaFile(emails, repo, query, workingDir, messageConsumer, workingDir);
        } catch (Exception e) {
            throw new IOException("Could not export PDF.", e);
        }

        for (int i = 0; i < emails.size(); i++) {
            messageConsumer.accept("Exporting email #" + i);
            EmailEntryPreview email = emails.get(i);
            repo.loadRepliesRecursive(email);
            int replyId = i + 1;
            try {
                exportEmail(workingDir, email, repo, replyId);
            } catch (Exception e) {
                throw new IOException("Could not export PDF.", e);
            }
        }
    }

    private void makeMetaFile(List<EmailEntryPreview> emails, EmailRepository repo, String query, Path file,
            Consumer<String> messageConsumer, Path workingDir) throws DocumentException, IOException {
        Document document = new Document();

        Path targetPath = Path.of(workingDir.toString() + "/meta-data.pdf");
        PdfWriter.getInstance(document, new FileOutputStream(targetPath.toString()));

        document.open();

        addText("Export Meta Data", document, HEADER_TEXT);

        addText("Query:\n", document, SUBHEADER_TEXT);
        addText(query + "\n\n", document, REGULAR_TEXT);

        addText("Exported at:\n", document, SUBHEADER_TEXT);
        addText(ZonedDateTime.now() + "\n\n", document, REGULAR_TEXT);

        addText("Tags:\n", document, SUBHEADER_TEXT);
        addText(String.join(", ", repo.getAllTags()) + "\n\n", document, REGULAR_TEXT);

        addText("Total emails", document, SUBHEADER_TEXT);
        addText(emails.size() + "\n\n", document, REGULAR_TEXT);

        document.close();
    }

    private void addText(String text, Document document, Font font) throws DocumentException {
        Chunk chunk = new Chunk(text, font);
        Paragraph paragraph = new Paragraph(chunk);
        document.add(paragraph);
    }

    private void exportEmail(Path workingDir, EmailEntryPreview email, EmailRepository repository, int mailIndex)
            throws IOException, DocumentException {
        Document document = new Document();
        Path targetPath = Path.of(workingDir.toString() + "/email-" + mailIndex + ".pdf");
        PdfWriter.getInstance(document, new FileOutputStream(targetPath.toString()));
        document.open();
        exportEmailThread(document, email, repository, "#" + mailIndex);
        document.close();
    }

    private void exportEmailThread(Document document, EmailEntryPreview email, EmailRepository repository,
            String emailId)
            throws DocumentException {

        if (processedMailTracker.contains(email.messageId())) {
            return;
        } else {
            processedMailTracker.add(email.messageId());
        }

        addText("Email " + emailId, document, HEADER_TEXT);

        addText("Subject::\n", document, SUBHEADER_TEXT);
        addText(email.subject() + "\n\n", document, REGULAR_TEXT);

        addText("Sent from:\n", document, SUBHEADER_TEXT);
        addText(email.sentFrom() + "\n\n", document, REGULAR_TEXT);

        addText("Date:", document, SUBHEADER_TEXT);
        addText(email.date() + "\n\n", document, REGULAR_TEXT);

        addText("Tags::\n", document, SUBHEADER_TEXT);
        addText(String.join(", ", email.tags()) + "\n\n", document, REGULAR_TEXT);

        addText("Replies:\n", document, SUBHEADER_TEXT);
        addText(email.replies().size() + "\n\n", document, REGULAR_TEXT);

        addText("Body:\n\n", document, SUBHEADER_TEXT);
        repository.getBody(email.messageId()).ifPresent(body -> {
            try {
                addText(body, document, REGULAR_TEXT);
            } catch (Exception e) {
                System.out.println("ERROR DURING BODY ACQUISITION:\n" + e.getMessage());
            }
        });

        document.newPage();

        List<EmailEntryPreview> replies = email.replies();
        for (int i = 0; i < replies.size(); i++) {
            EmailEntryPreview reply = replies.get(i);
            int replyId = i + 1;
            exportEmailThread(document, reply, repository, emailId + "." + replyId);
        }
    }
}
