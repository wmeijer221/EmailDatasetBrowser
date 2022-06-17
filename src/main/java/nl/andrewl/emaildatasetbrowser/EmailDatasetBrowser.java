package nl.andrewl.emaildatasetbrowser;

import nl.andrewl.email_indexer.data.EmailDataset;
import nl.andrewl.email_indexer.data.EmailRepository;
import nl.andrewl.email_indexer.data.TagRepository;
import nl.andrewl.emaildatasetbrowser.control.*;
import nl.andrewl.emaildatasetbrowser.control.email.*;
import nl.andrewl.emaildatasetbrowser.control.tag.ManageTagsAction;
import nl.andrewl.emaildatasetbrowser.view.DatasetChangeListener;
import nl.andrewl.emaildatasetbrowser.view.ProgressDialog;
import nl.andrewl.emaildatasetbrowser.view.email.EmailViewPanel;
import nl.andrewl.emaildatasetbrowser.view.search.LuceneSearchPanel;
import nl.andrewl.emaildatasetbrowser.view.search.SimpleBrowsePanel;

import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.lang.ref.WeakReference;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.prefs.Preferences;

/**
 * The main JFrame for the dataset browser application.
 */
public class EmailDatasetBrowser extends JFrame {
	public static final String PREFERENCES_NODE_NAME = "email_dataset_browser_prefs";

	private final EmailViewPanel emailViewPanel;
	private EmailDataset currentDataset = null;

	/**
	 * A set of listeners that are updated when the state of the browser app
	 * changes. This is a set of weak references, to prevent any dangling
	 * reference memory leaks.
	 */
	private final Set<WeakReference<DatasetChangeListener>> datasetChangeListeners = new HashSet<>();

	public EmailDatasetBrowser () {
		super("Email Dataset Browser");
		this.setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
		this.emailViewPanel = new EmailViewPanel(this);
		SimpleBrowsePanel browsePanel = new SimpleBrowsePanel(emailViewPanel);
		LuceneSearchPanel searchPanel = new LuceneSearchPanel(emailViewPanel);
		datasetChangeListeners.add(new WeakReference<>(emailViewPanel));
		datasetChangeListeners.add(new WeakReference<>(browsePanel));
		datasetChangeListeners.add(new WeakReference<>(searchPanel));

		JTabbedPane searchPane = new JTabbedPane();
		searchPane.add("Browse", browsePanel);
		searchPane.add("Lucene Search", searchPanel);
		JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
		splitPane.add(searchPane);
		splitPane.add(emailViewPanel);
		setContentPane(splitPane);

		this.setJMenuBar(buildMenu());
		this.setPreferredSize(new Dimension(1000, 600));
		this.pack();
		this.setLocationRelativeTo(null);
		setDataset(null);

		this.addWindowListener(new WindowAdapter() {
			@Override
			public void windowClosing(WindowEvent e) {
				closeDataset(null).thenRun(() -> dispose());
			}
		});
		SwingUtilities.invokeLater(() -> new DatasetOpenAction(this).tryOpenLastDataset());
	}

	public EmailDataset getCurrentDataset() {
		return this.currentDataset;
	}

	private void setDataset(EmailDataset ds) {
		currentDataset = ds;
		notifyListeners();
	}

	private JMenuBar buildMenu() {
		JMenuBar menuBar = new JMenuBar();
		JMenu fileMenu = new JMenu("File");
		fileMenu.add(new JMenuItem(new DatasetOpenAction(this)));
		fileMenu.add(new JMenuItem(new UpgradeDatasetAction(this)));
		fileMenu.add(new JMenuItem(new GenerateDatasetAction(this)));
		fileMenu.add(new JMenuItem(new RegenerateIndexesAction(this)));
		fileMenu.add(new JMenuItem(new ExportDatasetAction(this)));
		fileMenu.add(new JMenuItem(new CloseDatasetAction(this)));
		menuBar.add(fileMenu);

		JMenu filterMenu = new JMenu("Filter");
		filterMenu.add(new JMenuItem(new HideAction(emailViewPanel)));
		filterMenu.add(new JMenuItem(new ShowAction(emailViewPanel)));
		filterMenu.add(new JMenuItem(new HideAllByAuthorAction(emailViewPanel)));
		filterMenu.add(new JMenuItem(new HideAllByBodyAction(emailViewPanel)));
		filterMenu.add(new JMenuItem(new HideBySqlAction(this)));
		filterMenu.add(new JMenuItem(new DeleteHiddenAction(emailViewPanel)));
		menuBar.add(filterMenu);

		JMenu viewMenu = new JMenu("View");
		viewMenu.add(new JMenuItem(new ViewSelectionAction(this)));
		menuBar.add(viewMenu);

		JMenu tagMenu = new JMenu("Tag");
		tagMenu.add(new JMenuItem(new ManageTagsAction(this)));
		menuBar.add(tagMenu);

		return menuBar;
	}

