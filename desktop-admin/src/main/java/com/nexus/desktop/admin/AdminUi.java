package com.nexus.desktop.admin;

import com.nexus.desktop.shared.ui.I18n;
import com.nexus.desktop.shared.ui.UiComponents;
import com.nexus.sdk.model.GroupMember;
import javafx.beans.InvalidationListener;
import javafx.beans.property.SimpleStringProperty;
import javafx.geometry.Bounds;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.chart.BarChart;
import javafx.scene.chart.CategoryAxis;
import javafx.scene.chart.NumberAxis;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TablePosition;
import javafx.scene.control.TableView;
import javafx.scene.control.Tooltip;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.text.Font;
import javafx.scene.text.Text;
import javafx.scene.text.TextAlignment;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Function;

/** Shared table, placeholder, chart and formatting helpers for admin screens. */
final class AdminUi {
    private AdminUi() {
    }

    static <T> void bind(TableColumn<T, String> column, Function<T, String> value) {
        column.setCellValueFactory(cell -> new SimpleStringProperty(
                cell.getValue() == null ? "" : safe(value.apply(cell.getValue()))));
    }

    static <T> TableColumn<T, String> column(Function<T, String> value) {
        TableColumn<T, String> column = new TableColumn<>();
        bind(column, value);
        return column;
    }

    /**
     * Lets a column header wrap instead of ending in an ellipsis when the table is narrower than its
     * copy. Vietnamese headers are the wider case ("Tên đăng nhập" against "Username"), and the same
     * screen is shown both full width and inside the shell's narrower content area, so a header that
     * only fits one of them would hide which column the reader is looking at.
     */
    static void wrapHeader(TableColumn<?, ?> column) {
        if (column.getText() == null || column.getText().isBlank()) return;
        Label label = new Label(column.getText());
        label.getStyleClass().add("header-wrap");
        label.setWrapText(true);
        label.setAlignment(Pos.CENTER);
        label.setTextAlignment(TextAlignment.CENTER);
        // The header lays the graphic out at its preferred width unless it is told how much room it
        // has; without this the label stays one line and is clipped by the column.
        label.maxWidthProperty().bind(column.widthProperty().subtract(8));
        column.setGraphic(label);
        column.setText(null);
    }

    /**
     * Explains a cell whose text the column is too narrow to show: hovering it shows the whole value,
     * and assistive technology reads the whole value instead of the clipped prefix.
     *
     * <p>The tooltip is installed only while the cell is really truncated, so a short value that fits
     * stays a plain cell and an empty or reused cell carries no stale explanation into the next row.
     * The shell shows the accounts table in a narrower content area than the workspace does, which is
     * where a full name or an email address stops fitting.
     */
    static <T> void explainTruncatedText(TableColumn<T, String> column) {
        column.setCellFactory(ignored -> new TruncatingTextCell<>());
        READABLE.add(column);
    }

    /** The columns that explain themselves; the keyboard walks exactly these. */
    private static final Set<TableColumn<?, ?>> READABLE =
            Collections.newSetFromMap(new WeakHashMap<>());

    /**
     * An explanation can hold free text (a report reason, a submitted message), so it wraps at a
     * readable width instead of running off the window as one very long line.
     */
    static final double TOOLTIP_WRAP_WIDTH = 420;

    /**
     * A hover stays short even for a value that is a paragraph: wrapping bounds the width, not the
     * height, so a whole evidence message would still make a tooltip taller than the window. The
     * value is not lost, it stays on the cell as accessible text.
     */
    static final int TOOLTIP_PREVIEW_CHARS = 200;

    /** The style class that marks the cell the keyboard has reached; see {@code admin.css}. */
    static final String KEYBOARD_FOCUS_CLASS = "admin-cell-focus";

    private static final class TruncatingTextCell<T> extends TableCell<T, String> {
        private final Tooltip tooltip = new Tooltip();
        private String measuredText;
        private Font measuredFont;
        private double measuredWidth = Double.NaN;
        private boolean measuredTruncated;

        private final InvalidationListener focusListener = observable -> syncKeyboardFocus();
        private TableView<T> listeningTo;

