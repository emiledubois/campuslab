package cl.campuslab.report.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ReportRangeTest {

    @Test
    void fromParam_last24h_resolvesTo24Buckets() {
        ReportRange range = ReportRange.fromParam("last24h");

        assertThat(range.bucketCount()).isEqualTo(24);
    }

    @Test
    void fromParam_last7d_resolvesTo168Buckets() {
        ReportRange range = ReportRange.fromParam("last7d");

        assertThat(range.bucketCount()).isEqualTo(168);
    }

    @Test
    void fromParam_last30d_resolvesTo720Buckets() {
        ReportRange range = ReportRange.fromParam("last30d");

        assertThat(range.bucketCount()).isEqualTo(720);
    }

    @Test
    void fromParam_unrecognizedValue_throwsInvalidRangeExceptionNamingAcceptedValues() {
        assertThatThrownBy(() -> ReportRange.fromParam("lastYear"))
                .isInstanceOf(InvalidRangeException.class)
                .hasMessageContaining("last24h")
                .hasMessageContaining("last7d")
                .hasMessageContaining("last30d");
    }
}