	private void cleanListeners() {
		datasetChangeListeners.removeIf(ls -> ls.get() == null);
	}

	public void notifyListeners() {
		datasetChangeListeners.forEach(ls -> {
			DatasetChangeListener listener = ls.get();
			if (listener != null) listener.datasetChanged(currentDataset);
		});
	}

	public void notifyTagsChanged() {
		datasetChangeListeners.forEach(ls -> {
			DatasetChangeListener listener = ls.get();
			if (listener != null) listener.tagsChanged(currentDataset);
		});
	}

	public void addListener(DatasetChangeListener listener) {
		datasetChangeListeners.add(new WeakReference<>(listener));
		cleanListeners();
	}

	/**
	 * Opens a dataset in the app. If this app already has a dataset loaded,
	 * then the current dataset will be closed first.
	 * @param dsPath The path to the dataset.
	 * @return A future that completes when the dataset is opened.
	 */
	public CompletableFuture<Void> openDataset(Path dsPath) {
		ProgressDialog dialog = new ProgressDialog(
				this,
				"Opening Dataset",
				"Opening dataset at " + dsPath.toAbsolutePath(),
				true,
				false,
				false,
				true
		);
		dialog.start();
		return closeDataset(dialog)
				.thenCompose(unused -> EmailDataset.open(dsPath))
				.handle((dataset, throwable) -> {
					if (throwable != null) {
						dialog.append("Could not display dataset in the browser: " + throwable.getMessage());
					} else {
						setDataset(dataset);

						var repo = new EmailRepository(dataset);
						var tagRepo = new TagRepository(dataset);
						String message = "Opened dataset from %s with\n%d emails,\n%d tags,\n%d tagged emails".formatted(
								dataset.getOpenDir(),
								repo.countEmails(),
								tagRepo.countTags(),
								repo.countTaggedEmails()
						);
						dialog.append(message);
					}
					dialog.done();
					return null;
				});
	}

	/**
	 * Closes this app's opened dataset, if one is opened.
	 * @param existingDialog A dialog to append progress messages to. If this
	 *                       is null, then the app will make a new dialog to
	 *                       display messages instead.
	 * @return A future that completes when the app no longer has any dataset
	 * open.
	 */
	public CompletableFuture<Void> closeDataset(ProgressDialog existingDialog) {
		if (currentDataset == null) return CompletableFuture.completedFuture(null);
		ProgressDialog dialog = existingDialog;
		if (existingDialog == null) {
			dialog = new ProgressDialog(
					this,
					"Closing Dataset",
					"Closing the current dataset.",
					true,
					false,
					false,
					true
			);
			dialog.start();
		}
		dialog.appendF("Closing the currently open dataset at %s", currentDataset.getOpenDir());
		final ProgressDialog finalDialog = dialog;
		return currentDataset.close().handle((unused, throwable) -> {
			if (throwable != null) {
				throwable.printStackTrace();
				JOptionPane.showMessageDialog(
						this,
						"An error occurred while closing the database:\n" + throwable.getMessage(),
						"Error",
						JOptionPane.ERROR_MESSAGE
				);
			} else {
				finalDialog.append("Dataset closed successfully.");
			}
			// If no existing dialog was provided, mark ours as done.
			if (existingDialog == null) {
				finalDialog.done();
			}
			setDataset(null);
			return null;
		});
	}

	/**
	 * Gets the preferences root for this app. Use this as a basis for any
	 * preferences in this app.
	 * @return The root preference node.
	 */
	public static Preferences getPreferences() {
		return Preferences.userRoot().node(PREFERENCES_NODE_NAME);
	}
}
