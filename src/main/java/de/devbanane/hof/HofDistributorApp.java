package de.devbanane.hof;

import de.devbanane.hof.HofCore.HofEntry;
import de.devbanane.hof.HofCore.PlannedCopy;
import de.devbanane.hof.HofCore.Variant;
import de.devbanane.hof.HofCore.VehicleFolder;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.control.cell.CheckBoxListCell;
import javafx.scene.image.Image;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;

import java.io.File;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class HofDistributorApp extends Application {

    private static final int MAX_FOLDERS_IN_LABEL = 2;

    private record VehicleRow(VehicleFolder folder, BooleanProperty selected) {
        @Override public String toString() {
            return folder.name();
        }
    }

    private final TextField pathField = new TextField();
    private final ObservableList<VehicleRow> vehicles = FXCollections.observableArrayList();
    private final TableView<HofEntry> hofTable = new TableView<>();
    private final TableView<PlannedCopy> planTable = new TableView<>();
    private final CheckBox overwriteBox = new CheckBox("Replace files that differ (a backup is kept)");
    private final Label status = new Label("Pick your OMSI 2 folder.");
    private final Button applyButton = new Button("Distribute");
    private final Button undoButton = new Button("Undo last run");

    private Stage stage;
    private Path root;
    private List<Path> mapHofs = List.of();

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage stage) {
        this.stage = stage;
        for (int size : new int[]{16, 24, 32, 48, 64, 128, 256}) {
            stage.getIcons().add(new Image(getClass().getResourceAsStream("icon-" + size + ".png")));
        }

        Settings.omsiFolder().ifPresent(p -> pathField.setText(p.toString()));
        HBox.setHgrow(pathField, Priority.ALWAYS);
        Button browse = new Button("Browse…");
        browse.setOnAction(e -> browse());
        Button scan = new Button("Scan");
        scan.setDefaultButton(true);
        scan.setOnAction(e -> scan());
        HBox top = new HBox(8, new Label("OMSI 2 folder:"), pathField, browse, scan);
        top.setAlignment(Pos.CENTER_LEFT);

        ListView<VehicleRow> vehicleList = new ListView<>(vehicles);
        vehicleList.setCellFactory(CheckBoxListCell.forListView(VehicleRow::selected));
        Button onlyBuses = new Button("Buses only");
        onlyBuses.setOnAction(e -> vehicles.forEach(v -> v.selected().set(v.folder().isBus())));
        Button none = new Button("None");
        none.setOnAction(e -> vehicles.forEach(v -> v.selected().set(false)));
        VBox.setVgrow(vehicleList, Priority.ALWAYS);
        VBox left = new VBox(6, bold("Vehicles"), vehicleList, new HBox(6, onlyBuses, none));

        setupHofTable();
        setupPlanTable();
        VBox.setVgrow(hofTable, Priority.ALWAYS);
        VBox.setVgrow(planTable, Priority.ALWAYS);
        Label hint = new Label("If a file differs between vehicles, choose which version to use.");
        hint.setStyle("-fx-text-fill: -fx-mid-text-color;");
        SplitPane right = new SplitPane(
                new VBox(6, bold("HOF files"), hint, hofTable),
                new VBox(6, bold("Changes"), planTable));
        right.setOrientation(Orientation.VERTICAL);

        SplitPane center = new SplitPane(left, right);
        center.setDividerPositions(0.25);

        overwriteBox.setOnAction(e -> refreshPlan());
        applyButton.setOnAction(e -> apply());
        undoButton.setOnAction(e -> undo());
        applyButton.setDisable(true);
        undoButton.setDisable(true);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox bottom = new HBox(10, overwriteBox, spacer, undoButton, applyButton);
        bottom.setAlignment(Pos.CENTER_LEFT);

        BorderPane rootPane = new BorderPane(center, top, null, new VBox(6, bottom, status), null);
        BorderPane.setMargin(center, new Insets(8, 0, 8, 0));
        rootPane.setPadding(new Insets(10));

        stage.setTitle("OMSI HOF Distributor");
        stage.setScene(new Scene(rootPane, 1000, 680));
        stage.setMinWidth(700);
        stage.setMinHeight(450);
        stage.show();

        if (!pathField.getText().isBlank()) scan();
    }

    private void setupHofTable() {
        TableColumn<HofEntry, String> name = new TableColumn<>("File");
        name.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue().fileName));
        name.setPrefWidth(220);
        name.setMaxWidth(320);

        TableColumn<HofEntry, HofEntry> chosen = new TableColumn<>("Version");
        chosen.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue()));
        chosen.setCellFactory(col -> new TableCell<>() {
            private final ComboBox<Variant> combo = new ComboBox<>();
            {
                // Keep the dropdown inside its column instead of growing to the longest entry.
                combo.setMinWidth(0);
                combo.prefWidthProperty().bind(col.widthProperty().subtract(12));
                combo.setCellFactory(lv -> variantCell());
                combo.setButtonCell(variantCell());
                combo.setOnAction(e -> {
                    HofEntry entry = getItem();
                    if (entry != null && combo.getValue() != null && entry.chosen != combo.getValue()) {
                        entry.chosen = combo.getValue();
                        refreshPlan();
                    }
                });
            }

            @Override protected void updateItem(HofEntry entry, boolean empty) {
                super.updateItem(entry, empty);
                setText(null);
                setGraphic(null);
                setStyle(null);
                if (empty || entry == null) return;
                if (entry.variants.size() == 1) {
                    setText("Same everywhere");
                    setStyle("-fx-text-fill: -fx-mid-text-color;");
                } else {
                    combo.setItems(FXCollections.observableArrayList(entry.variants));
                    combo.setValue(entry.chosen);
                    setGraphic(combo);
                }
            }
        });

        hofTable.getColumns().setAll(List.of(name, chosen));
        hofTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        hofTable.setPlaceholder(new Label("No HOF files in the selected vehicles."));
    }

    private static String describe(Variant v) {
        List<String> folders = v.locations().stream().map(p -> p.getParent().getFileName().toString()).toList();
        String where = String.join(", ", folders.subList(0, Math.min(MAX_FOLDERS_IN_LABEL, folders.size())));
        if (folders.size() > MAX_FOLDERS_IN_LABEL) where += " +" + (folders.size() - MAX_FOLDERS_IN_LABEL) + " more";
        LocalDate date = LocalDate.ofInstant(v.modified().toInstant(), ZoneId.systemDefault());
        return where + "  (" + date + ")";
    }

    private static ListCell<Variant> variantCell() {
        return new ListCell<>() {
            @Override protected void updateItem(Variant v, boolean empty) {
                super.updateItem(v, empty);
                setText(empty || v == null ? null : describe(v));
                setTooltip(empty || v == null ? null : new Tooltip(String.join("\n",
                        v.locations().stream().map(p -> p.getParent().getFileName().toString()).toList())));
            }
        };
    }

    private void setupPlanTable() {
        TableColumn<PlannedCopy, String> action = new TableColumn<>("Change");
        action.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(
                c.getValue().action() == HofCore.Action.COPY ? "Add" : "Replace"));
        action.setPrefWidth(80);
        action.setMaxWidth(100);
        TableColumn<PlannedCopy, String> file = new TableColumn<>("File");
        file.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue().entry().fileName));
        file.setPrefWidth(260);
        TableColumn<PlannedCopy, String> vehicle = new TableColumn<>("Vehicle");
        vehicle.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue().vehicle().name()));
        planTable.getColumns().setAll(List.of(action, file, vehicle));
        planTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        planTable.setPlaceholder(new Label("Nothing to do. Every selected vehicle already has every HOF file."));
    }

    private void browse() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Select your OMSI 2 folder");
        File current = new File(pathField.getText());
        if (current.isDirectory()) chooser.setInitialDirectory(current);
        File dir = chooser.showDialog(stage);
        if (dir != null) {
            pathField.setText(dir.getAbsolutePath());
            scan();
        }
    }

    private void scan() {
        Path candidate = Path.of(pathField.getText().trim());
        status.setText("Scanning…");
        applyButton.setDisable(true);
        Thread.ofVirtual().start(() -> {
            try {
                List<VehicleFolder> found = HofCore.scanVehicles(candidate);
                List<Path> maps = HofCore.scanMaps(candidate);
                Platform.runLater(() -> {
                    root = candidate;
                    mapHofs = maps;
                    Settings.saveOmsiFolder(candidate);
                    vehicles.clear();
                    for (VehicleFolder v : found) {
                        VehicleRow row = new VehicleRow(v, new SimpleBooleanProperty(v.isBus()));
                        row.selected().addListener((obs, was, now) -> recollect());
                        vehicles.add(row);
                    }
                    undoButton.setDisable(!HofCore.hasUndo(root));
                    recollect();
                });
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    status.setText("That doesn't look like an OMSI 2 folder.");
                    error("Scan failed", ex);
                });
            }
        });
    }

    private void recollect() {
        if (root == null) return;
        Map<String, String> previousPick = new HashMap<>();
        hofTable.getItems().forEach(e -> previousPick.put(e.key(), e.chosen.hash()));
        try {
            List<HofEntry> entries = HofCore.collect(selectedFolders(), mapHofs);
            for (HofEntry e : entries) {
                String hash = previousPick.get(e.key());
                e.variants.stream().filter(v -> v.hash().equals(hash)).findFirst().ifPresent(v -> e.chosen = v);
            }
            hofTable.getItems().setAll(entries);
            refreshPlan();
        } catch (Exception ex) {
            error("Reading HOF files failed", ex);
        }
    }

    private void refreshPlan() {
        if (root == null) return;
        List<PlannedCopy> plan = HofCore.plan(hofTable.getItems(), selectedFolders(), overwriteBox.isSelected());
        planTable.getItems().setAll(plan);
        applyButton.setDisable(plan.isEmpty());
        status.setText(plan.isEmpty() ? "Everything is up to date."
                : "%d file(s) will be copied.".formatted(plan.size()));
    }

    private List<VehicleFolder> selectedFolders() {
        return vehicles.stream().filter(v -> v.selected().get()).map(VehicleRow::folder).toList();
    }

    private void apply() {
        List<PlannedCopy> plan = List.copyOf(planTable.getItems());
        long overwrites = plan.stream().filter(c -> c.action() == HofCore.Action.OVERWRITE).count();
        String msg = "Copy %d file(s) into your vehicles?".formatted(plan.size())
                + (overwrites > 0 ? "\n%d existing file(s) will be replaced. Backups are kept.".formatted(overwrites) : "");
        if (!confirm(msg)) return;
        try {
            int n = HofCore.apply(plan, root);
            scan();
            info("Done. " + n + " file(s) copied.\nUse \"Undo last run\" to revert.");
        } catch (Exception ex) {
            scan();
            error("Copying failed. Use \"Undo last run\" to revert what was already copied.", ex);
        }
    }

    private void undo() {
        if (!confirm("Remove the files added by the last run and restore any replaced ones?")) return;
        try {
            int n = HofCore.undo(root);
            scan();
            info(n + " file(s) reverted.");
        } catch (Exception ex) {
            error("Undo failed", ex);
        }
    }

    private static Label bold(String text) {
        Label l = new Label(text);
        l.setStyle("-fx-font-weight: bold;");
        return l;
    }

    private Alert alert(Alert.AlertType type, String msg) {
        Alert a = new Alert(type, msg);
        a.initOwner(stage); // inherits the window icon
        a.setHeaderText(null);
        return a;
    }

    private boolean confirm(String msg) {
        return alert(Alert.AlertType.CONFIRMATION, msg).showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK;
    }

    private void info(String msg) {
        alert(Alert.AlertType.INFORMATION, msg).showAndWait();
    }

    private void error(String title, Exception ex) {
        Alert a = alert(Alert.AlertType.ERROR, ex.getMessage());
        a.setHeaderText(title);
        a.showAndWait();
    }
}