        private TruncatingTextCell() {
            tooltip.setWrapText(true);
            tooltip.setMaxWidth(TOOLTIP_WRAP_WIDTH);
            // The keyboard's cursor is painted here rather than by JavaFX: a table keeps focus on
            // the control, so the cell it has reached has to mark itself from the focus model.
            tableViewProperty().addListener((observable, previous, table) -> listenTo(table));
        }

        private void listenTo(TableView<T> table) {
            if (listeningTo == table) return;
            if (listeningTo != null) {
                listeningTo.getFocusModel().focusedCellProperty().removeListener(focusListener);
                listeningTo.focusedProperty().removeListener(focusListener);
            }
            listeningTo = table;
            if (table != null) {
                table.getFocusModel().focusedCellProperty().addListener(focusListener);
                table.focusedProperty().addListener(focusListener);
            }
            syncKeyboardFocus();
        }

        /** Marks the cell the keyboard has reached, and shows what a clipped cell cannot. */
        private void syncKeyboardFocus() {
            boolean reached = isKeyboardFocused();
            boolean marked = getStyleClass().contains(KEYBOARD_FOCUS_CLASS);
            if (reached && !marked) getStyleClass().add(KEYBOARD_FOCUS_CLASS);
            if (!reached && marked) getStyleClass().remove(KEYBOARD_FOCUS_CLASS);
            if (!reached) {
                tooltip.hide();
                return;
            }
            explainIfTruncated();
            showExplanation();
        }

        private boolean isKeyboardFocused() {
            TableView<T> table = getTableView();
            if (table == null || !table.isFocused() || isEmpty() || getTableColumn() == null) return false;
            TablePosition<T, ?> focused = table.getFocusModel().getFocusedCell();
            return focused != null && focused.getRow() == getIndex()
                    && focused.getTableColumn() == getTableColumn();
        }

        /** Shows the explanation for the cell the keyboard reached; hover installs it by itself. */
        private void showExplanation() {
            if (getTooltip() != tooltip || tooltip.isShowing()) return;
            Bounds bounds = localToScreen(getBoundsInLocal());
            if (bounds == null) return;
            tooltip.show(this, bounds.getMinX(), bounds.getMaxY() + 2);
        }

        @Override
        protected void updateItem(String item, boolean empty) {
            super.updateItem(item, empty);
            // A cell that scrolls on to another row must not leave the previous value on screen.
            tooltip.hide();
            setText(empty ? null : item);
            explainIfTruncated();
            syncKeyboardFocus();
        }

        @Override
        protected void layoutChildren() {
            super.layoutChildren();
            explainIfTruncated();
        }

        private void explainIfTruncated() {
            String text = getText();
            if (text == null || text.isEmpty() || getWidth() <= 0) {
                clearExplanation();
                return;
            }
            double available = getWidth() - snappedLeftInset() - snappedRightInset();
            if (!isTruncated(text, available)) {
                clearExplanation();
                return;
            }
            String preview = hoverPreview(text);
            if (!preview.equals(tooltip.getText())) tooltip.setText(preview);
            if (getTooltip() != tooltip) setTooltip(tooltip);
            // The cell keeps the whole value for assistive technology; only the hover is shortened.
            setAccessibleText(text);
        }

        private void clearExplanation() {
            tooltip.hide();
            if (getTooltip() != null) setTooltip(null);
            setAccessibleText(null);
        }

        /** The hover copy for {@code text}: the value itself, or its opening words when it is long. */
        private static String hoverPreview(String text) {
            if (text.length() <= TOOLTIP_PREVIEW_CHARS) return text;
            int cut = text.lastIndexOf(' ', TOOLTIP_PREVIEW_CHARS);
            if (cut < TOOLTIP_PREVIEW_CHARS / 2) cut = TOOLTIP_PREVIEW_CHARS;
            return text.substring(0, cut).stripTrailing() + "…";
        }

        /** Whether {@code text} is wider than the room the cell has, measured once per size. */
        private boolean isTruncated(String text, double available) {
            Font font = getFont();
            if (text.equals(measuredText) && font == measuredFont && available == measuredWidth) {
                return measuredTruncated;
            }
            Text measure = new Text(text);
            measure.setFont(font);
            measuredText = text;
            measuredFont = font;
            measuredWidth = available;
            measuredTruncated = measure.getLayoutBounds().getWidth() > available + 0.5;
            return measuredTruncated;
        }
    }

