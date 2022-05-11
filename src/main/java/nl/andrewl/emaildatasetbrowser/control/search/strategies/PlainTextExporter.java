
package nl.andrewl.emaildatasetbrowser.control.search.strategies;

import nl.andrewl.email_indexer.data.EmailEntryPreview;
import nl.andrewl.email_indexer.data.EmailRepository;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.time.ZonedDateTime;

public final class PlainTextExporter implements ResultsExporter {

    @Override
    public void writeExport(List<EmailEntryPreview> emails, EmailRepository repo, String query, Path file,
            Consumer<String> messageConsumer) throws IOException {
        try (PrintWriter p = new PrintWriter(new FileWriter(file.toFile()), false)) {
            p.println("Query: " + query);
            p.println("Exported at: " + ZonedDateTime.now());
            p.println("Tags: " + String.join(", ", repo.getAllTags()));
            p.println("Total emails: " + emails.size());
            p.println("\n");
            for (int i = 0; i < emails.size(); i++) {
                messageConsumer.accept("Exporting email #" + (i + 1));
                var email = emails.get(i);
                repo.loadRepliesRecursive(email);
                p.println("Email #" + (i + 1));
                exportEmail(email, repo, p, 0);
            }
        }
    }

    private void exportEmail(EmailEntryPreview email, EmailRepository repo, PrintWriter p, int indentLevel) {
        String indent = "\t".repeat(indentLevel);
        p.println(indent + "Message id: " + email.messageId());
        p.println(indent + "Subject: " + email.subject());
        p.println(indent + "Sent from: " + email.sentFrom());
        p.println(indent + "Date: " + email.date());
        p.println(indent + "Tags: " + String.join(", ", email.tags()));
        p.println(indent + "Hidden: " + email.hidden());
        repo.getBody(email.messageId()).ifPresent(body -> {
            p.println(indent + "Body---->>>");
            body.trim().lines().forEachOrdered(line -> p.println(indent + line));
            p.println(indent + "-------->>>");
        });
        if (!email.replies().isEmpty()) {
            p.println("Replies:");
            for (int i = 0; i < email.replies().size(); i++) {
                var reply = email.replies().get(i);
                p.println("\t" + indent + "Reply #" + (i + 1));
                exportEmail(reply, repo, p, indentLevel + 1);
                p.println();
            }
        }
        p.println();
    }
}
