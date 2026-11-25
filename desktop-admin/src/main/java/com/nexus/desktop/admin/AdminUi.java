package com.nexus.desktop.admin;

import com.nexus.desktop.shared.ui.I18n;
import com.nexus.desktop.shared.ui.UiComponents;
import com.nexus.sdk.model.GroupMember;
import javafx.beans.InvalidationListener;
import javafx.beans.property.SimpleStringProperty;
import javafx.geometry.Bounds;
import javafx.geometry.Pos;
import javafx.scene.AccessibleAttribute;
import javafx.scene.Node;
import javafx.scene.chart.BarChart;
import javafx.scene.chart.CategoryAxis;
import javafx.scene.chart.NumberAxis;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TablePosition;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
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
import javafx.stage.Popup;
import javafx.stage.Window;

import java.text.BreakIterator;
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
     *
     * <p>The column also describes the cell the keyboard reaches, so a screen reader hears the
     * column, the whole value and whether the screen is clipping it.
     */
    static <T> void explainTruncatedText(TableColumn<T, String> column, I18n i18n) {
        column.setCellFactory(ignored -> new TruncatingTextCell<>(i18n));
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

    /** A line-break opportunity the popup honours; it is invisible and never leaves the popup. */
    static final char BREAK_OPPORTUNITY = '\u200B';

    /** How far a run may go before it needs a break, at the 420 px the popup wraps at. */
    static final int BREAK_EVERY = 40;

    /** The style class that marks the cell the keyboard has reached; see {@code admin.css}. */
    static final String KEYBOARD_FOCUS_CLASS = "admin-cell-focus";

    private static final class TruncatingTextCell<T> extends TableCell<T, String> {
        private final Tooltip tooltip = new Tooltip();
        private final I18n i18n;
        private Measurement measured;
        /** Whether this cell is currently described as the keyboard's cursor. */
        private boolean described;

        private final InvalidationListener focusListener = observable -> syncKeyboardFocus();

        private TruncatingTextCell(I18n i18n) {
            this.i18n = i18n;
            tooltip.setWrapText(true);
            tooltip.setMaxWidth(TOOLTIP_WRAP_WIDTH);
            // The keyboard's cursor is painted here rather than by JavaFX: a table keeps focus on
            // the control, so the cell it has reached has to mark itself from the focus model.
            tableViewProperty().addListener((observable, previous, table) -> listenTo(table));
        }

        /** Follows the table's cursor; a cell belongs to one table for its whole life. */
        private void listenTo(TableView<T> table) {
            if (table == null) return;
            table.getFocusModel().focusedCellProperty().addListener(focusListener);
            table.focusedProperty().addListener(focusListener);
            syncKeyboardFocus();
        }

        /**
         * Marks the cell the keyboard has reached, shows what a clipped cell cannot, and says the
         * same to assistive technology: a screen reader cannot see the mark, so the description is
         * the cursor's only cue.
         */
        private void syncKeyboardFocus() {
            boolean reached = isKeyboardFocused();
            boolean wasDescribed = described;
            described = reached;
            if (!reached) {
                getStyleClass().remove(KEYBOARD_FOCUS_CLASS);
            } else if (!getStyleClass().contains(KEYBOARD_FOCUS_CLASS)) {
                getStyleClass().add(KEYBOARD_FOCUS_CLASS);
            }
            // Restores the plain value when the cursor leaves this cell.
            explainIfTruncated();
            if (reached) {
                setAccessibleText(description());
                notifyAccessibleAttributeChanged(AccessibleAttribute.TEXT);
                showExplanation();
                return;
            }
            if (wasDescribed) notifyAccessibleAttributeChanged(AccessibleAttribute.TEXT);
            tooltip.hide();
        }

        /** The cursor's description: which column, the whole value, and whether it is clipped. */
        private String description() {
            String header = headerText(getTableColumn());
            String text = getText() == null ? "" : getText();
            return getTooltip() == tooltip
                    ? i18n.format("admin.a11y.cellClipped", header, text, i18n.get("admin.a11y.clipped"))
                    : i18n.format("admin.a11y.cell", header, text);
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
            // The cursor's own description, once it is here, is the more specific text.
            if (!described) setAccessibleText(text);
        }

        private void clearExplanation() {
            tooltip.hide();
            if (getTooltip() != null) setTooltip(null);
            if (!described) setAccessibleText(null);
        }

        /**
         * The hover copy for {@code text}: the value itself, or its opening words when it is long,
         * with break opportunities added so an address or a path that never offers a space still
         * wraps. The breaks are invisible and live only in the popup: the accessible text and the
         * copied value are the untouched value.
         */
        private static String hoverPreview(String text) {
            String preview = text.length() <= TOOLTIP_PREVIEW_CHARS ? text : shorten(text);
            return withBreakOpportunities(preview);
        }

        private static String shorten(String text) {
            int cut = text.lastIndexOf(' ', TOOLTIP_PREVIEW_CHARS);
            if (cut < TOOLTIP_PREVIEW_CHARS / 2) cut = TOOLTIP_PREVIEW_CHARS;
            return text.substring(0, cut).stripTrailing() + "…";
        }

        /**
         * Inserts a zero-width break opportunity inside a run of characters a space never ends — a
         * long email, a path — which is the one case the wrap width cannot bound on its own.
         *
         * <p>The run is counted in grapheme clusters, not in characters: a break inside one splits
         * what a reader sees as a single character — a family emoji becomes two people, an accented
         * letter loses its accent — so a break may only land where {@link BreakIterator} sees a
         * boundary.
         */
        private static String withBreakOpportunities(String text) {
            BreakIterator clusters = BreakIterator.getCharacterInstance();
            clusters.setText(text);
            StringBuilder broken = new StringBuilder(text.length() + 8);
            int run = 0;
            for (int start = clusters.first(), end = clusters.next();
                    end != BreakIterator.DONE; start = end, end = clusters.next()) {
                broken.append(text, start, end);
                run = Character.isWhitespace(text.charAt(start)) ? 0 : run + 1;
                if (run == BREAK_EVERY) {
                    broken.append(BREAK_OPPORTUNITY);
                    run = 0;
                }
            }
            return broken.toString();
        }

        /** The last measurement, so a layout pass that changed nothing measures nothing. */
        private record Measurement(String text, Font font, double width, boolean truncated) {
        }

        /** Whether {@code text} is wider than the room the cell has, measured once per size. */
        private boolean isTruncated(String text, double available) {
            Font font = getFont();
            if (measured != null && measured.text().equals(text)
                    && measured.font() == font && measured.width() == available) {
                return measured.truncated();
            }
            Text measure = new Text(text);
            measure.setFont(font);
            measured = new Measurement(text, font, available,
                    measure.getLayoutBounds().getWidth() > available + 0.5);
            return measured.truncated();
        }
    }

    /**
     * Gives a table the keyboard path to the values the narrow pane clips. A JavaFX table keeps the
     * keyboard on the control rather than on a cell, so the navigation is installed here: the
     * arrows walk the columns that explain themselves, the focused cell is marked and shows its own
     * explanation, and the copy shortcut takes that cell's whole value.
     *
     * <p>F2 and the row copy stay as the fallback for the whole row, because a reader may want the
     * row as a unit, and because F2 already worked before the keyboard could name one cell. F2
     * opens it in the {@link RowInspector}, which unlike a tooltip can be entered, scrolled and
     * copied from.
     *
     * @return the row inspector, which the caller may hold for its own tests or hints; it is only
     *     shown on F2, so it is deliberately not installed as the table's hover tooltip.
     */
    static <T> RowInspector keyboardReadable(TableView<T> table) {
        RowInspector inspector = new RowInspector();
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
        // Focus moving into the inspector's own window leaves its owner window's focus owner on the
        // table, so this closes the inspector only when the keyboard left the table for a sibling.
        table.focusedProperty().addListener((observable, wasFocused, focused) -> {
            if (focused || table.getScene() == null) return;
            Node owner = table.getScene().getFocusOwner();
            if (owner != null && owner != table) inspector.hide();
        });
        return inspector;
    }

    /**
     * The F2 row inspector: the whole row over a read-only text area in a small window of its own.
     * A {@link Tooltip} cannot take focus, scroll or have its text selected, so a long row could be
     * neither finished nor partly copied; here the keyboard moves the caret and the selection, the
     * arrows and page keys scroll, the platform's copy shortcuts work as everywhere else, and F2 or
     * Escape closes it and hands the keyboard back to the table.
     */
    static final class RowInspector {
        private final Popup popup = new Popup();
        private final TextArea text = new TextArea();

        private RowInspector() {
            text.setEditable(false);
            text.setWrapText(true);
            text.setPrefWidth(TOOLTIP_WRAP_WIDTH);
            // The keyboard opened it, so the keyboard has to be able to close it from inside.
            text.addEventHandler(KeyEvent.KEY_PRESSED, event -> {
                if (event.getCode() == KeyCode.F2) {
                    event.consume();
                    hide();
                }
            });
            popup.getContent().add(text);
            popup.setAutoHide(true);
            popup.setHideOnEscape(true);
            popup.setAutoFix(true);
            // The caret is inside the window, so closing it has to hand the keyboard back to the
            // table rather than to whichever node the owner window chose next.
            popup.setOnHidden(event -> {
                Window owner = popup.getOwnerWindow();
                if (owner == null || !owner.isFocused() || owner.getScene() == null) return;
                if (owner.getScene().getFocusOwner() != null) {
                    owner.getScene().getFocusOwner().requestFocus();
                }
            });
        }

        /** The read-only text; the caret, the selection and the copy shortcut all live here. */
        TextArea text() {
            return text;
        }

        boolean isShowing() {
            return popup.isShowing();
        }

        void show(Node anchor, String value) {
            Bounds bounds = anchor.localToScreen(anchor.getBoundsInLocal());
            if (bounds == null) return;
            text.setText(value);
            // A one-column row must not open a box the height of the window; a longer one scrolls.
            text.setPrefHeight(Math.min(180, 52 + 20.0 * value.split("\n").length));
            popup.show(anchor, bounds.getMinX(), bounds.getMinY() + 28);
            text.requestFocus();
            text.positionCaret(0);
        }

        void hide() {
            popup.hide();
        }
    }

    /** Moves the keyboard's cursor to the neighbouring column that explains itself. */
    private static <T> void moveToCell(TableView<T> table, KeyEvent event, boolean forward) {
        int row = Math.max(table.getSelectionModel().getSelectedIndex(),
                table.getFocusModel().getFocusedIndex());
        if (row < 0) return;
        TablePosition<T, ?> focused = table.getFocusModel().getFocusedCell();
        int from = focused == null ? -1 : focused.getColumn();
        int next = neighbour(table, from, forward);
        if (next < 0) return;
        focusCell(table, row, next);
        event.consume();
    }

    /** The next column in {@code direction} that explains itself, or -1 at the edge. */
    private static <T> int neighbour(TableView<T> table, int from, boolean forward) {
        int step = forward ? 1 : -1;
        for (int index = from < 0 ? (forward ? 0 : table.getColumns().size() - 1) : from + step;
                index >= 0 && index < table.getColumns().size(); index += step) {
            if (READABLE.contains(table.getColumns().get(index))) return index;
        }
        return -1;
    }

    private static <T> void focusCell(TableView<T> table, int row, int column) {
        table.getFocusModel().focus(row, table.getColumns().get(column));
    }

    private static <T> void showRowInspector(TableView<T> table, RowInspector inspector, KeyEvent event) {
        event.consume();
        if (inspector.isShowing()) {
            inspector.hide();
            return;
        }
        String values = rowText(table, true);
        if (!values.isEmpty()) inspector.show(table, values);
    }

    /** Copies the focused cell, falling back to the whole row when no cell is focused. */
    private static <T> void copyForKeyboard(TableView<T> table, KeyEvent event) {
        String value = focusedCellValue(table);
        if (value == null) value = rowText(table, false);
        if (value.isEmpty()) return;
        ClipboardContent content = new ClipboardContent();
        content.putString(value);
        Clipboard.getSystemClipboard().setContent(content);
        event.consume();
    }

    /** The focused cell's whole value, or null while the keyboard has only a row. */
    private static <T> String focusedCellValue(TableView<T> table) {
        TablePosition<T, ?> focused = table.getFocusModel().getFocusedCell();
        if (focused == null || focused.getColumn() < 0) return null;
        Object value = table.getColumns().get(focused.getColumn()).getCellData(focused.getRow());
        return value == null ? "" : value.toString();
    }

    /** The selected row as text, labelled with the column headers when asked. */
    private static <T> String rowText(TableView<T> table, boolean withHeaders) {
        int row = table.getSelectionModel().getSelectedIndex();
        if (row < 0 || row >= table.getItems().size()) return "";
        StringBuilder text = new StringBuilder();
        for (TableColumn<T, ?> column : table.getColumns()) {
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