    /**
     * Gives a table the keyboard path to the values the narrow pane clips. A JavaFX table keeps the
     * keyboard on the control rather than on a cell, so the navigation is installed here: the
     * arrows walk the columns that explain themselves, the focused cell is marked and shows its own
     * explanation, and the copy shortcut takes that cell's whole value.
     *
     * <p>F2 and the row copy stay as the fallback for the whole row, because a reader may want the
     * row as a unit, and because F2 already worked before the keyboard could name one cell.
     *
     * @return the row inspector, which the caller may hold for its own tests or hints; it is only
     *     shown on F2, so it is deliberately not installed as the table's hover tooltip.
     */
    static Tooltip keyboardReadable(TableView<?> table) {
        Tooltip inspector = new Tooltip();
        inspector.setWrapText(true);
        inspector.setMaxWidth(TOOLTIP_WRAP_WIDTH);
        table.addEventHandler(KeyEvent.KEY_PRESSED, event -> {
            switch (event.getCode()) {
                case RIGHT -> moveToCell(table, event, true);
                case LEFT -> moveToCell(table, event, false);
                case ESCAPE -> {
                    if (inspector.isShowing()) {
                        inspector.hide();
                        event.consume();
                    }
                }
                case F2 -> showRowInspector(table, inspector, event);
                case C -> {
                    if (event.isShortcutDown()) copyForKeyboard(table, event);
                }
                default -> { }
            }
        });
        // The arrows move the selection; the focused cell has to follow it, or the marked cell and
        // the copy would still name the row the reader left.
        table.getSelectionModel().selectedIndexProperty().addListener((observable, was, row) -> {
            TablePosition<?, ?> focused = table.getFocusModel().getFocusedCell();
            if (row == null || row.intValue() < 0 || focused == null || focused.getColumn() < 0) return;
            focusCell(table, row.intValue(), focused.getColumn());
        });
        table.focusedProperty().addListener((observable, wasFocused, focused) -> {
            if (!focused) inspector.hide();
        });
        return inspector;
    }

    /** Moves the keyboard's cursor to the neighbouring column that explains itself. */
    private static void moveToCell(TableView<?> table, KeyEvent event, boolean forward) {
        int row = table.getSelectionModel().getSelectedIndex();
        if (row < 0) row = table.getFocusModel().getFocusedIndex();
        if (row < 0) return;
        TablePosition<?, ?> focused = table.getFocusModel().getFocusedCell();
        int from = focused == null ? -1 : focused.getColumn();
        int next = neighbour(table, from, forward);
        if (next < 0) return;
        focusCell(table, row, next);
        event.consume();
    }

    /** The next column in {@code direction} that explains itself, or -1 at the edge. */
    private static int neighbour(TableView<?> table, int from, boolean forward) {
        int step = forward ? 1 : -1;
        for (int index = from < 0 ? (forward ? 0 : table.getColumns().size() - 1) : from + step;
                index >= 0 && index < table.getColumns().size(); index += step) {
            if (READABLE.contains(table.getColumns().get(index))) return index;
        }
        return -1;
    }

    /** Focuses one cell; the raw cast is the price of driving a wildcard table's focus model. */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static void focusCell(TableView<?> table, int row, int column) {
        ((TableView) table).getFocusModel().focus(row, table.getColumns().get(column));
    }

    private static void showRowInspector(TableView<?> table, Tooltip inspector, KeyEvent event) {
        String values = rowText(table, true);
        if (values.isEmpty() || inspector.isShowing()) {
            inspector.hide();
        } else {
            inspector.setText(values);
            Bounds bounds = table.localToScreen(table.getBoundsInLocal());
            if (bounds != null) inspector.show(table, bounds.getMinX(), bounds.getMinY() + 28);
        }
        event.consume();
    }

    /** Copies the focused cell, falling back to the whole row when no cell is focused. */
    private static void copyForKeyboard(TableView<?> table, KeyEvent event) {
        String value = focusedCellValue(table);
        if (value == null) value = rowText(table, false);
        if (value.isEmpty()) return;
        ClipboardContent content = new ClipboardContent();
        content.putString(value);
        Clipboard.getSystemClipboard().setContent(content);
        event.consume();
    }

