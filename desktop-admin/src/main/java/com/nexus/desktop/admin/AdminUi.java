package com.nexus.desktop.admin;

import com.nexus.desktop.shared.ui.I18n;
import com.nexus.desktop.shared.ui.UiComponents;
import com.nexus.sdk.model.GroupMember;
import javafx.beans.property.SimpleStringProperty;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.chart.BarChart;
import javafx.scene.chart.CategoryAxis;
import javafx.scene.chart.NumberAxis;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.text.Font;
import javafx.scene.text.Text;
import javafx.scene.text.TextAlignment;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
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
    }

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

    private static final class TruncatingTextCell<T> extends TableCell<T, String> {
        private final Tooltip tooltip = new Tooltip();
        private String measuredText;
        private Font measuredFont;
        private double measuredWidth = Double.NaN;
        private boolean measuredTruncated;

        private TruncatingTextCell() {
            tooltip.setWrapText(true);
            tooltip.setMaxWidth(TOOLTIP_WRAP_WIDTH);
        }

        @Override
        protected void updateItem(String item, boolean empty) {
            super.updateItem(item, empty);
            setText(empty ? null : item);
            explainIfTruncated();
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
