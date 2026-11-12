package com.nexus.desktop.admin;

import com.nexus.desktop.shared.ui.I18n;
import com.nexus.desktop.shared.ui.UiComponents;
import com.nexus.sdk.model.GroupMember;
import javafx.beans.property.SimpleStringProperty;
import javafx.scene.Node;
import javafx.scene.chart.BarChart;
import javafx.scene.chart.CategoryAxis;
import javafx.scene.chart.NumberAxis;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;

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