    /** The focused cell's whole value, or null while the keyboard has only a row. */
    private static String focusedCellValue(TableView<?> table) {
        TablePosition<?, ?> focused = table.getFocusModel().getFocusedCell();
        if (focused == null || focused.getColumn() < 0) return null;
        Object value = table.getColumns().get(focused.getColumn()).getCellData(focused.getRow());
        return value == null ? "" : value.toString();
    }

    /** The selected row as text, labelled with the column headers when asked. */
    private static String rowText(TableView<?> table, boolean withHeaders) {
        int row = table.getSelectionModel().getSelectedIndex();
        if (row < 0 || row >= table.getItems().size()) return "";
        StringBuilder text = new StringBuilder();
        for (TableColumn<?, ?> column : table.getColumns()) {
            Object value = column.getCellData(row);
            if (value == null || value.toString().isEmpty()) continue;
            if (text.length() > 0) text.append(withHeaders ? '\n' : '\t');
            if (withHeaders) text.append(headerText(column)).append(": ");
            text.append(value);
        }
        return text.toString();
    }

    /** A header's words; {@link #wrapHeader} moves them from the text into the graphic. */
    private static String headerText(TableColumn<?, ?> column) {
        if (column.getText() != null && !column.getText().isBlank()) return column.getText();
        if (column.getGraphic() instanceof Label label && label.getText() != null) return label.getText();
        return "";
    }

    static <T> void bindAction(TableColumn<T, String> column, Function<T, Node> graphic) {
        column.setCellFactory(ignored -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || getIndex() < 0 || getIndex() >= getTableView().getItems().size()) {
                    setGraphic(null);
                    return;
                }
                Node node = graphic.apply(getTableView().getItems().get(getIndex()));
                if (!node.getStyleClass().contains("admin-action-button")) {
                    node.getStyleClass().add("admin-action-button");
                }
                setGraphic(node);
                setText(null);
            }
        });
    }

    static <T> TableColumn<T, String> actionColumn(Function<T, Node> graphic) {
        TableColumn<T, String> column = new TableColumn<>();
        column.setCellFactory(ignored -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || getIndex() < 0 || getIndex() >= getTableView().getItems().size()) {
                    setGraphic(null);
                    return;
                }
                Node node = graphic.apply(getTableView().getItems().get(getIndex()));
                node.getStyleClass().add("admin-action-button");
                setGraphic(node);
                setText(null);
            }
        });
        return column;
    }

    static void loading(TableView<?> table, I18n i18n) {
        table.setPlaceholder(UiComponents.loadingState(i18n.get("admin.common.loading")));
    }

    static void empty(TableView<?> table, I18n i18n, String titleKey, String hintKey) {
        table.setPlaceholder(UiComponents.emptyState(i18n.get(titleKey), i18n.get(hintKey), null, null));
    }

    static void error(TableView<?> table, I18n i18n, Exception exception, Runnable retry) {
        table.setPlaceholder(UiComponents.errorState(i18n.get("admin.error.title"),
                AdminErrors.message(exception, i18n), i18n.get("admin.common.retry"), retry));
    }

    static <T> void setItems(TableView<T> table, List<T> items) {
        table.getItems().setAll(items);
    }

    static String count(I18n i18n, String key, int count) {
        return i18n.format(key, count);
    }

    static String time(OffsetDateTime time, I18n i18n) {
        if (time == null) return "";
        return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", i18n.locale())
                .withZone(ZoneId.systemDefault())
                .format(time.atZoneSameInstant(ZoneId.systemDefault()));
    }

    static OffsetDateTime startOfDay(LocalDate date) {
        return date == null ? null : date.atStartOfDay(ZoneId.systemDefault()).toOffsetDateTime();
    }

    static String role(GroupMember member, I18n i18n) {
        return member.getRole() == GroupMember.RoleEnum.ADMIN
                ? i18n.get("admin.groups.roleAdmin") : i18n.get("admin.groups.roleMember");
    }

    static BarChart<String, Number> chart(String title) {
        BarChart<String, Number> chart = new BarChart<>(new CategoryAxis(), new NumberAxis());
        chart.setTitle(title);
        chart.setLegendVisible(false);
        chart.setAnimated(false);
        chart.setPrefHeight(240);
        chart.getStyleClass().add("admin-chart");
        chart.setAccessibleText(title);
        return chart;
    }

    static Label spacer() {
        Label label = new Label();
        HBox.setHgrow(label, Priority.ALWAYS);
        return label;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
