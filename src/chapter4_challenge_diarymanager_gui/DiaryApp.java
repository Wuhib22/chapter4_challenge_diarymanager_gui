package chapter4_challenge_diarymanager_gui;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.control.Alert.AlertType;
import javafx.scene.layout.*;
import javafx.scene.web.HTMLEditor;
import javafx.scene.web.WebView;
import javafx.stage.Stage;

import java.io.*;
import java.net.URL;
import java.nio.file.*;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.Timer;
import java.util.TimerTask;

public class DiaryApp extends Application {

    private Path diaryDir = Paths.get("diary");
    private ObservableList<LocalDate> entries = FXCollections.observableArrayList();
    private FilteredList<LocalDate> filteredEntries;
    private LocalDate currentDate;
    private String currentContent = "";

    private ListView<LocalDate> entryList;
    private HTMLEditor editor;
    private WebView preview;
    private TextField searchField;
    private DatePicker datePicker;
    private ProgressIndicator progress;
    private ToggleButton darkModeToggle;
    private BooleanProperty darkMode = new SimpleBooleanProperty(false);

    private Timer autoSaveTimer;

    @Override
    public void start(Stage primaryStage) {
        createDiaryDirectory();

        BorderPane root = new BorderPane();
        root.setPadding(new Insets(10));

        // Top bar
        HBox topBar = new HBox(10);
        topBar.setAlignment(Pos.CENTER_LEFT);
        Label title = new Label("Personal Diary Manager");
        title.setStyle("-fx-font-size: 20px; -fx-font-weight: bold;");
        
        searchField = new TextField();
        searchField.setPromptText("Search entries...");
        
        datePicker = new DatePicker();
        datePicker.setPromptText("Go to date");
        datePicker.setOnAction(e -> {
            if (datePicker.getValue() != null) {
                loadOrCreateDate(datePicker.getValue());
                datePicker.setValue(null);
            }
        });

        darkModeToggle = new ToggleButton("Dark Mode");
        progress = new ProgressIndicator(-1);
        progress.setVisible(false);
        
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        
        topBar.getChildren().addAll(title, spacer, searchField, datePicker, darkModeToggle, progress);

        // Left sidebar - entry list
        entryList = new ListView<>();
        entryList.setPrefWidth(250);
        entryList.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(LocalDate date, boolean empty) {
                super.updateItem(date, empty);
                if (empty || date == null) {
                    setText(null);
                } else {
                    setText(date.format(DateTimeFormatter.ofPattern("EEE, MMM d, yyyy")));
                }
            }
        });

        // Context Menu for Delete
        ContextMenu listContextMenu = new ContextMenu();
        MenuItem deleteItem = new MenuItem("Delete Entry");
        deleteItem.setOnAction(e -> {
            LocalDate selected = entryList.getSelectionModel().getSelectedItem();
            if (selected != null) {
                deleteEntry(selected);
            }
        });
        listContextMenu.getItems().add(deleteItem);
        entryList.setContextMenu(listContextMenu);

        Button newEntryBtn = new Button("New Entry Today");
        newEntryBtn.setMaxWidth(Double.MAX_VALUE);
        newEntryBtn.setOnAction(e -> loadOrCreateDate(LocalDate.now()));

        VBox leftPane = new VBox(10, new Label("Entries"), entryList, newEntryBtn);
        leftPane.setPadding(new Insets(0, 10, 0, 0));
        VBox.setVgrow(entryList, Priority.ALWAYS);

        // Center - Split editor / preview
        editor = new HTMLEditor();
        editor.setVisible(false);

        preview = new WebView();
        preview.setContextMenuEnabled(false);

        SplitPane centerSplit = new SplitPane(editor, preview);
        centerSplit.setDividerPositions(0.5);

        root.setTop(topBar);
        root.setLeft(leftPane);
        root.setCenter(centerSplit);
        BorderPane.setMargin(topBar, new Insets(0, 0, 10, 0));

        Scene scene = new Scene(root, 1200, 800);

        // Theme handling
        loadTheme(scene, false);
        darkModeToggle.selectedProperty().bindBidirectional(darkMode);
        darkMode.addListener((obs, old, dark) -> loadTheme(scene, dark));

        // Search filtering
        filteredEntries = new FilteredList<>(entries);
        entryList.setItems(filteredEntries);
        searchField.textProperty().addListener((obs, old, newText) -> {
            if (newText == null || newText.isBlank()) {
                filteredEntries.setPredicate(d -> true);
            } else {
                String lower = newText.toLowerCase();
                filteredEntries.setPredicate(date -> {
                    Path file = diaryDir.resolve(date + ".html");
                    if (Files.exists(file)) {
                        try {
                            String content = Files.readString(file).toLowerCase();
                            return content.contains(lower);
                        } catch (IOException ignored) {}
                    }
                    return false;
                });
            }
        });

        // Entry selection
        entryList.getSelectionModel().selectedItemProperty().addListener((obs, old, selected) -> {
            if (selected != null) {
                loadEntry(selected);
            }
        });

        // Auto-save setup
        autoSaveTimer = new Timer(true);
        editorFocusedProperty().addListener((obs, was, focused) -> {
            if (focused) startAutoSave();
            else stopAutoSave();
        });

        primaryStage.setScene(scene);
        primaryStage.setTitle("Personal Diary Manager");
        primaryStage.show();

        loadAllEntries();
        loadOrCreateDate(LocalDate.now());
    }
    
    private void loadTheme(Scene scene, boolean dark) {
        scene.getStylesheets().clear();
        String cssPath = dark ? "/resources/dark.css" : "/resources/light.css";
        URL cssResource = getClass().getResource(cssPath);
        if (cssResource != null) {
            scene.getStylesheets().add(cssResource.toExternalForm());
        } else {
             File f = new File("resources/" + (dark ? "dark.css" : "light.css"));
             if (f.exists()) {
                 scene.getStylesheets().add(f.toURI().toString());
             }
        }
    }

    private void createDiaryDirectory() {
        try {
            Files.createDirectories(diaryDir);
        } catch (IOException e) {
            showError("Could not create diary directory", e);
        }
    }

    private void loadAllEntries() {
        Task<Void> task = new Task<>() {
            @Override
            protected Void call() throws Exception {
                if (Files.exists(diaryDir)) {
                    try (DirectoryStream<Path> stream = Files.newDirectoryStream(diaryDir, "*.html")) {
                        Platform.runLater(() -> entries.clear());
                        for (Path file : stream) {
                            String name = file.getFileName().toString();
                            try {
                                LocalDate date = LocalDate.parse(name.substring(0, name.length() - 5));
                                Platform.runLater(() -> entries.add(date));
                            } catch (Exception e) {
                                // Ignore files that don't match the date format
                            }
                        }
                    }
                }
                Platform.runLater(() -> entries.sort(Comparator.reverseOrder()));
                return null;
            }
        };
        new Thread(task).start();
    }

    private void loadOrCreateDate(LocalDate date) {
        currentDate = date;
        if (!entries.contains(currentDate)) {
            entries.add(currentDate);
            entries.sort(Comparator.reverseOrder());
        }
        entryList.getSelectionModel().select(currentDate);
        loadEntry(currentDate);
    }

    private void loadEntry(LocalDate date) {
        currentDate = date;
        Path file = diaryDir.resolve(date + ".html");
        showProgress(true);

        Task<String> loadTask = new Task<>() {
            @Override
            protected String call() throws Exception {
                if (Files.exists(file)) {
                    return Files.readString(file);
                }
                return "<html><body></body></html>";
            }

            @Override
            protected void succeeded() {
                currentContent = getValue();
                editor.setHtmlText(currentContent);
                preview.getEngine().loadContent(currentContent);
                editor.setVisible(true);
                showProgress(false);
            }

            @Override
            protected void failed() {
                showError("Failed to load entry", getException());
                showProgress(false);
            }
        };

        new Thread(loadTask).start();
    }
    
    private void deleteEntry(LocalDate date) {
        Alert alert = new Alert(AlertType.CONFIRMATION);
        alert.setTitle("Delete Entry");
        alert.setHeaderText("Delete entry for " + date + "?");
        alert.setContentText("This cannot be undone.");

        alert.showAndWait().ifPresent(response -> {
            if (response == ButtonType.OK) {
                Path file = diaryDir.resolve(date + ".html");
                try {
                    Files.deleteIfExists(file);
                    entries.remove(date);
                    if (date.equals(currentDate)) {
                        editor.setHtmlText("");
                        preview.getEngine().loadContent("");
                        editor.setVisible(false);
                        currentDate = null;
                        currentContent = "";
                    }
                } catch (IOException ex) {
                    showError("Could not delete entry", ex);
                }
            }
        });
    }

    private void startAutoSave() {
        stopAutoSave();
        autoSaveTimer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                if (currentDate != null) {
                    String newHtml = editor.getHtmlText();
                    if (!newHtml.equals(currentContent)) {
                        saveCurrentEntry(newHtml);
                    }
                }
            }
        }, 5000, 5000);
    }

    private void stopAutoSave() {
        if (autoSaveTimer != null) {
            autoSaveTimer.cancel();
            autoSaveTimer = new Timer(true);
        }
    }

    private void saveCurrentEntry(String html) {
        if (currentDate == null) return;
        Path file = diaryDir.resolve(currentDate + ".html");
        showProgress(true);

        Task<Void> saveTask = new Task<>() {
            @Override
            protected Void call() throws Exception {
                Files.writeString(file, html);
                return null;
            }

            @Override
            protected void succeeded() {
                currentContent = html;
                preview.getEngine().loadContent(html);
                showProgress(false);
            }

            @Override
            protected void failed() {
                showError("Auto-save failed", getException());
                showProgress(false);
            }
        };
        new Thread(saveTask).start();
    }

    private void showProgress(boolean show) {
        Platform.runLater(() -> progress.setVisible(show));
    }

    private void showError(String header, Throwable ex) {
        Platform.runLater(() -> {
            Alert alert = new Alert(AlertType.ERROR);
            alert.setTitle("Error");
            alert.setHeaderText(header);
            alert.setContentText(ex.getMessage());
            alert.showAndWait();
        });
    }

    private final BooleanProperty editorFocused = new SimpleBooleanProperty(false);

    private BooleanProperty editorFocusedProperty() {
        editor.focusedProperty().addListener((obs, o, focused) -> editorFocused.set(focused));
        return editorFocused;
    }

    @Override
    public void stop() {
        stopAutoSave();
    }

    public static void main(String[] args) {
        launch(args);
    }
}