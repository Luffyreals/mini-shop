package com.minishop.common;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

/** Định dạng tiền VND, ví dụ {@code 1.250.000 ₫} (dấu cách thường, không phải NBSP). */
public class VndFormatter {

    public String format(BigDecimal amount) {
        if (amount == null) {
            return "";
        }
        DecimalFormatSymbols symbols = new DecimalFormatSymbols(Locale.ROOT);
        symbols.setGroupingSeparator('.');
        symbols.setDecimalSeparator(',');
        // DecimalFormat không thread-safe nên tạo mới mỗi lần gọi.
        DecimalFormat df = new DecimalFormat("#,##0", symbols);
        df.setRoundingMode(RoundingMode.HALF_UP);
        return df.format(amount) + " ₫";
    }
}
