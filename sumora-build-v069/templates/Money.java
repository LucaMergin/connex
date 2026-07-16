package si.deliva.app;

import org.json.JSONObject;

import java.math.BigDecimal;
import java.math.RoundingMode;

final class Money {
    private Money() { }

    static long toCents(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) return 0L;
        return BigDecimal.valueOf(value)
                .setScale(2, RoundingMode.HALF_UP)
                .movePointRight(2)
                .longValue();
    }

    static double fromCents(long cents) {
        return BigDecimal.valueOf(cents, 2).doubleValue();
    }

    static long parseCents(String raw) {
        BigDecimal value = parseDecimal(raw);
        return value.setScale(2, RoundingMode.HALF_UP).movePointRight(2).longValueExact();
    }

    static BigDecimal parsePercent(String raw) {
        return parseDecimal(raw).setScale(4, RoundingMode.HALF_UP);
    }

    static long shareForPercent(long amountCents, BigDecimal percent) {
        if (amountCents <= 0L) return 0L;
        BigDecimal safe = percent == null ? BigDecimal.ZERO : percent;
        long share = BigDecimal.valueOf(amountCents)
                .multiply(safe)
                .divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP)
                .longValue();
        return clamp(share, 0L, amountCents);
    }

    static long amountCents(JSONObject item) {
        if (item == null) return 0L;
        if (item.has("amountCents")) return Math.max(0L, item.optLong("amountCents", 0L));
        return Math.max(0L, toCents(item.optDouble("amount", 0.0)));
    }

    static long shareACents(JSONObject item) {
        if (item == null) return 0L;
        long amount = amountCents(item);
        long share = item.has("shareACents")
                ? item.optLong("shareACents", amount / 2L)
                : toCents(item.optDouble("shareA", fromCents(amount / 2L)));
        return clamp(share, 0L, amount);
    }

    static double amount(JSONObject item) {
        return fromCents(amountCents(item));
    }

    static double shareA(JSONObject item) {
        return fromCents(shareACents(item));
    }

    static void putAmount(JSONObject item, long cents) throws Exception {
        long safe = Math.max(0L, cents);
        item.put("amountCents", safe);
        item.put("amount", fromCents(safe));
    }

    static void putShareA(JSONObject item, long cents) throws Exception {
        long safe = clamp(cents, 0L, amountCents(item));
        item.put("shareACents", safe);
        item.put("shareA", fromCents(safe));
    }

    static boolean normalizeExpense(JSONObject item) {
        if (item == null) return false;
        try {
            long amount = amountCents(item);
            long share = shareACents(item);
            boolean changed = !item.has("amountCents")
                    || item.optLong("amountCents", Long.MIN_VALUE) != amount
                    || Math.abs(item.optDouble("amount", Double.NaN) - fromCents(amount)) > 0.000001
                    || !item.has("shareACents")
                    || item.optLong("shareACents", Long.MIN_VALUE) != share
                    || Math.abs(item.optDouble("shareA", Double.NaN) - fromCents(share)) > 0.000001;
            putAmount(item, amount);
            putShareA(item, share);
            return changed;
        } catch (Exception ignored) {
            return false;
        }
    }

    static boolean normalizeSettlement(JSONObject item) {
        if (item == null) return false;
        try {
            long amount = amountCents(item);
            boolean changed = !item.has("amountCents")
                    || item.optLong("amountCents", Long.MIN_VALUE) != amount
                    || Math.abs(item.optDouble("amount", Double.NaN) - fromCents(amount)) > 0.000001;
            putAmount(item, amount);
            return changed;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static BigDecimal parseDecimal(String raw) {
        if (raw == null) throw new NumberFormatException("Prazen vnos");
        String value = raw.trim().replace("€", "").replace(" ", "").replace("\u00A0", "");
        if (value.isEmpty()) throw new NumberFormatException("Prazen vnos");

        int comma = value.lastIndexOf(',');
        int dot = value.lastIndexOf('.');
        if (comma >= 0 && dot >= 0) {
            if (comma > dot) value = value.replace(".", "").replace(',', '.');
            else value = value.replace(",", "");
        } else if (comma >= 0) {
            value = value.replace(',', '.');
        }
        return new BigDecimal(value);
    }

    private static long clamp(long value, long min, long max) {
        return Math.max(min, Math.min(max, value));
    }
}
