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
import javafx.scene.Scene;
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
import javafx.scene.layout.StackPane;
import javafx.scene.text.Font;
import javafx.scene.text.Text;
import javafx.scene.text.TextAlignment;
import javafx.stage.Popup;
import javafx.stage.Window;

import java.text.Normalizer;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
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

    /**
     * The room a tooltip leaves for its copy: {@link #TOOLTIP_WRAP_WIDTH} less the 8 px each side the
     * theme's {@code .tooltip} padding takes. A copy above this is the one JavaFX will not wrap by
     * itself once it carries a mark on a letter.
     */
    static final double TOOLTIP_TEXT_WIDTH = TOOLTIP_WRAP_WIDTH - 16;

    /** The style class that marks the cell the keyboard has reached; see {@code admin.css}. */
    static final String KEYBOARD_FOCUS_CLASS = "admin-cell-focus";

    private static final class TruncatingTextCell<T> extends TableCell<T, String> {
        private final Tooltip tooltip = new Tooltip();
        private final I18n i18n;
        private Measurement measured;
        /** Whether this cell is currently described as the keyboard's cursor. */
        private boolean described;

        private final InvalidationListener focusListener = observable -> syncKeyboardFocus();
        /** The tooltip's resolved font, and the theme classes it was resolved from. */
        private static Font tooltipFont;
        private static List<String> tooltipTheme;

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
         * written in its composed form and broken into lines where composing could not help.
         *
         * <p>A run that never offers a space — a long email, a path — needs nothing added to wrap:
         * inside {@link #TOOLTIP_WRAP_WIDTH} JavaFX breaks it at its own glyph boundaries. It does
         * not do that for a value carrying a combining mark on a letter: the popup is then sized
         * from the text's unwrapped width and paints outside its own box. Composing the copy to NFC
         * clears that mark whenever the letter has a precomposed form, leaving the same glyphs in a
         * canonically equivalent string; a mark with no precomposed form — a macron on an "x", a
         * Devanagari vowel sign — survives composition, so those lines are broken by hand. The cell
         * keeps the value as submitted for the accessible text and the copy, so neither is
         * rewritten.
         */
        private String hoverPreview(String text) {
            String composed = Normalizer.normalize(text, Normalizer.Form.NFC);
            String preview = composed.length() <= TOOLTIP_PREVIEW_CHARS
                    ? composed : shorten(composed);
            return breakMarkedLines(preview);
        }

        /**
         * Breaks a copy that is wider than the tooltip allows and still carries a combining mark, at
         * the grid the tooltip paints in, never between a letter and its mark and never inside a
         * joined sequence. A copy that fits on one line is returned untouched, and so is one with no
         * mark on a letter, so nothing about either changes.
         */
        private String breakMarkedLines(String text) {
            if (!carriesMarkOnALetter(text)) return text;
            Font font = tooltipFont();
            Text measure = new Text();
            measure.setFont(font);
            if (fits(text, measure)) return text;
            StringBuilder broken = new StringBuilder(text.length() + 8);
            String rest = text;
            while (!rest.isEmpty()) {
                int fitted = 0;
                int space = -1;
                for (int cursor = 0; cursor < rest.length(); cursor = nextCluster(rest, cursor)) {
                    int next = nextCluster(rest, cursor);
                    measure.setText(rest.substring(0, next));
                    if (measure.getLayoutBounds().getWidth() > TOOLTIP_TEXT_WIDTH && cursor > 0) break;
                    fitted = next;
                    if (rest.charAt(next - 1) == ' ') space = next - 1;
                }
                if (fitted >= rest.length()) {
                    broken.append(rest);
                    break;
                }
                // A space reads better than the middle of a word, as long as it does not leave the
                // line mostly empty; otherwise the last cluster that fitted is the break.
                boolean atSpace = space > fitted / 2;
                broken.append(rest, 0, atSpace ? space : fitted).append('\n');
                rest = rest.substring(atSpace ? space + 1 : fitted);
            }
            return broken.toString();
        }

        /** Whether the whole copy fits the tooltip's own width, so it needs no break at all. */
        private static boolean fits(String text, Text measure) {
            measure.setText(text);
            return measure.getLayoutBounds().getWidth() <= TOOLTIP_TEXT_WIDTH;
        }

        /** The index just past the cluster that starts at {@code offset}: a base and its marks. */
        private static int nextCluster(String text, int offset) {
            int next = offset + Character.charCount(text.codePointAt(offset));
            while (next < text.length() && attachesToPrevious(text.codePointAt(next))) {
                next += Character.charCount(text.codePointAt(next));
            }
            return next;
        }

        /** A combining mark on a letter: the case that stops JavaFX from wrapping the tooltip. */
        private static boolean carriesMarkOnALetter(String text) {
            int base = -1;
            for (int offset = 0; offset < text.length(); ) {
                int codePoint = text.codePointAt(offset);
                if (isMark(codePoint)) {
                    if (base >= 0 && Character.isLetter(base)) return true;
                } else {
                    base = codePoint;
                }
                offset += Character.charCount(codePoint);
            }
            return false;
        }

        /**
         * The font the popup paints its copy with. Its own label carries {@code .tooltip} and the
         * view's theme class, and the theme is what sets the size — so the theme's class is all that
         * is needed to ask the stylesheets, and the answer is kept until the theme changes.
         */
        private Font tooltipFont() {
            Scene scene = getScene();
            if (scene == null) return getFont();
            List<String> theme = new ArrayList<>(scene.getRoot().getStyleClass());
            theme.remove("root");
            if (!theme.equals(tooltipTheme)) {
                Label probe = new Label("");
                probe.getStyleClass().add("tooltip");
                probe.getStyleClass().addAll(theme);
                Scene scratch = new Scene(new StackPane(probe));
                scratch.getStylesheets().setAll(scene.getStylesheets());
                probe.applyCss();
                tooltipFont = probe.getFont();
                tooltipTheme = List.copyOf(theme);
            }
            return tooltipFont;
        }

        /**
         * Whether {@code codePoint} belongs to the glyph before it, so a line must not start there:
         * a mark, or a joiner and selector that hold a sequence of them together.
         */
        private static boolean attachesToPrevious(int codePoint) {
            return isMark(codePoint) || Character.getType(codePoint) == Character.FORMAT;
        }

        private static boolean isMark(int codePoint) {
            int type = Character.getType(codePoint);
            return type == Character.NON_SPACING_MARK || type == Character.ENCLOSING_MARK
                    || type == Character.COMBINING_SPACING_MARK;
        }

        private static String shorten(String text) {
            int cut = text.lastIndexOf(' ', TOOLTIP_PREVIEW_CHARS);
            if (cut < TOOLTIP_PREVIEW_CHARS / 2) cut = TOOLTIP_PREVIEW_CHARS;
            return text.substring(0, cut).stripTrailing() + "…";
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
