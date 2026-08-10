package entropydata.databricks;

import entropydata.databricks.DatabricksProperties.FilterProperties;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Matches catalog, schema, and table names against configured glob patterns. An empty include list matches everything.
 */
record NameFilter(List<Pattern> include, List<Pattern> exclude) {

  static NameFilter of(FilterProperties properties) {
    if (properties == null) {
      return new NameFilter(List.of(), List.of());
    }
    return new NameFilter(compile(properties.include()), compile(properties.exclude()));
  }

  boolean matches(String name) {
    if (!include.isEmpty() && include.stream().noneMatch(pattern -> pattern.matcher(name).matches())) {
      return false;
    }
    return exclude.stream().noneMatch(pattern -> pattern.matcher(name).matches());
  }

  private static List<Pattern> compile(List<String> globs) {
    if (globs == null) {
      return List.of();
    }
    return globs.stream()
        .filter(glob -> !glob.isBlank())
        .map(NameFilter::toPattern)
        .toList();
  }

  private static Pattern toPattern(String glob) {
    var regex = Arrays.stream(glob.trim().split("\\*", -1))
        .map(Pattern::quote)
        .collect(Collectors.joining(".*"));
    // Unity Catalog identifiers are case-insensitive
    return Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
  }
}
