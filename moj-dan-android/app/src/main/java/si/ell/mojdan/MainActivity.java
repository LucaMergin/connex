package si.ell.mojdan;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {
    private static final String PREFS = "moj_dan_prefs";
    private static final String KEY_TASKS = "tasks";

    private final List<Task> tasks = new ArrayList<>();
    private LinearLayout taskContainer;
    private EditText taskInput;
    private TextView progressText;
    private ProgressBar progressBar;
    private TextView emptyText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            loadTasks();
            setContentView(buildUi());
            renderTasks();
        } catch (Throwable error) {
            showStartupError(error);
        }
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(245, 248, 252));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(24), dp(20), dp(28));
        scroll.addView(root, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));

        TextView title = makeText("Moj dan", 30, Typeface.BOLD, Color.rgb(23, 78, 166));
        root.addView(title);

        TextView subtitle = makeText("Majhni koraki. Dober dan.", 16, Typeface.NORMAL, Color.DKGRAY);
        LinearLayout.LayoutParams subtitleParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        subtitleParams.setMargins(0, dp(4), 0, dp(20));
        root.addView(subtitle, subtitleParams);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(18), dp(18), dp(18));
        card.setBackgroundColor(Color.WHITE);
        root.addView(card, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        progressText = makeText("Dan je pripravljen", 16, Typeface.BOLD, Color.rgb(35, 35, 35));
        card.addView(progressText);

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(12));
        progressParams.setMargins(0, dp(10), 0, dp(18));
        card.addView(progressBar, progressParams);

        LinearLayout inputRow = new LinearLayout(this);
        inputRow.setOrientation(LinearLayout.HORIZONTAL);
        inputRow.setGravity(Gravity.CENTER_VERTICAL);

        taskInput = new EditText(this);
        taskInput.setHint("Kaj želiš danes narediti?");
        taskInput.setSingleLine(true);
        taskInput.setTextSize(16);
        inputRow.addView(taskInput, new LinearLayout.LayoutParams(0, dp(52), 1f));

        Button addButton = new Button(this);
        addButton.setText("Dodaj");
        addButton.setAllCaps(false);
        addButton.setTextSize(16);
        addButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                addTask();
            }
        });
        LinearLayout.LayoutParams addParams = new LinearLayout.LayoutParams(dp(92), dp(52));
        addParams.setMargins(dp(10), 0, 0, 0);
        inputRow.addView(addButton, addParams);
        card.addView(inputRow);

        TextView sectionTitle = makeText("Današnja opravila", 20, Typeface.BOLD, Color.rgb(35, 35, 35));
        LinearLayout.LayoutParams sectionParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        sectionParams.setMargins(0, dp(24), 0, dp(10));
        root.addView(sectionTitle, sectionParams);

        emptyText = makeText("Seznam je prazen. Dodaj prvo opravilo.", 16, Typeface.NORMAL, Color.GRAY);
        emptyText.setGravity(Gravity.CENTER);
        emptyText.setPadding(dp(8), dp(28), dp(8), dp(28));
        root.addView(emptyText);

        taskContainer = new LinearLayout(this);
        taskContainer.setOrientation(LinearLayout.VERTICAL);
        root.addView(taskContainer, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        Button clearDone = new Button(this);
        clearDone.setText("Odstrani končana opravila");
        clearDone.setAllCaps(false);
        clearDone.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                for (int i = tasks.size() - 1; i >= 0; i--) {
                    if (tasks.get(i).done) {
                        tasks.remove(i);
                    }
                }
                saveTasks();
                renderTasks();
            }
        });
        LinearLayout.LayoutParams clearParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(52));
        clearParams.setMargins(0, dp(18), 0, 0);
        root.addView(clearDone, clearParams);

        return scroll;
    }

    private void addTask() {
        String value = taskInput.getText().toString().trim();
        if (value.length() == 0) {
            Toast.makeText(this, "Najprej vpiši opravilo.", Toast.LENGTH_SHORT).show();
            return;
        }

        tasks.add(new Task(value, false));
        taskInput.setText("");
        saveTasks();
        renderTasks();

        InputMethodManager manager = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (manager != null) {
            manager.hideSoftInputFromWindow(taskInput.getWindowToken(), 0);
        }
    }

    private void renderTasks() {
        taskContainer.removeAllViews();
        emptyText.setVisibility(tasks.isEmpty() ? View.VISIBLE : View.GONE);

        int completed = 0;
        for (int i = 0; i < tasks.size(); i++) {
            final int index = i;
            Task task = tasks.get(i);
            if (task.done) {
                completed++;
            }

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(12), dp(8), dp(6), dp(8));
            row.setBackgroundColor(Color.WHITE);

            CheckBox checkBox = new CheckBox(this);
            checkBox.setChecked(task.done);
            checkBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (index < tasks.size()) {
                    tasks.get(index).done = isChecked;
                    saveTasks();
                    renderTasks();
                }
            });
            row.addView(checkBox);

            TextView taskText = makeText(task.text, 17, Typeface.NORMAL,
                    task.done ? Color.GRAY : Color.rgb(30, 30, 30));
            if (task.done) {
                taskText.setPaintFlags(taskText.getPaintFlags() | android.graphics.Paint.STRIKE_THRU_TEXT_FLAG);
            }
            row.addView(taskText, new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            Button delete = new Button(this);
            delete.setText("×");
            delete.setTextSize(20);
            delete.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    if (index < tasks.size()) {
                        tasks.remove(index);
                        saveTasks();
                        renderTasks();
                    }
                }
            });
            row.addView(delete, new LinearLayout.LayoutParams(dp(48), dp(48)));

            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            rowParams.setMargins(0, 0, 0, dp(8));
            taskContainer.addView(row, rowParams);
        }

        int percent = tasks.isEmpty() ? 0 : Math.round(completed * 100f / tasks.size());
        progressBar.setProgress(percent);
        if (tasks.isEmpty()) {
            progressText.setText("Dan je pripravljen");
        } else {
            progressText.setText(completed + " od " + tasks.size() + " opravljenih · " + percent + " %");
        }
    }

    private TextView makeText(String value, int size, int style, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTypeface(Typeface.DEFAULT, style);
        view.setTextColor(color);
        return view;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void saveTasks() {
        try {
            JSONArray array = new JSONArray();
            for (Task task : tasks) {
                JSONObject object = new JSONObject();
                object.put("text", task.text);
                object.put("done", task.done);
                array.put(object);
            }
            getSharedPreferences(PREFS, MODE_PRIVATE)
                    .edit()
                    .putString(KEY_TASKS, array.toString())
                    .apply();
        } catch (Throwable ignored) {
        }
    }

    private void loadTasks() {
        tasks.clear();
        try {
            SharedPreferences preferences = getSharedPreferences(PREFS, MODE_PRIVATE);
            String saved = preferences.getString(KEY_TASKS, "[]");
            JSONArray array = new JSONArray(saved == null ? "[]" : saved);
            for (int i = 0; i < array.length(); i++) {
                JSONObject object = array.getJSONObject(i);
                tasks.add(new Task(
                        object.optString("text", "Opravilo"),
                        object.optBoolean("done", false)));
            }
        } catch (Throwable ignored) {
            tasks.clear();
        }
    }

    private void showStartupError(Throwable error) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24), dp(32), dp(24), dp(24));
        root.setGravity(Gravity.CENTER);

        TextView title = makeText("Moj dan", 28, Typeface.BOLD, Color.rgb(23, 78, 166));
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        TextView message = makeText(
                "Aplikacija se je odprla, vendar je pri pripravi zaslona prišlo do napake.\n\n" +
                        error.getClass().getSimpleName(),
                16,
                Typeface.NORMAL,
                Color.DKGRAY);
        message.setGravity(Gravity.CENTER);
        message.setPadding(0, dp(20), 0, 0);
        root.addView(message);

        setContentView(root);
    }

    private static class Task {
        final String text;
        boolean done;

        Task(String text, boolean done) {
            this.text = text;
            this.done = done;
        }
    }
}
