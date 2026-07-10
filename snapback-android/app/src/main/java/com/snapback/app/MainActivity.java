package com.snapback.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public class MainActivity extends Activity {
    private static final int REQUEST_IMAGE = 1201;
    private static final String PREFS = "snapback_prefs";
    private static final String KEY_ITEMS = "items";

    private static final String[] CATEGORIES = {
            "Ideje", "Nakupi", "Potovanja", "Dogodki", "Dokumenti", "Dokazi", "Drugo"
    };

    private static final String[] NEXT_STEPS = {
            "Samo shrani", "Kupi pozneje", "Preveri pozneje", "Dodaj v načrt", "Shrani kot dokaz"
    };

    private final List<SnapItem> items = new ArrayList<>();
    private LinearLayout listContainer;
    private TextView emptyView;
    private TextView statsView;
    private EditText searchInput;
    private Spinner categoryFilter;
    private String currentSearch = "";
    private String currentCategory = "Vse kategorije";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            loadItems();
            setContentView(buildMainUi());
            renderItems();
            handleIncomingIntent(getIntent());
        } catch (Throwable error) {
            showFatalScreen(error);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIncomingIntent(intent);
    }

    private View buildMainUi() {
        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.setBackgroundColor(Color.rgb(246, 247, 251));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(22), dp(18), dp(36));
        scrollView.addView(root, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(dp(20), dp(20), dp(20), dp(20));
        header.setBackground(rounded(Color.rgb(25, 34, 56), 22));
        header.setElevation(dp(4));
        root.addView(header, fullWidth());

        TextView logo = makeText("SNAPBACK", 13, Typeface.BOLD, Color.rgb(124, 214, 183));
        header.addView(logo);

        TextView title = makeText("Shrani zdaj. Najdi takrat, ko potrebuješ.", 27,
                Typeface.BOLD, Color.WHITE);
        LinearLayout.LayoutParams titleParams = fullWidth();
        titleParams.setMargins(0, dp(6), 0, dp(8));
        header.addView(title, titleParams);

        TextView subtitle = makeText(
                "Fotografije, posnetki zaslona, povezave in ideje na enem mestu.",
                15, Typeface.NORMAL, Color.rgb(213, 220, 232));
        header.addView(subtitle);

        statsView = makeText("0 shranjenih stvari", 15, Typeface.BOLD, Color.rgb(25, 34, 56));
        statsView.setGravity(Gravity.CENTER);
        statsView.setPadding(dp(12), dp(13), dp(12), dp(13));
        statsView.setBackground(rounded(Color.WHITE, 16));
        statsView.setElevation(dp(2));
        LinearLayout.LayoutParams statsParams = fullWidth();
        statsParams.setMargins(0, dp(14), 0, dp(14));
        root.addView(statsView, statsParams);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER);

        Button addImage = makePrimaryButton("+ Dodaj sliko");
        addImage.setOnClickListener(view -> chooseImage());
        actions.addView(addImage, new LinearLayout.LayoutParams(0, dp(54), 1f));

        Button addNote = makeSecondaryButton("+ Dodaj zapis");
        addNote.setOnClickListener(view -> showEditor(null, "", null, false));
        LinearLayout.LayoutParams noteParams = new LinearLayout.LayoutParams(0, dp(54), 1f);
        noteParams.setMargins(dp(10), 0, 0, 0);
        actions.addView(addNote, noteParams);
        root.addView(actions, fullWidth());

        TextView shareHint = makeText(
                "Namig: v Galeriji izberi sliko → Deli → SnapBack.",
                13, Typeface.NORMAL, Color.rgb(85, 91, 103));
        shareHint.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams hintParams = fullWidth();
        hintParams.setMargins(0, dp(10), 0, dp(20));
        root.addView(shareHint, hintParams);

        searchInput = new EditText(this);
        searchInput.setSingleLine(true);
        searchInput.setTextSize(16);
        searchInput.setHint("Išči po naslovu, opombi ali kategoriji …");
        searchInput.setPadding(dp(15), 0, dp(15), 0);
        searchInput.setBackground(roundedStroke(Color.WHITE, Color.rgb(218, 222, 231), 14));
        root.addView(searchInput, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(52)));

        searchInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                currentSearch = s == null ? "" : s.toString().trim().toLowerCase(Locale.ROOT);
                renderItems();
            }
            @Override public void afterTextChanged(Editable s) { }
        });

        List<String> filterValues = new ArrayList<>();
        filterValues.add("Vse kategorije");
        for (String category : CATEGORIES) filterValues.add(category);

        categoryFilter = new Spinner(this);
        ArrayAdapter<String> filterAdapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_dropdown_item, filterValues);
        categoryFilter.setAdapter(filterAdapter);
        categoryFilter.setBackground(roundedStroke(Color.WHITE, Color.rgb(218, 222, 231), 14));
        categoryFilter.setPadding(dp(10), 0, dp(10), 0);
        categoryFilter.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                currentCategory = filterValues.get(position);
                renderItems();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
        LinearLayout.LayoutParams filterParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(52));
        filterParams.setMargins(0, dp(10), 0, dp(18));
        root.addView(categoryFilter, filterParams);

        TextView sectionTitle = makeText("Moji SnapBacki", 21, Typeface.BOLD, Color.rgb(25, 34, 56));
        root.addView(sectionTitle);

        emptyView = makeText(
                "Še ničesar nisi shranil.\nDodaj prvo sliko, idejo ali povezavo.",
                16, Typeface.NORMAL, Color.rgb(100, 106, 119));
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setPadding(dp(16), dp(40), dp(16), dp(40));
        root.addView(emptyView, fullWidth());

        listContainer = new LinearLayout(this);
        listContainer.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams listParams = fullWidth();
        listParams.setMargins(0, dp(12), 0, 0);
        root.addView(listContainer, listParams);

        TextView privacy = makeText(
                "V0.1 · Vse je shranjeno samo na tej napravi. Brez računa in brez oblaka.",
                12, Typeface.NORMAL, Color.rgb(116, 121, 132));
        privacy.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams privacyParams = fullWidth();
        privacyParams.setMargins(0, dp(22), 0, 0);
        root.addView(privacy, privacyParams);

        return scrollView;
    }

    private void chooseImage() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        startActivityForResult(intent, REQUEST_IMAGE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_IMAGE && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null) importImageAndEdit(uri);
        }
    }

    private void handleIncomingIntent(Intent intent) {
        if (intent == null || !Intent.ACTION_SEND.equals(intent.getAction())) return;

        String type = intent.getType();
        if (type != null && type.startsWith("image/")) {
            Uri uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (uri != null) importImageAndEdit(uri);
        } else if ("text/plain".equals(type)) {
            String text = intent.getStringExtra(Intent.EXTRA_TEXT);
            showEditor(null, text == null ? "" : text, null, false);
        }

        setIntent(new Intent());
    }

    private void importImageAndEdit(Uri uri) {
        String copiedPath = copyToPrivateStorage(uri);
        if (copiedPath == null) {
            Toast.makeText(this, "Slike ni bilo mogoče uvoziti.", Toast.LENGTH_LONG).show();
            return;
        }
        showEditor(copiedPath, "", null, true);
    }

    private void showEditor(String imagePath, String sharedText, SnapItem existing, boolean deleteImageOnCancel) {
        ScrollView editorScroll = new ScrollView(this);
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(20), dp(8), dp(20), dp(8));
        editorScroll.addView(form);

        String activeImage = existing != null ? existing.imagePath : imagePath;
        if (activeImage != null && new File(activeImage).exists()) {
            ImageView preview = new ImageView(this);
            preview.setScaleType(ImageView.ScaleType.CENTER_CROP);
            preview.setImageBitmap(decodeSampledBitmap(activeImage, 900, 500));
            preview.setBackground(rounded(Color.rgb(235, 238, 244), 16));
            form.addView(preview, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(190)));
        }

        TextView titleLabel = fieldLabel("Naslov");
        form.addView(titleLabel, labelParams());

        EditText titleInput = fieldInput("Kaj si shranil?");
        if (existing != null) {
            titleInput.setText(existing.title);
        } else if (sharedText != null && !sharedText.trim().isEmpty()) {
            titleInput.setText(shorten(sharedText.trim(), 80));
        }
        form.addView(titleInput, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(52)));

        form.addView(fieldLabel("Kategorija"), labelParams());
        Spinner categorySpinner = new Spinner(this);
        ArrayAdapter<String> categoryAdapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_dropdown_item, CATEGORIES);
        categorySpinner.setAdapter(categoryAdapter);
        categorySpinner.setBackground(roundedStroke(Color.WHITE, Color.rgb(210, 215, 225), 12));
        categorySpinner.setPadding(dp(8), 0, dp(8), 0);
        form.addView(categorySpinner, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(52)));

        form.addView(fieldLabel("Kaj želiš narediti s tem?"), labelParams());
        Spinner nextStepSpinner = new Spinner(this);
        ArrayAdapter<String> nextStepAdapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_dropdown_item, NEXT_STEPS);
        nextStepSpinner.setAdapter(nextStepAdapter);
        nextStepSpinner.setBackground(roundedStroke(Color.WHITE, Color.rgb(210, 215, 225), 12));
        nextStepSpinner.setPadding(dp(8), 0, dp(8), 0);
        form.addView(nextStepSpinner, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(52)));

        form.addView(fieldLabel("Opomba ali povezava"), labelParams());
        EditText noteInput = fieldInput("Zakaj bo to pozneje pomembno?");
        noteInput.setSingleLine(false);
        noteInput.setGravity(Gravity.TOP | Gravity.START);
        noteInput.setPadding(dp(13), dp(13), dp(13), dp(13));
        if (existing != null) {
            noteInput.setText(existing.note);
        } else if (sharedText != null) {
            noteInput.setText(sharedText);
        }
        form.addView(noteInput, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(110)));

        if (existing != null) {
            selectSpinnerValue(categorySpinner, CATEGORIES, existing.category);
            selectSpinnerValue(nextStepSpinner, NEXT_STEPS, existing.nextStep);
        }

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(existing == null ? "Nov SnapBack" : "Uredi SnapBack")
                .setView(editorScroll)
                .setNegativeButton("Prekliči", null)
                .setPositiveButton("Shrani", null)
                .create();

        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                String titleValue = titleInput.getText().toString().trim();
                if (titleValue.isEmpty()) {
                    titleInput.setError("Vpiši kratek naslov.");
                    return;
                }

                if (existing == null) {
                    SnapItem item = new SnapItem();
                    item.id = UUID.randomUUID().toString();
                    item.title = titleValue;
                    item.category = String.valueOf(categorySpinner.getSelectedItem());
                    item.nextStep = String.valueOf(nextStepSpinner.getSelectedItem());
                    item.note = noteInput.getText().toString().trim();
                    item.imagePath = imagePath;
                    item.createdAt = System.currentTimeMillis();
                    items.add(item);
                } else {
                    existing.title = titleValue;
                    existing.category = String.valueOf(categorySpinner.getSelectedItem());
                    existing.nextStep = String.valueOf(nextStepSpinner.getSelectedItem());
                    existing.note = noteInput.getText().toString().trim();
                }

                saveItems();
                renderItems();
                hideKeyboard(titleInput);
                dialog.dismiss();
                Toast.makeText(this, "Shranjeno v SnapBack.", Toast.LENGTH_SHORT).show();
            });
        });

        dialog.setOnCancelListener(dialogInterface -> {
            if (deleteImageOnCancel && existing == null && imagePath != null) {
                deleteFileQuietly(imagePath);
            }
        });

        dialog.setOnDismissListener(dialogInterface -> {
            if (deleteImageOnCancel && existing == null && imagePath != null) {
                boolean used = false;
                for (SnapItem item : items) {
                    if (imagePath.equals(item.imagePath)) {
                        used = true;
                        break;
                    }
                }
                if (!used) deleteFileQuietly(imagePath);
            }
        });

        dialog.show();
    }

    private void renderItems() {
        if (listContainer == null) return;
        listContainer.removeAllViews();

        int visibleCount = 0;
        for (int i = items.size() - 1; i >= 0; i--) {
            SnapItem item = items.get(i);
            if (!matchesFilter(item)) continue;
            visibleCount++;
            listContainer.addView(makeItemCard(item), cardParams());
        }

        emptyView.setVisibility(visibleCount == 0 ? View.VISIBLE : View.GONE);
        statsView.setText(items.size() == 1
                ? "1 shranjena stvar"
                : items.size() + " shranjenih stvari");
    }

    private boolean matchesFilter(SnapItem item) {
        if (!"Vse kategorije".equals(currentCategory)
                && !currentCategory.equals(item.category)) return false;

        if (currentSearch.isEmpty()) return true;
        String haystack = (item.title + " " + item.category + " " + item.note + " " + item.nextStep)
                .toLowerCase(Locale.ROOT);
        return haystack.contains(currentSearch);
    }

    private View makeItemCard(SnapItem item) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(14), dp(14), dp(12));
        card.setBackground(rounded(Color.WHITE, 18));
        card.setElevation(dp(2));

        if (item.imagePath != null && new File(item.imagePath).exists()) {
            ImageView image = new ImageView(this);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            image.setImageBitmap(decodeSampledBitmap(item.imagePath, 900, 500));
            image.setBackground(rounded(Color.rgb(235, 238, 244), 14));
            card.addView(image, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(155)));
        }

        LinearLayout metaRow = new LinearLayout(this);
        metaRow.setOrientation(LinearLayout.HORIZONTAL);
        metaRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams metaParams = fullWidth();
        metaParams.setMargins(0, dp(11), 0, dp(4));
        card.addView(metaRow, metaParams);

        TextView category = makeText(item.category.toUpperCase(Locale.ROOT), 12,
                Typeface.BOLD, Color.rgb(32, 111, 90));
        category.setPadding(dp(9), dp(5), dp(9), dp(5));
        category.setBackground(rounded(Color.rgb(224, 247, 239), 20));
        metaRow.addView(category);

        TextView date = makeText(formatDate(item.createdAt), 12,
                Typeface.NORMAL, Color.rgb(112, 117, 128));
        date.setGravity(Gravity.END);
        LinearLayout.LayoutParams dateParams = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        dateParams.setMargins(dp(8), 0, 0, 0);
        metaRow.addView(date, dateParams);

        TextView title = makeText(item.title, 20, Typeface.BOLD, Color.rgb(25, 34, 56));
        card.addView(title, fullWidth());

        TextView action = makeText("Naslednji korak: " + item.nextStep, 14,
                Typeface.BOLD, Color.rgb(70, 79, 98));
        LinearLayout.LayoutParams actionParams = fullWidth();
        actionParams.setMargins(0, dp(5), 0, 0);
        card.addView(action, actionParams);

        if (item.note != null && !item.note.trim().isEmpty()) {
            TextView note = makeText(shorten(item.note, 150), 14,
                    Typeface.NORMAL, Color.rgb(85, 91, 103));
            LinearLayout.LayoutParams noteParams = fullWidth();
            noteParams.setMargins(0, dp(6), 0, 0);
            card.addView(note, noteParams);
        }

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams buttonsParams = fullWidth();
        buttonsParams.setMargins(0, dp(10), 0, 0);
        card.addView(buttons, buttonsParams);

        Button edit = makeSecondaryButton("Uredi");
        edit.setOnClickListener(view -> showEditor(null, "", item, false));
        buttons.addView(edit, new LinearLayout.LayoutParams(0, dp(46), 1f));

        Button delete = makeDangerButton("Izbriši");
        delete.setOnClickListener(view -> confirmDelete(item));
        LinearLayout.LayoutParams deleteParams = new LinearLayout.LayoutParams(0, dp(46), 1f);
        deleteParams.setMargins(dp(10), 0, 0, 0);
        buttons.addView(delete, deleteParams);

        return card;
    }

    private void confirmDelete(SnapItem item) {
        new AlertDialog.Builder(this)
                .setTitle("Izbrišem SnapBack?")
                .setMessage(item.title)
                .setNegativeButton("Ne", null)
                .setPositiveButton("Izbriši", (dialog, which) -> {
                    items.remove(item);
                    if (item.imagePath != null) deleteFileQuietly(item.imagePath);
                    saveItems();
                    renderItems();
                })
                .show();
    }

    private String copyToPrivateStorage(Uri uri) {
        File directory = new File(getFilesDir(), "snapback_images");
        if (!directory.exists() && !directory.mkdirs()) return null;

        File destination = new File(directory,
                "snap_" + System.currentTimeMillis() + "_" + UUID.randomUUID() + ".img");

        try (InputStream input = getContentResolver().openInputStream(uri);
             OutputStream output = new FileOutputStream(destination)) {
            if (input == null) return null;
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            output.flush();
            return destination.getAbsolutePath();
        } catch (Throwable error) {
            deleteFileQuietly(destination.getAbsolutePath());
            return null;
        }
    }

    private Bitmap decodeSampledBitmap(String path, int reqWidth, int reqHeight) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, bounds);

            int sample = 1;
            while (bounds.outWidth / sample > reqWidth * 2
                    || bounds.outHeight / sample > reqHeight * 2) {
                sample *= 2;
            }

            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = Math.max(1, sample);
            return BitmapFactory.decodeFile(path, options);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void saveItems() {
        try {
            JSONArray array = new JSONArray();
            for (SnapItem item : items) {
                JSONObject object = new JSONObject();
                object.put("id", item.id);
                object.put("title", item.title);
                object.put("category", item.category);
                object.put("nextStep", item.nextStep);
                object.put("note", item.note);
                object.put("imagePath", item.imagePath == null ? JSONObject.NULL : item.imagePath);
                object.put("createdAt", item.createdAt);
                array.put(object);
            }
            getSharedPreferences(PREFS, MODE_PRIVATE)
                    .edit()
                    .putString(KEY_ITEMS, array.toString())
                    .apply();
        } catch (Throwable error) {
            Toast.makeText(this, "Shranjevanje ni uspelo.", Toast.LENGTH_SHORT).show();
        }
    }

    private void loadItems() {
        items.clear();
        SharedPreferences preferences = getSharedPreferences(PREFS, MODE_PRIVATE);
        String saved = preferences.getString(KEY_ITEMS, "[]");
        try {
            JSONArray array = new JSONArray(saved == null ? "[]" : saved);
            for (int i = 0; i < array.length(); i++) {
                JSONObject object = array.getJSONObject(i);
                SnapItem item = new SnapItem();
                item.id = object.optString("id", UUID.randomUUID().toString());
                item.title = object.optString("title", "Brez naslova");
                item.category = object.optString("category", "Drugo");
                item.nextStep = object.optString("nextStep", "Samo shrani");
                item.note = object.optString("note", "");
                item.imagePath = object.isNull("imagePath") ? null : object.optString("imagePath", null);
                item.createdAt = object.optLong("createdAt", System.currentTimeMillis());
                items.add(item);
            }
        } catch (Throwable ignored) {
            items.clear();
        }
    }

    private void showFatalScreen(Throwable error) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(dp(24), dp(30), dp(24), dp(30));
        root.setBackgroundColor(Color.WHITE);

        TextView title = makeText("SnapBack", 30, Typeface.BOLD, Color.rgb(25, 34, 56));
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        TextView message = makeText(
                "Pri zagonu je prišlo do napake.\n\n" + error.getClass().getSimpleName(),
                16, Typeface.NORMAL, Color.DKGRAY);
        message.setGravity(Gravity.CENTER);
        message.setPadding(0, dp(16), 0, 0);
        root.addView(message);
        setContentView(root);
    }

    private TextView fieldLabel(String text) {
        return makeText(text, 14, Typeface.BOLD, Color.rgb(50, 57, 71));
    }

    private LinearLayout.LayoutParams labelParams() {
        LinearLayout.LayoutParams params = fullWidth();
        params.setMargins(0, dp(13), 0, dp(6));
        return params;
    }

    private EditText fieldInput(String hint) {
        EditText input = new EditText(this);
        input.setHint(hint);
        input.setTextSize(16);
        input.setSingleLine(true);
        input.setPadding(dp(13), 0, dp(13), 0);
        input.setBackground(roundedStroke(Color.WHITE, Color.rgb(210, 215, 225), 12));
        return input;
    }

    private void selectSpinnerValue(Spinner spinner, String[] values, String value) {
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(value)) {
                spinner.setSelection(i);
                return;
            }
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

    private Button makePrimaryButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(15);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setTextColor(Color.WHITE);
        button.setBackground(rounded(Color.rgb(32, 111, 90), 14));
        return button;
    }

    private Button makeSecondaryButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(15);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setTextColor(Color.rgb(42, 52, 72));
        button.setBackground(rounded(Color.rgb(232, 235, 242), 14));
        return button;
    }

    private Button makeDangerButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(15);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setTextColor(Color.rgb(150, 45, 50));
        button.setBackground(rounded(Color.rgb(255, 235, 236), 14));
        return button;
    }

    private GradientDrawable rounded(int color, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }

    private GradientDrawable roundedStroke(int color, int strokeColor, int radiusDp) {
        GradientDrawable drawable = rounded(color, radiusDp);
        drawable.setStroke(dp(1), strokeColor);
        return drawable;
    }

    private LinearLayout.LayoutParams fullWidth() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams cardParams() {
        LinearLayout.LayoutParams params = fullWidth();
        params.setMargins(0, 0, 0, dp(12));
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private String formatDate(long timestamp) {
        return new SimpleDateFormat("dd.MM.yyyy · HH:mm", Locale.getDefault())
                .format(new Date(timestamp));
    }

    private String shorten(String value, int maxLength) {
        if (value == null) return "";
        String oneLine = value.replace('\n', ' ').replace('\r', ' ').trim();
        if (oneLine.length() <= maxLength) return oneLine;
        return oneLine.substring(0, Math.max(0, maxLength - 1)).trim() + "…";
    }

    private void hideKeyboard(View view) {
        InputMethodManager manager = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (manager != null && view != null) {
            manager.hideSoftInputFromWindow(view.getWindowToken(), 0);
        }
    }

    private void deleteFileQuietly(String path) {
        if (path == null) return;
        try {
            File file = new File(path);
            if (file.exists()) file.delete();
        } catch (Throwable ignored) { }
    }

    private static class SnapItem {
        String id;
        String title;
        String category;
        String nextStep;
        String note;
        String imagePath;
        long createdAt;
    }
}
