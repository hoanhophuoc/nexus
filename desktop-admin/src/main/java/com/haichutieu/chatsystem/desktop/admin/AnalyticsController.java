package com.haichutieu.chatsystem.desktop.admin;

import com.haichutieu.chatsystem.desktop.shared.ui.I18n;
import com.haichutieu.chatsystem.sdk.model.ActivityAnalytics;
import com.haichutieu.chatsystem.sdk.model.ActivityAnalyticsPage;
import com.haichutieu.chatsystem.sdk.model.FriendAnalytics;
import com.haichutieu.chatsystem.sdk.model.FriendAnalyticsPage;
import com.haichutieu.chatsystem.sdk.model.MonthlyCount;
import com.haichutieu.chatsystem.sdk.model.MonthlyCounts;
import javafx.fxml.FXML;
import javafx.scene.chart.BarChart;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Button;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/** Analytics screen: monthly charts plus activity and friend-connectivity reports. */
public final class AnalyticsController {
    private final AdminAppContext context;
    private final I18n i18n;
    private BarChart<String, Number> registrationChart;
    private BarChart<String, Number> appOpenChart;

    @FXML private HBox chartsHost;
    @FXML private TextField yearField;
    @FXML private Button loadChartsButton;
    @FXML private Label analyticsStatus;
    @FXML private DatePicker activityFrom;
    @FXML private DatePicker activityTo;
    @FXML private TextField activityQuery;
    @FXML private Button loadActivityButton;
    @FXML private TableView<ActivityAnalytics> activityTable;
    @FXML private TableColumn<ActivityAnalytics, String> activityUsernameColumn;
    @FXML private TableColumn<ActivityAnalytics, String> activityOpensColumn;
    @FXML private TableColumn<ActivityAnalytics, String> activityPeopleColumn;
    @FXML private TableColumn<ActivityAnalytics, String> activityGroupsColumn;
    @FXML private Label activityStatus;
    @FXML private TextField friendQuery;
    @FXML private Button loadFriendsButton;
    @FXML private TableView<FriendAnalytics> friendTable;
    @FXML private TableColumn<FriendAnalytics, String> friendUsernameColumn;
    @FXML private TableColumn<FriendAnalytics, String> friendDirectColumn;
    @FXML private TableColumn<FriendAnalytics, String> friendOfFriendColumn;
    @FXML private Label friendStatus;

    AnalyticsController(AdminAppContext context) {
        this.context = context;
        this.i18n = context.i18n();
    }

    @FXML
    private void initialize() {
        registrationChart = AdminUi.chart(i18n.get("admin.analytics.registrationsChart"));
        appOpenChart = AdminUi.chart(i18n.get("admin.analytics.appOpensChart"));
        HBox.setHgrow(registrationChart, javafx.scene.layout.Priority.ALWAYS);
        HBox.setHgrow(appOpenChart, javafx.scene.layout.Priority.ALWAYS);
        chartsHost.getChildren().setAll(registrationChart, appOpenChart);

        yearField.setText(Integer.toString(LocalDate.now().getYear()));
        activityFrom.setValue(LocalDate.now().minusDays(30));
        activityTo.setValue(LocalDate.now());
        AdminUi.bind(activityUsernameColumn, item -> item.getUser() == null ? ""
                : item.getUser().getUsername());
        AdminUi.bind(activityOpensColumn, item -> Integer.toString(item.getAppOpens()));
        AdminUi.bind(activityPeopleColumn, item -> Integer.toString(item.getPeopleChattedWith()));
        AdminUi.bind(activityGroupsColumn, item -> Integer.toString(item.getGroupsChattedWith()));
        AdminUi.bind(friendUsernameColumn, item -> item.getUser() == null ? ""
                : item.getUser().getUsername());
        AdminUi.bind(friendDirectColumn, item -> Integer.toString(item.getDirectFriends()));
        AdminUi.bind(friendOfFriendColumn, item -> Integer.toString(item.getFriendsOfFriends()));
        activityTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        friendTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        activityTable.setAccessibleText(i18n.get("admin.analytics.activityTitle"));
        friendTable.setAccessibleText(i18n.get("admin.analytics.friendTitle"));
        yearField.setAccessibleText(i18n.get("admin.analytics.yearPrompt"));
        activityFrom.setAccessibleText(i18n.get("admin.analytics.from"));
        activityTo.setAccessibleText(i18n.get("admin.analytics.to"));
        activityQuery.setAccessibleText(i18n.get("admin.analytics.filterPrompt"));
        friendQuery.setAccessibleText(i18n.get("admin.analytics.friendFilterPrompt"));
        AdminUi.empty(activityTable, i18n, "admin.analytics.activityEmptyTitle",
                "admin.analytics.activityEmptyHint");
        AdminUi.empty(friendTable, i18n, "admin.analytics.friendEmptyTitle",
                "admin.analytics.friendEmptyHint");
        activityStatus.setText(i18n.get("admin.analytics.activityHint"));
        friendStatus.setText(i18n.get("admin.analytics.friendHint"));
        loadChartsButton.setOnAction(event -> loadCharts());
        loadActivityButton.setOnAction(event -> loadActivity());
        loadFriendsButton.setOnAction(event -> loadFriends());
        activityQuery.setOnAction(event -> loadActivity());
        friendQuery.setOnAction(event -> loadFriends());
        loadCharts();
    }

