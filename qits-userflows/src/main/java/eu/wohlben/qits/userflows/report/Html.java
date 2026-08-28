package eu.wohlben.qits.userflows.report;

/** The one escaping rule every HTML-emitting renderer shares. */
final class Html {

  private Html() {}

  /** Escape for element content and attribute values alike — all five, so one rule fits both. */
  static String escape(String text) {
    if (text == null) {
      return "";
    }
    StringBuilder out = new StringBuilder(text.length());
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      switch (c) {
        case '&' -> out.append("&amp;");
        case '<' -> out.append("&lt;");
        case '>' -> out.append("&gt;");
        case '"' -> out.append("&quot;");
        case '\'' -> out.append("&#39;");
        default -> out.append(c);
      }
    }
    return out.toString();
  }
}
