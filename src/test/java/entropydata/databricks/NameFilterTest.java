package entropydata.databricks;

import static org.assertj.core.api.Assertions.assertThat;

import entropydata.databricks.DatabricksProperties.FilterProperties;
import java.util.List;
import org.junit.jupiter.api.Test;

class NameFilterTest {

  @Test
  void matchesEverythingWhenNotConfigured() {
    var filter = NameFilter.of(null);

    assertThat(filter.matches("prod")).isTrue();
    assertThat(filter.matches("dev_sandbox")).isTrue();
  }

  @Test
  void matchesEverythingWhenListsAreEmpty() {
    var filter = NameFilter.of(new FilterProperties(List.of(), List.of()));

    assertThat(filter.matches("prod")).isTrue();
  }

  @Test
  void excludesByGlob() {
    var filter = NameFilter.of(new FilterProperties(List.of(), List.of("dev_*", "staging")));

    assertThat(filter.matches("prod")).isTrue();
    assertThat(filter.matches("dev_sandbox")).isFalse();
    assertThat(filter.matches("staging")).isFalse();
  }

  @Test
  void includesOnlyMatchingNames() {
    var filter = NameFilter.of(new FilterProperties(List.of("prod", "analytics_*"), List.of()));

    assertThat(filter.matches("prod")).isTrue();
    assertThat(filter.matches("analytics_eu")).isTrue();
    assertThat(filter.matches("prod_backup")).isFalse();
  }

  @Test
  void excludeWinsOverInclude() {
    var filter = NameFilter.of(new FilterProperties(List.of("prod_*"), List.of("prod_tmp")));

    assertThat(filter.matches("prod_sales")).isTrue();
    assertThat(filter.matches("prod_tmp")).isFalse();
  }

  @Test
  void ignoresBlankPatternsFromEmptyConfiguration() {
    var filter = NameFilter.of(new FilterProperties(List.of(""), List.of("")));

    assertThat(filter.matches("prod")).isTrue();
  }

  @Test
  void matchesCaseInsensitively() {
    var filter = NameFilter.of(new FilterProperties(List.of(), List.of("DEV_*")));

    assertThat(filter.matches("dev_sandbox")).isFalse();
  }

  @Test
  void treatsGlobLiteralsAsText() {
    var filter = NameFilter.of(new FilterProperties(List.of("sales.eu"), List.of()));

    assertThat(filter.matches("sales.eu")).isTrue();
    assertThat(filter.matches("salesXeu")).isFalse();
  }
}