    private void loadCharts() {
        Integer year = parseYear();
        if (year == null) return;
        analyticsStatus.setText(i18n.get("admin.analytics.chartsLoading"));
        context.runAsync(() -> new MonthlyCounts[]{
                context.session().api().adminGetMonthlyRegistrations(year),
                context.session().api().adminGetMonthlyAppOpens(year)
        }, counts -> {
            drawChart(registrationChart, counts[0]);
            drawChart(appOpenChart, counts[1]);
            analyticsStatus.setText(i18n.get("admin.analytics.chartsLoaded"));
        }, exception -> analyticsStatus.setText(AdminErrors.message(exception, i18n)));
    }

    private void loadActivity() {
        LocalDate from = activityFrom.getValue();
        LocalDate to = activityTo.getValue();
        if (from == null || to == null) {
            activityStatus.setText(i18n.get("admin.analytics.dateRequired"));
            return;
        }
        if (!from.isBefore(to)) {
            activityStatus.setText(i18n.get("admin.analytics.dateOrder"));
            return;
        }
        String query = activityQuery.getText() == null ? null : activityQuery.getText().strip();
        OffsetDateTime fromTime = AdminUi.startOfDay(from);
        OffsetDateTime toTime = AdminUi.startOfDay(to.plusDays(1));
        activityStatus.setText(i18n.get("admin.analytics.activityLoading"));
        AdminUi.loading(activityTable, i18n);
        context.runAsync(() -> {
            List<ActivityAnalytics> all = new ArrayList<>();
            String cursor = null;
            do {
                ActivityAnalyticsPage page = context.session().api().adminGetActivityAnalytics(
                        fromTime, toTime, query == null || query.isBlank() ? null : query,
                        null, null, "appOpens", "desc", cursor, 100);
                if (page == null || page.getItems() == null) break;
                all.addAll(page.getItems());
                cursor = page.getNextCursor();
            } while (cursor != null && !cursor.isBlank());
            return all;
        }, all -> {
            AdminUi.setItems(activityTable, all);
            AdminUi.empty(activityTable, i18n, "admin.analytics.activityEmptyTitle",
                    "admin.analytics.activityEmptyHint");
            activityStatus.setText(i18n.format("admin.analytics.activityCount", all.size()));
        }, exception -> {
            activityStatus.setText(AdminErrors.message(exception, i18n));
            AdminUi.error(activityTable, i18n, exception, this::loadActivity);
        });
    }

    private void loadFriends() {
        String query = friendQuery.getText() == null ? null : friendQuery.getText().strip();
        friendStatus.setText(i18n.get("admin.analytics.friendLoading"));
        AdminUi.loading(friendTable, i18n);
        context.runAsync(() -> {
            List<FriendAnalytics> all = new ArrayList<>();
            String cursor = null;
            do {
                FriendAnalyticsPage page = context.session().api().adminGetFriendAnalytics(
                        query == null || query.isBlank() ? null : query,
                        null, null, "directFriends", "desc", cursor, 100);
                if (page == null || page.getItems() == null) break;
                all.addAll(page.getItems());
                cursor = page.getNextCursor();
            } while (cursor != null && !cursor.isBlank());
            return all;
        }, all -> {
            AdminUi.setItems(friendTable, all);
            AdminUi.empty(friendTable, i18n, "admin.analytics.friendEmptyTitle",
                    "admin.analytics.friendEmptyHint");
            friendStatus.setText(i18n.format("admin.analytics.friendCount", all.size()));
        }, exception -> {
            friendStatus.setText(AdminErrors.message(exception, i18n));
            AdminUi.error(friendTable, i18n, exception, this::loadFriends);
        });
    }

    private Integer parseYear() {
        String text = yearField.getText() == null ? "" : yearField.getText().strip();
        try {
            int year = Integer.parseInt(text);
            if (year < 2000 || year > 9999) {
                analyticsStatus.setText(i18n.get("admin.analytics.yearRange"));
                return null;
            }
            return year;
        } catch (NumberFormatException exception) {
            analyticsStatus.setText(i18n.get("admin.analytics.invalidYear"));
            return null;
        }
    }

    private void drawChart(BarChart<String, Number> chart, MonthlyCounts counts) {
        XYChart.Series<String, Number> series = new XYChart.Series<>();
        String[] labels = i18n.get("admin.analytics.months").split(",");
        if (counts != null && counts.getMonths() != null) {
            for (MonthlyCount month : counts.getMonths()) {
                int index = month.getMonth() == null ? 0 : month.getMonth() - 1;
                String label = index >= 0 && index < labels.length ? labels[index].strip()
                        : Integer.toString(index + 1);
                series.getData().add(new XYChart.Data<>(label, month.getCount()));
            }
        }
        chart.getData().setAll(series);
    }
}
