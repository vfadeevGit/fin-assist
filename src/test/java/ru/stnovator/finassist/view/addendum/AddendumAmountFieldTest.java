package ru.stnovator.finassist.view.addendum;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class AddendumAmountFieldTest {
    @ParameterizedTest
    @ValueSource(strings = {"1234.56", "1234,56", "1 234,56", "1\u00a0234.56", "1\u202f234,56", " 1234,56 "})
    void acceptsExactDecimalAndSpaceGrouping(String input) {
        assertThat(AddendumAmountField.parse(input)).isEqualByComparingTo("1234.56");
    }
    @ParameterizedTest
    @ValueSource(strings = {"12,345", "1,234.56", "1e3", "-", "12,", "12.", "1 23", "100000000000000000", "NaN", "+1"})
    void rejectsMalformedOrOutOfRangeInput(String input) {
        assertThatIllegalArgumentException().isThrownBy(() -> AddendumAmountField.parse(input));
    }
    @Test void preservesNullZeroSignAndMaximumPrecision() {
        assertThat(AddendumAmountField.parse(" ")).isNull();
        assertThat(AddendumAmountField.parse("0")).isZero();
        assertThat(AddendumAmountField.parse("-5000,25")).isEqualByComparingTo("-5000.25");
        assertThat(AddendumAmountField.parse("99999999999999999,99")).isEqualByComparingTo("99999999999999999.99");
    }
}
