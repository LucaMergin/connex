package si.deliva.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.AtomicFile;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

final class LocalDataStore {
    private static final String PREFS = "deliva_v02";
    private static final String KEY_EXPENSES = "expenses";
    private static final String KEY_SETTLEMENTS = "settlements";
    private static final String KEY_NAME_A = "name_a";
    private static final String KEY_NAME_B = "name_b";
    private static final int SCHEMA_VERSION = 1;

    static final class State {
        final JSONArray expenses;
        final JSONArray settlements;
        final String nameA;
        final String nameB;

        State(JSONArray expenses, JSONArray settlements, String nameA, String nameB) {
            this.expenses = expenses == null ? new JSONArray() : expenses;
            this.settlements = settlements == null ? new JSONArray() : settlements;
            this.nameA = cleanName(nameA, "Jaz");
            this.nameB = cleanName(nameB, "Partner/ka");
        }
    }

    private final SharedPreferences legacy;
    private final AtomicFile file;

    LocalDataStore(Context context) {
        Context app = context.getApplicationContext();
        this.legacy = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        this.file = new AtomicFile(new File(app.getFilesDir(), "sumora_data_v1.json"));
    }

    State load() {
        try {
            if (file.getBaseFile().exists()) {
                byte[] bytes = file.readFully();
                JSONObject root = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
                String expensesText = root.optJSONArray("expenses") == null ? "[]" : root.optJSONArray("expenses").toString();
                String settlementsText = root.optJSONArray("settlements") == null ? "[]" : root.optJSONArray("settlements").toString();
                String nameA = root.optString("nameA", "Jaz");
                String nameB = root.optString("nameB", "Partner/ka");
                String expected = checksum(expensesText, settlementsText, nameA, nameB);
                if (!expected.equals(root.optString("checksum", ""))) throw new IllegalStateException("Kontrolna vsota ni veljavna.");
                return new State(new JSONArray(expensesText), new JSONArray(settlementsText), nameA, nameB);
            }
        } catch (Exception ignored) { }
        return loadLegacy();
    }

    boolean save(JSONArray expenses, JSONArray settlements, String nameA, String nameB) {
        String expensesText = expenses == null ? "[]" : expenses.toString();
        String settlementsText = settlements == null ? "[]" : settlements.toString();
        String safeA = cleanName(nameA, "Jaz");
        String safeB = cleanName(nameB, "Partner/ka");
        FileOutputStream output = null;
        try {
            JSONObject root = new JSONObject();
            root.put("schemaVersion", SCHEMA_VERSION);
            root.put("savedAt", System.currentTimeMillis());
            root.put("nameA", safeA);
            root.put("nameB", safeB);
            root.put("expenses", new JSONArray(expensesText));
            root.put("settlements", new JSONArray(settlementsText));
            root.put("checksum", checksum(expensesText, settlementsText, safeA, safeB));

            output = file.startWrite();
            output.write(root.toString().getBytes(StandardCharsets.UTF_8));
            output.flush();
            file.finishWrite(output);
            output = null;

            legacy.edit()
                    .putString(KEY_EXPENSES, expensesText)
                    .putString(KEY_SETTLEMENTS, settlementsText)
                    .putString(KEY_NAME_A, safeA)
                    .putString(KEY_NAME_B, safeB)
                    .commit();
            return true;
        } catch (Exception ignored) {
            if (output != null) file.failWrite(output);
            return false;
        }
    }

    private State loadLegacy() {
        try {
            return new State(
                    new JSONArray(legacy.getString(KEY_EXPENSES, "[]")),
                    new JSONArray(legacy.getString(KEY_SETTLEMENTS, "[]")),
                    legacy.getString(KEY_NAME_A, "Jaz"),
                    legacy.getString(KEY_NAME_B, "Partner/ka")
            );
        } catch (Exception ignored) {
            return new State(new JSONArray(), new JSONArray(), "Jaz", "Partner/ka");
        }
    }

    private static String checksum(String expenses, String settlements, String nameA, String nameB) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] bytes = digest.digest((expenses + "\n" + settlements + "\n" + nameA + "\n" + nameB)
                .getBytes(StandardCharsets.UTF_8));
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(String.format(java.util.Locale.ROOT, "%02x", value & 0xff));
        return result.toString();
    }

    private static String cleanName(String value, String fallback) {
        String clean = value == null ? "" : value.trim();
        return clean.isEmpty() ? fallback : clean;
    }
}
